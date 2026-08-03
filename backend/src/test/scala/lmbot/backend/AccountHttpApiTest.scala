package lmbot.backend

import java.sql.{Date as SqlDate, Time as SqlTime}
import java.time.{Duration, Instant, LocalDate, LocalTime, OffsetDateTime}
import java.util.{Base64, UUID}

import scala.compiletime.uninitialized
import scala.concurrent.duration.*

import com.sun.net.httpserver.HttpServer
import lmbot.backend.account.{AccountClientFactory, AccountService}
import lmbot.backend.auth.{AuthService, Passwords}
import lmbot.backend.config.{AppVersion, MasterKey}
import lmbot.backend.crypto.{AesGcm, EncryptionContext, EncryptionPurpose}
import lmbot.backend.db.{
  AccountRepo,
  LuxmedAccountRow,
  MonitorRepo,
  MonitorRow,
  SessionRepo,
  UserRepo
}
import lmbot.backend.http.{AccountRoutes, AuthRoutes, HealthRoutes, Server}
import lmbot.backend.luxmed.LuxmedConfig
import lmbot.backend.luxmed.support.{
  LuxmedResponseScripts,
  MockResponse,
  RealHttpLuxmedServer
}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.{Role, UserId}
import sttp.client3.*
import sttp.model.{StatusCode, Uri}

/** Drives the real server over real HTTP against real Postgres, with a real
  * loopback Luxmed server standing in for the linking network calls (modelled
  * on `HttpApiTest` and `AccountServiceTest`).
  */
class AccountHttpApiTest extends PostgresSuite:

  private val ttl = 7.days
  private val fixedInstant = Instant.parse("2026-07-30T08:00:00Z")
  private val fixedOffset = OffsetDateTime.parse("2026-07-30T10:00:00+02:00")
  private val key = MasterKey
    .fromBase64(Base64.getEncoder.encodeToString(Array.fill[Byte](32)(5)))
    .toOption
    .get
  private val crypto = AesGcm(key)

  private var server: HttpServer = uninitialized
  private var baseUri: Uri = uninitialized
  private var luxmed: RealHttpLuxmedServer = uninitialized
  private val http = HttpClientSyncBackend()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    luxmed = RealHttpLuxmedServer()
    val auth =
      AuthService(
        UserRepo(xa),
        SessionRepo(xa),
        ttl,
        () => OffsetDateTime.now()
      )
    val authRoutes = AuthRoutes(auth, cookieSecure = false, sessionTtl = ttl)
    val accounts = AccountRepo(xa)
    val baseConfig = LuxmedConfig(
      oldApi = Uri.unsafeParse(s"${luxmed.baseUri}/PatientPortalMobileAPI/api"),
      newApi = Uri.unsafeParse(s"${luxmed.baseUri}/PatientPortal"),
      appVersion = AppVersion.unsafeFromString("4.44.0"),
      deviceUuid = UUID.fromString("00000000-0000-4000-8000-000000000001")
    )
    val clients = AccountClientFactory.production(
      xa = xa,
      accounts = accounts,
      baseConfig = baseConfig,
      crypto = crypto,
      minimumSpacing = Duration.ZERO,
      now = () => fixedInstant
    )
    val accountService = AccountService(
      accounts = accounts,
      clients = clients,
      crypto = crypto,
      now = () => fixedInstant
    )
    val accountRoutes = AccountRoutes(auth, accountService)
    // Port 0 lets the OS choose, so tests never collide.
    server = Server.start(
      "127.0.0.1",
      0,
      HealthRoutes.endpoints ++ authRoutes.endpoints ++ accountRoutes.endpoints
    )
    baseUri = uri"http://127.0.0.1:${server.getAddress.getPort}"

  override def afterEach(context: AfterEach): Unit =
    if server != null then server.stop(0)
    if luxmed != null then luxmed.close()
    super.afterEach(context)

  private def aUser(username: String, password: String): UserId =
    UserId(
      UserRepo(xa)
        .insert(username, "Krzysiek", Passwords.hash(password), Role.User)
        .id
    )

  private def login(username: String, password: String) =
    basicRequest
      .post(uri"$baseUri/api/auth/login")
      .body(s"""{"username":"$username","password":"$password"}""")
      .contentType("application/json")
      .send(http)

  private def sessionCookieValue(
      response: Response[Either[String, String]]
  ): String =
    response
      .headers("Set-Cookie")
      .flatMap(_.split(";").headOption)
      .collectFirst {
        case kv if kv.startsWith("lmbot_session=") =>
          kv.drop("lmbot_session=".length)
      }
      .getOrElse(fail("no session cookie in login response"))

  private def loggedIn(
      username: String = "krzysiek",
      password: String = "s3cret"
  ): (UserId, String) =
    val id = aUser(username, password)
    val token = sessionCookieValue(login(username, password))
    (id, token)

  private def enqueueAuth(): Unit =
    LuxmedResponseScripts
      .realisticAuthFlow(
        accessToken = "AT1",
        refreshToken = "RT1",
        jwtToken = "JWT_TOKEN_1"
      )
      .foreach: response =>
        luxmed.enqueue(
          MockResponse(
            response.status,
            response.headers.groupMap(_._1)(_._2),
            response.body
          )
        )

  private def createBody(
      label: String = "Main",
      username: String = "user@example.com",
      password: String = "password123"
  ): String =
    s"""{"label":"$label","username":"$username","password":"$password"}"""

  private def createAccount(token: String, label: String = "Main") =
    enqueueAuth()
    basicRequest
      .post(uri"$baseUri/api/accounts")
      .cookie("lmbot_session", token)
      .body(createBody(label = label))
      .contentType("application/json")
      .send(http)

  private def insertAccount(
      ownerId: UserId,
      label: String,
      username: String = "stored@example.com"
  ): Long =
    val repo = AccountRepo(xa)
    val id = repo.reserveId()
    repo.insert(
      LuxmedAccountRow(
        id.value,
        ownerId.value,
        label,
        crypto
          .encrypt(
            username,
            EncryptionContext(ownerId, id, EncryptionPurpose.Username)
          )
          .render,
        s"encrypted-password-$label",
        s"encrypted-device-$label",
        None,
        "active",
        None,
        None,
        fixedOffset,
        fixedOffset
      )
    )
    id.value

  private def insertMonitor(accountId: Long): Long =
    val repo = MonitorRepo(xa)
    val monitorId = repo.reserveId()
    repo.insert(
      MonitorRow(
        monitorId,
        accountId,
        "Dermatologist",
        3L,
        "Warsaw",
        42L,
        "Dermatology",
        List(9L),
        List("Puławska"),
        Nil,
        Nil,
        SqlDate.valueOf(LocalDate.parse("2026-08-01")),
        SqlDate.valueOf(LocalDate.parse("2026-08-31")),
        SqlTime.valueOf(LocalTime.parse("08:00")),
        SqlTime.valueOf(LocalTime.parse("16:00")),
        0x7f.toShort,
        false,
        10,
        "active",
        fixedOffset,
        fixedOffset
      )
    )
    monitorId

  test("create is 401 without a session cookie"):
    val r = basicRequest
      .post(uri"$baseUri/api/accounts")
      .body(createBody())
      .contentType("application/json")
      .send(http)
    assertEquals(r.code, StatusCode.Unauthorized)

  test("create is 401 with an invalid session cookie"):
    val r = basicRequest
      .post(uri"$baseUri/api/accounts")
      .cookie("lmbot_session", "not-a-real-token")
      .body(createBody())
      .contentType("application/json")
      .send(http)
    assertEquals(r.code, StatusCode.Unauthorized)

  test("create succeeds and returns the literal account view JSON"):
    val (_, token) = loggedIn()
    val r = createAccount(token)

    assertEquals(r.code, StatusCode.Ok)
    assertEquals(
      r.body,
      Right(
        """{"id":1,"label":"Main","username":"user@example.com","status":"active","lastSuccessfulLogin":"2026-07-30T08:00:00Z"}"""
      )
    )

  test("create never echoes credentials, cookies, or Luxmed tokens back"):
    val (_, token) = loggedIn()
    val r = createAccount(token)

    val body = r.body.getOrElse(fail("expected a body"))
    val forbidden = List(
      "password123",
      "s3cret",
      "AT1",
      "RT1",
      "JWT_TOKEN_1",
      "00000000-0000-4000-8000-000000000001",
      token,
      "lmbot_session"
    )
    forbidden.foreach: needle =>
      assert(!body.contains(needle), s"response leaked '$needle': $body")

  test("create with a blank label is 422 and never contacts Luxmed"):
    val (_, token) = loggedIn()
    val r = basicRequest
      .post(uri"$baseUri/api/accounts")
      .cookie("lmbot_session", token)
      .body(createBody(label = "   "))
      .contentType("application/json")
      .send(http)

    assertEquals(r.code, StatusCode.UnprocessableEntity)
    assertEquals(luxmed.requests, Nil)

  test("create with a duplicate label is 409 and never contacts Luxmed"):
    val (ownerId, token) = loggedIn()
    insertAccount(ownerId, "Main")
    val r = basicRequest
      .post(uri"$baseUri/api/accounts")
      .cookie("lmbot_session", token)
      .body(createBody(label = "Main"))
      .contentType("application/json")
      .send(http)

    assertEquals(r.code, StatusCode.Conflict)
    assertEquals(luxmed.requests, Nil)

  test("list returns only the caller's own accounts and no secrets"):
    val (firstOwner, firstToken) = loggedIn("first", "pw-first")
    val secondOwner = aUser("second", "pw-second")
    insertAccount(firstOwner, "Mine")
    insertAccount(secondOwner, "NotMine")

    val r = basicRequest
      .get(uri"$baseUri/api/accounts")
      .cookie("lmbot_session", firstToken)
      .send(http)

    assertEquals(r.code, StatusCode.Ok)
    val body = r.body.getOrElse(fail("expected a body"))
    assert(body.contains("Mine"))
    assert(!body.contains("NotMine"))
    assert(!body.contains("encrypted-password"))
    assert(!body.contains("encrypted-device"))

  test("delete is 404 for another owner's account"):
    val firstOwner = aUser("first", "pw-first")
    val (_, secondToken) = loggedIn("second", "pw-second")
    val accountId = insertAccount(firstOwner, "Main")

    val r = basicRequest
      .delete(uri"$baseUri/api/accounts/$accountId")
      .cookie("lmbot_session", secondToken)
      .send(http)

    assertEquals(r.code, StatusCode.NotFound)
    assert(
      AccountRepo(xa)
        .findOwned(lmbot.shared.domain.AccountId(accountId), firstOwner)
        .isDefined
    )

  test("delete removes the account and cascades its monitors"):
    val (ownerId, token) = loggedIn()
    val accountId = insertAccount(ownerId, "Main")
    val monitorId = insertMonitor(accountId)

    val r = basicRequest
      .delete(uri"$baseUri/api/accounts/$accountId")
      .cookie("lmbot_session", token)
      .send(http)

    assertEquals(r.code, StatusCode.Ok)
    assertEquals(
      AccountRepo(xa)
        .findOwned(lmbot.shared.domain.AccountId(accountId), ownerId),
      None
    )
    assertEquals(
      MonitorRepo(xa).findOwned(
        lmbot.shared.domain.MonitorId(monitorId),
        ownerId
      ),
      None
    )
