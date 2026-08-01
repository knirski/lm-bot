package lmbot.backend

import java.net.{InetSocketAddress, ServerSocket}
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}

import scala.io.{Codec, Source}
import scala.jdk.CollectionConverters.*

import com.sun.net.httpserver.HttpServer
import lmbot.backend.config.{AppVersion, Config, MasterKey, Port, Secret}
import lmbot.backend.luxmed.support.LuxmedResponseScripts
import sttp.model.Uri

/** Every fixed input the Plan 4 browser acceptance run needs, and the
  * deterministic stand-in for Luxmed's HTTP boundary.
  *
  * Nothing here is a secret: the usernames and passwords are literals that only
  * ever reach an embedded database and a loopback stub, and the master key is a
  * fixed byte pattern. They live in test scope so no fixture credential can
  * reach `backend/src/main`, and there is deliberately no runtime "mock Luxmed"
  * switch in `Config` — substituting the boundary is only possible from a
  * test-scope main.
  *
  * Ports are discovered rather than fixed so an acceptance run never collides
  * with a `startDev` server or another run.
  */
object Plan4AcceptanceConfig:

  val host: String = "127.0.0.1"

  /** The lm-bot operator the browser signs in as (`AdminBootstrap` creates it
    * on first start, exactly as it does in production).
    */
  val adminUsername: String = "acceptance-admin"
  val adminPassword: String = "acceptance-admin-not-a-secret"

  /** The Luxmed credentials typed into the link form. The stub accepts any
    * credentials; these exist so the flow has something to type.
    */
  val luxmedUsername: String = "acceptance.user@example.test"
  val luxmedPassword: String = "acceptance-luxmed-not-a-secret"
  val accountLabel: String = "Acceptance"

  /** A second lm-bot operator, owning nothing. It exists so owner scoping can
    * be attacked from a browser: production has no user-management UI until
    * Plan 7, so the harness is the only thing that can create a second operator
    * to attempt a cross-owner read as.
    */
  val intruderUsername: String = "acceptance-intruder"
  val intruderPassword: String = "acceptance-intruder-not-a-secret"

  /** A fixed 32-byte AES key, so an acceptance database written by one run is
    * still readable by the next one.
    */
  val masterKey: MasterKey =
    MasterKey
      .fromBase64(
        Base64.getEncoder.encodeToString(Array.fill[Byte](32)(11))
      )
      .fold(error => throw IllegalStateException(error), identity)

  /** How long the stub says its access tokens live. `LuxmedClient` refreshes
    * proactively at 300 seconds, so [[refreshBoundary]] is just past the point
    * where a session minted at start-up becomes refreshable.
    */
  val sessionLifetimeSeconds: Int = 600
  val refreshBoundary: Duration = Duration.ofSeconds(301)

  /** Ask the OS for a free port and hand it back. Binding on 0 and reading the
    * assigned port is the same trick the HTTP tests use; releasing it first is
    * what lets the same port be re-bound by a restarted composition graph.
    */
  def freePort(): Int =
    val socket = ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()

  def config(dbPort: Int, httpPort: Int): Config =
    Config(
      dbUrl = s"jdbc:postgresql://$host:$dbPort/lmbot",
      dbUser = "lmbot",
      dbPassword = Secret("lmbot"),
      httpHost = host,
      httpPort = Port
        .fromInt(httpPort)
        .fold(error => throw IllegalStateException(error), identity),
      // The acceptance server speaks plain HTTP on loopback, so a Secure
      // cookie would never be sent back.
      cookieSecure = false,
      sessionTtl = Duration.ofDays(7),
      luxmedAppVersion = AppVersion.unsafeFromString("4.44.0"),
      adminUsername = Some(adminUsername),
      adminPassword = Some(Secret(adminPassword)),
      masterKey = masterKey
    )

  /** A loopback stand-in for Luxmed, routed by path rather than by a FIFO
    * script.
    *
    * Routing is what makes it usable from a browser: the monitor form asks for
    * cities and services concurrently, and a reload or a retry asks again, so
    * the number and order of Luxmed calls is decided by the person clicking,
    * not by the harness. A queue of responses (`StubLuxmedBackend`,
    * `RealHttpLuxmedServer`) answers whichever request arrives first and would
    * hand a service list to a city request. The response bodies themselves are
    * still the shared ones: [[LuxmedResponseScripts]] for the auth flow and the
    * committed dictionary fixtures for the rest.
    *
    * It also counts what it was asked for, which is what the restart/refresh
    * gate reads: a proactive refresh must spend a `refresh_token` grant and no
    * second password grant.
    */
  final class StubLuxmedServer:

    private val passwordGrants = AtomicInteger(0)
    private val refreshGrants = AtomicInteger(0)
    private val issued = AtomicInteger(0)
    private val latestRefresh = AtomicReference("")
    private val unrouted = ConcurrentLinkedQueue[String]()

    private val server = HttpServer.create(InetSocketAddress(host, 0), 0)
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
    server.createContext(
      "/",
      exchange =>
        val body =
          Source
            .fromInputStream(exchange.getRequestBody)(using Codec.UTF8)
            .mkString
        val response = route(exchange.getRequestURI.getRawPath, body)
        response.headers.foreach: (name, value) =>
          exchange.getResponseHeaders.add(name, value)
        val bytes = response.body.getBytes("UTF-8")
        exchange.sendResponseHeaders(response.status, bytes.length)
        exchange.getResponseBody.write(bytes)
        exchange.close()
    )
    server.start()

    def baseUri: String = s"http://$host:${server.getAddress.getPort}"
    def oldApi: Uri = Uri.unsafeParse(s"$baseUri/PatientPortalMobileAPI/api")
    def newApi: Uri = Uri.unsafeParse(s"$baseUri/PatientPortal")

    def passwordGrantCount: Int = passwordGrants.get()
    def refreshGrantCount: Int = refreshGrants.get()

    /** The refresh token the stub most recently minted. Compared against the
      * persisted one to prove the rotation was written; never rendered.
      */
    def latestIssuedRefreshToken: String = latestRefresh.get()

    /** Paths the stub did not recognise. A non-empty list means the app asked
      * Luxmed something this harness does not model, which would otherwise
      * surface only as an opaque 404.
      */
    def unroutedPaths: List[String] = unrouted.asScala.toList

    def close(): Unit = server.stop(0)

    private def route(
        path: String,
        body: String
    ): LuxmedResponseScripts.Response =
      if path.endsWith("/PatientPortalMobileAPI/api/token") then token(body)
      else if path.endsWith("/Account/LogInToApp") then
        LuxmedResponseScripts.logInToAppRedirect()
      else if path.endsWith("/NewPortal/Page/Reservation") then
        LuxmedResponseScripts.reservationPage(s"JWT_TOKEN_${issued.get()}")
      else if path.endsWith("/Dictionary/cities") then json("cities.json")
      else if path.endsWith("/Dictionary/serviceVariantsGroups") then
        json("service-variants.json")
      else if path.endsWith("/Dictionary/facilitiesAndDoctors") then
        json("facilities-and-doctors.json")
      else
        unrouted.add(path)
        LuxmedResponseScripts.Response(404)

    /** One OAuth token endpoint serves both grants, as Luxmed's does; which
      * grant was spent is read off the form body.
      */
    private def token(body: String): LuxmedResponseScripts.Response =
      if body.contains("grant_type=refresh_token") then
        refreshGrants.incrementAndGet()
      else passwordGrants.incrementAndGet()
      val serial = issued.incrementAndGet()
      latestRefresh.set(s"RT$serial")
      LuxmedResponseScripts.oauthPasswordGrant(
        accessToken = s"AT$serial",
        refreshToken = s"RT$serial",
        expiresIn = sessionLifetimeSeconds
      )

    private def json(name: String): LuxmedResponseScripts.Response =
      LuxmedResponseScripts.Response(
        200,
        List("Content-Type" -> "application/json"),
        fixture(name)
      )

    /** The same committed fixtures the wire tests decode, so the browser sees
      * dictionary data of exactly the shape production parses.
      */
    private def fixture(name: String): String =
      val path = s"/luxmed/$name"
      val stream = Option(getClass.getResourceAsStream(path))
        .getOrElse(throw IllegalArgumentException(s"Missing fixture: $path"))
      try Source.fromInputStream(stream)(using Codec.UTF8).mkString
      finally stream.close()
