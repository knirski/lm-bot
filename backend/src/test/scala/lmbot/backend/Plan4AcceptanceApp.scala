package lmbot.backend

import java.net.InetSocketAddress
import java.time.{Duration, Instant, OffsetDateTime}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, Executors}

import com.augustnagro.magnum.Transactor
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import lmbot.backend.account.{
  AccountClientFactory,
  AccountService,
  DictionaryService
}
import lmbot.backend.auth.{AdminBootstrap, AuthService, Passwords}
import lmbot.backend.config.Config
import lmbot.backend.crypto.AesGcm
import lmbot.backend.db.{
  AccountRepo,
  Database,
  MonitorRepo,
  SessionRepo,
  UserRepo
}
import lmbot.backend.http.{
  AccountRoutes,
  AuthRoutes,
  DictionaryRoutes,
  HealthRoutes,
  MonitorRoutes,
  Server,
  StaticRoutes
}
import lmbot.backend.luxmed.{LuxmedConfig, PostgresSessionStore}
import lmbot.backend.monitor.MonitorService
import lmbot.backend.support.EmbeddedPg
import lmbot.shared.domain.{AccountId, Role}
import org.slf4j.LoggerFactory

/** A test-scope main for the Plan 4 browser acceptance run: the ordinary
  * composition graph from `Main`, with the owned Luxmed HTTP boundary — and
  * only that boundary — answered by [[Plan4AcceptanceConfig.StubLuxmedServer]]
  * instead of the real API.
  *
  * Everything else is real: the embedded database and its Flyway migrations,
  * the admin bootstrap, Argon2 password hashing, the AES-256-GCM envelopes, the
  * repositories, the services with their ownership checks, the whole Tapir
  * endpoint list, and the linked frontend served from the classpath. The stub
  * is substituted by pointing `LuxmedConfig` at a loopback base URI, which is
  * how the account and dictionary HTTP tests already do it; production keeps
  * using `LuxmedTransport.production` and has no configuration that could
  * redirect it.
  *
  * Living in `src/test` is what keeps it out of the production artifact: the
  * assembled jar has one main class, and this is not on its classpath.
  *
  * Run it with:
  * {{{
  *   sbt "backend/Test/runMain lmbot.backend.Plan4AcceptanceApp"
  * }}}
  *
  * It prints the app URL, the sign-in credentials, and a control URL. The
  * control server exists for the restart/refresh release gate:
  *
  *   - `POST /restart?advanceSeconds=301` rebuilds the entire composition graph
  *     on the same port with its Luxmed-facing clock moved past the proactive
  *     refresh boundary. The embedded database keeps its rows, so what the new
  *     graph starts from is exactly what a restarted process reads: a
  *     persisted, encrypted session and nothing in memory.
  *   - `POST /second-owner` creates a second operator, owning nothing, for the
  *     cross-owner attempt to sign in as.
  *   - `GET /status` reports how many password and refresh grants the stub was
  *     asked for, and whether the session persisted in the database now holds
  *     the most recently issued refresh token. Tokens themselves are never
  *     rendered — only the comparison.
  */
object Plan4AcceptanceApp:

  private val log = LoggerFactory.getLogger(getClass)

  /** One running composition graph, and the clock offset it was built with. */
  final private case class Graph(server: HttpServer, clockOffset: Duration)

  def main(args: Array[String]): Unit =
    val dbPort = Plan4AcceptanceConfig.freePort()
    val httpPort = Plan4AcceptanceConfig.freePort()
    val controlPort = Plan4AcceptanceConfig.freePort()

    val db = EmbeddedPg.startForDev(dbPort)
    val luxmed = Plan4AcceptanceConfig.StubLuxmedServer()
    val config = Plan4AcceptanceConfig.config(dbPort, httpPort)

    val ds = Database.dataSource(
      config.dbUrl,
      config.dbUser,
      config.dbPassword.value
    )
    Database.migrate(ds)
    val xa = Database.transactor(ds)
    val crypto = AesGcm(config.masterKey)

    bootstrapAdmin(config, UserRepo(xa))

    val graph = AtomicReference(
      start(config, xa, luxmed, crypto, Duration.ZERO)
    )
    val control =
      startControl(controlPort, config, xa, luxmed, crypto, graph)

    log.info(s"Acceptance app:      http://${config.httpHost}:$httpPort")
    log.info(s"Acceptance control:  http://${config.httpHost}:$controlPort")
    log.info(s"Sign in as:          ${Plan4AcceptanceConfig.adminUsername}")
    log.info(s"Luxmed stub:         ${luxmed.baseUri}")

    Runtime.getRuntime.addShutdownHook(
      Thread: () =>
        log.info("Shutting down")
        graph.get().server.stop(0)
        control.stop(0)
        luxmed.close()
        ds.close()
        db.close()
    )

    // Nothing else drives this process: it stays up until it is killed, the
    // way a server does.
    CountDownLatch(1).await()

  private def bootstrapAdmin(config: Config, users: UserRepo): Unit =
    AdminBootstrap(users).run(
      config.adminUsername,
      config.adminPassword.map(_.value)
    ) match
      case AdminBootstrap.Outcome.Created(username) =>
        log.info(s"Created initial admin account '$username'")
      case AdminBootstrap.Outcome.SkippedUsersExist =>
        log.info("Users already exist; skipping admin bootstrap")
      case AdminBootstrap.Outcome.MissingCredentials =>
        log.warn("No admin credentials; nobody can log in")

  /** The wiring from `Main`, differing only in where `LuxmedConfig` points and
    * in the clock the Luxmed-facing collaborators read.
    *
    * The offset moves only that clock. Login sessions keep the wall clock, so
    * advancing past a Luxmed token boundary never signs the browser out — the
    * two clocks answer different questions.
    */
  private def start(
      config: Config,
      xa: Transactor,
      luxmed: Plan4AcceptanceConfig.StubLuxmedServer,
      crypto: AesGcm,
      clockOffset: Duration
  ): Graph =
    val luxmedNow = () => Instant.now().plus(clockOffset)

    val users = UserRepo(xa)
    val sessions = SessionRepo(xa)
    val auth =
      AuthService(
        users,
        sessions,
        config.sessionTtl,
        () => OffsetDateTime.now()
      )
    val authRoutes = AuthRoutes(auth, config.cookieSecure, config.sessionTtl)

    val accountRepo = AccountRepo(xa)
    val luxmedBaseConfig = LuxmedConfig(
      oldApi = luxmed.oldApi,
      newApi = luxmed.newApi,
      appVersion = config.luxmedAppVersion,
      // Overridden per account by `AccountClientFactory`, exactly as in `Main`.
      deviceUuid = UUID.randomUUID()
    )
    val accountClients = AccountClientFactory.production(
      xa = xa,
      accounts = accountRepo,
      baseConfig = luxmedBaseConfig,
      crypto = crypto,
      now = luxmedNow
    )
    val accountService =
      AccountService(accountRepo, accountClients, crypto, now = luxmedNow)
    val accountRoutes = AccountRoutes(auth, accountService)
    val dictionaryRoutes =
      DictionaryRoutes(auth, DictionaryService(accountClients))
    val monitorService = MonitorService(MonitorRepo(xa), accountRepo)
    val monitorRoutes = MonitorRoutes(auth, monitorService)

    val server = Server.start(
      config.httpHost,
      config.httpPort.value,
      HealthRoutes.endpoints ++ authRoutes.endpoints ++
        accountRoutes.endpoints ++ dictionaryRoutes.endpoints ++
        monitorRoutes.endpoints ++ StaticRoutes.endpoints
    )
    log.info(
      s"Composition graph started (Luxmed clock offset ${clockOffset.toSeconds}s)"
    )
    Graph(server, clockOffset)

  private def startControl(
      port: Int,
      config: Config,
      xa: Transactor,
      luxmed: Plan4AcceptanceConfig.StubLuxmedServer,
      crypto: AesGcm,
      graph: AtomicReference[Graph]
  ): HttpServer =
    val server = HttpServer.create(
      InetSocketAddress(Plan4AcceptanceConfig.host, port),
      0
    )
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
    server.createContext(
      "/",
      exchange =>
        try
          val body = exchange.getRequestURI.getRawPath match
            case "/restart" =>
              val advance = advanceSeconds(exchange)
              graph.get().server.stop(0)
              graph.set(
                start(config, xa, luxmed, crypto, Duration.ofSeconds(advance))
              )
              s"""{"restarted":true,"clockOffsetSeconds":$advance}"""
            case "/status" =>
              status(xa, luxmed, crypto, graph.get().clockOffset)
            case "/second-owner" =>
              secondOwner(UserRepo(xa))
            case other =>
              s"""{"error":"unknown control path","path":${quote(other)}}"""
          respond(exchange, 200, body)
        catch
          case t: Throwable =>
            log.error("Control request failed", t)
            respond(
              exchange,
              500,
              s"""{"error":${quote(
                  Option(t.getMessage).getOrElse(t.toString)
                )}}"""
            )
    )
    server.start()
    server

  /** How far to move the Luxmed clock, in seconds. Defaults to just past the
    * proactive refresh boundary, which is the only value the release gate
    * needs.
    */
  private def advanceSeconds(exchange: HttpExchange): Long =
    Option(exchange.getRequestURI.getQuery)
      .flatMap(_.split('&').find(_.startsWith("advanceSeconds=")))
      .map(_.stripPrefix("advanceSeconds="))
      .flatMap(_.toLongOption)
      .getOrElse(Plan4AcceptanceConfig.refreshBoundary.toSeconds)

  /** Creates the second, owns-nothing operator the cross-owner attempt signs in
    * as. A real row through the real repository with a real Argon2 hash, so the
    * attempt is an ordinary authenticated request that the services must refuse
    * on ownership grounds alone.
    */
  private def secondOwner(users: UserRepo): String =
    val username = Plan4AcceptanceConfig.intruderUsername
    val existing = users.findByUsername(username)
    if existing.isEmpty then
      users.insert(
        username,
        "Acceptance intruder",
        Passwords.hash(Plan4AcceptanceConfig.intruderPassword),
        Role.User
      )
    s"""{"username":"$username","created":${existing.isEmpty}}"""

  /** What the restart/refresh gate reads. `rotationPersisted` is a comparison,
    * not a token: the stored refresh token is never rendered anywhere.
    */
  private def status(
      xa: Transactor,
      luxmed: Plan4AcceptanceConfig.StubLuxmedServer,
      crypto: AesGcm,
      clockOffset: Duration
  ): String =
    val owner =
      UserRepo(xa).findByUsername(Plan4AcceptanceConfig.adminUsername).map(_.id)
    val stored = for
      ownerId <- owner
      account <- AccountRepo(xa).listOwned(ownerId).headOption
      session <- PostgresSessionStore(
        xa,
        ownerId,
        AccountId(account.id),
        crypto
      ).load().toOption.flatten
    yield session.refreshToken.value == luxmed.latestIssuedRefreshToken
    val unrouted = luxmed.unroutedPaths.map(quote).mkString(",")
    s"""{"clockOffsetSeconds":${clockOffset.toSeconds},""" +
      s""""passwordGrants":${luxmed.passwordGrantCount},""" +
      s""""refreshGrants":${luxmed.refreshGrantCount},""" +
      s""""sessionPersisted":${stored.isDefined},""" +
      s""""rotationPersisted":${stored.getOrElse(false)},""" +
      s""""unroutedLuxmedPaths":[$unrouted]}"""

  private def respond(exchange: HttpExchange, status: Int, body: String): Unit =
    try
      val bytes = body.getBytes("UTF-8")
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(status, bytes.length)
      exchange.getResponseBody.write(bytes)
    finally exchange.close()

  /** Minimal JSON string literal encoder for the control responses, since
    * `unroutedPaths` and the request path carry data the app or the browser can
    * influence.
    */
  private def quote(value: String): String =
    val escaped = value.flatMap:
      case '"'                 => "\\\""
      case '\\'                => "\\\\"
      case '\n'                => "\\n"
      case '\r'                => "\\r"
      case '\t'                => "\\t"
      case c if c.toInt < 0x20 => f"\\u${c.toInt}%04x"
      case c                   => c.toString
    s""""$escaped""""
