package lmbot.backend.luxmed

import java.time.Duration
import java.util.UUID

import gears.async.{Async, Future}
import lmbot.backend.config.{AppVersion, SafeDiagnostic, Secret}
import lmbot.backend.luxmed.model.*
import lmbot.backend.luxmed.support.{
  FakeTime,
  GearsTest,
  MockLuxmedServer,
  RecordedRequest
}
import sttp.model.Uri

class LuxmedClientAuthTest extends munit.FunSuite with GearsTest:

  private val testUuid = UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")

  private def header(request: RecordedRequest, name: String): Option[String] =
    request.headers.collectFirst {
      case (key, values) if key.equalsIgnoreCase(name) => values.head
    }

  final private class FailOnceStore extends SessionStore:
    private val delegate = InMemorySessionStore()
    private var failNextReplace = true

    def load(): Either[SessionStoreError, Option[LuxmedSession]] =
      delegate.load()

    def replace(
        expectedRefreshToken: Option[Secret],
        updatedSession: LuxmedSession
    ): Either[SessionStoreError, Unit] =
      if failNextReplace then
        failNextReplace = false
        Left(SessionStoreError.Unavailable("temporary store failure"))
      else delegate.replace(expectedRefreshToken, updatedSession)

    def clear(): Either[SessionStoreError, Unit] = delegate.clear()

  private def withClient[T](
      store: SessionStore = InMemorySessionStore()
  )(body: (LuxmedClient, MockLuxmedServer, FakeTime, SessionStore) => T): T =
    val mock = MockLuxmedServer()
    try
      val config = LuxmedConfig(
        oldApi = Uri.unsafeParse(s"${mock.baseUri}/PatientPortalMobileAPI/api"),
        newApi = Uri.unsafeParse(s"${mock.baseUri}/PatientPortal"),
        appVersion = AppVersion.unsafeFromString("4.44.0"),
        deviceUuid = testUuid
      )
      val transport = LuxmedTransport(config)
      val credentials = Credentials("user@example.com", Secret("password123"))
      val fake = FakeTime()
      val gate = AccountGate(Duration.ZERO, () => fake.now(), fake.sleeper)
      val client = LuxmedClient(
        transport,
        credentials,
        gate,
        store,
        now = () => fake.now()
      )
      body(client, mock, fake, store)
    finally mock.close()

  test("three-step auth flow succeeds"):
    withClient(): (client, mock, _, _) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":599,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Set-Cookie" -> "ASP.NET_SessionId=sess1")
      )
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map(
          "Set-Cookie" -> "jwt=JWT1",
          "Authorization-Token" -> "Bearer JWT_TOKEN_1"
        )
      )
      val result = runAsync:
        client.authenticate()
      assert(result.isRight, s"expected success, got $result")
      val session = result.toOption.get
      assertEquals(session.refreshToken.value, "RT1")
      assertEquals(session.jwtToken.value, "JWT_TOKEN_1")
      assertEquals(session.tokenType, TokenType.Bearer)
      val requests = mock.requests
      assertEquals(requests.map(_.method), List("POST", "GET", "GET"))
      assertEquals(
        requests.map(_.path),
        List(
          "/PatientPortalMobileAPI/api/token",
          "/PatientPortal/Account/LogInToApp",
          "/PatientPortal/NewPortal/Page/Reservation"
        )
      )
      assertEquals(
        requests(1).rawQuery,
        Some("app=search&client=3&lang=pl")
      )
      assert(requests.head.body.contains("client_id=Android"))
      assert(requests.head.body.contains("grant_type=password"))
      assert(requests.head.body.contains("username=user%40example.com"))
      assert(requests.head.body.contains("password=password123"))
      assert(
        header(requests.head, "Content-Type")
          .exists(_.startsWith("application/x-www-form-urlencoded"))
      )
      assertEquals(header(requests(1), "Authorization"), Some("AT1"))
      assertEquals(
        header(requests(1), "X-Requested-With"),
        Some("pl.luxmed.pp")
      )
      assertEquals(
        header(requests(2), "Cookie"),
        Some("ASP.NET_SessionId=sess1")
      )

  test("authenticate stores session in the store"):
    withClient(): (client, mock, _, _) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_1")
      )
      runAsync:
        client.authenticate()
      val stored = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assertEquals(stored, Right("AT1"))

  test("authenticate replaces an existing stored session"):
    withClient(): (client, mock, fake, store) =>
      val oldSession = LuxmedSession(
        accessToken = Secret("AT_OLD"),
        tokenType = TokenType.Bearer,
        refreshToken = Secret("RT_OLD"),
        expiresAt = fake.now().plusSeconds(600),
        jwtToken = Secret("JWT_OLD"),
        cookies = CookieJar.empty
      )
      assertEquals(store.replace(None, oldSession), Right(()))
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT_NEW","expires_in":600,"refresh_token":"RT_NEW","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_NEW")
      )
      val result = runAsync:
        client.authenticate()
      assertEquals(result.map(_.refreshToken.value), Right("RT_NEW"))
      assertEquals(
        store.load().map(_.map(_.refreshToken.value)),
        Right(Some("RT_NEW"))
      )

  test("missing JWT in response is ProtocolViolation"):
    withClient(): (client, mock, _, _) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(status = 200, body = "")
      val result = runAsync:
        client.authenticate()
      assert(result.left.exists:
        case LuxmedError.ProtocolViolation(_) => true
        case _                                => false)

  test("refresh happens when session is expiring"):
    withClient(): (client, mock, _, _) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":30,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_1")
      )
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_2")
      )
      runAsync:
        client.authenticate()
      val result = runAsync:
        client.withSession: (_, session) =>
          Right(session.refreshToken.value)
      assertEquals(result, Right("RT2"))

  test("301 seconds remaining does not refresh"):
    withClient(): (client, mock, fake, _) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_1")
      )
      runAsync:
        client.authenticate()
      fake.advance(Duration.ofSeconds(299))
      val result = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assertEquals(result, Right("AT1"))
      assertEquals(
        mock.requests.count(_.body.contains("grant_type=refresh_token")),
        0
      )

  test("300 seconds remaining refreshes exactly once"):
    withClient(): (client, mock, fake, _) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_1")
      )
      runAsync:
        client.authenticate()
      fake.advance(Duration.ofSeconds(300))
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_2")
      )
      val result = runAsync:
        client.withSession: (_, session) =>
          Right(session.refreshToken.value)
      assertEquals(result, Right("RT2"))
      assertEquals(
        mock.requests.count(_.body.contains("grant_type=refresh_token")),
        1
      )

  test("concurrent session operations perform one refresh transaction"):
    withClient(): (client, mock, fake, _) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_1")
      )
      runAsync:
        client.authenticate()
      fake.advance(Duration.ofSeconds(301))
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_2")
      )
      val results = runAsync:
        Async.group:
          val first = Future:
            client.withSession: (_, session) =>
              Right(session.accessToken.value)
          val second = Future:
            client.withSession: (_, session) =>
              Right(session.accessToken.value)
          List(first.awaitResult, second.awaitResult)
      assert(results.forall(_.isSuccess))
      assertEquals(
        results.flatMap(_.toOption),
        List(Right("AT2"), Right("AT2"))
      )
      assertEquals(
        mock.requests.count(_.body.contains("grant_type=refresh_token")),
        1
      )

  test("malformed OAuth success does not expose tokens in DecodeFailed"):
    withClient(): (client, mock, _, _) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"ACCESS_SECRET","refresh_token":"REFRESH_SECRET","expires_in":oops}"""
      )
      val result = runAsync:
        client.authenticate()
      result match
        case Left(LuxmedError.DecodeFailed(details)) =>
          assert(!details.value.contains("ACCESS_SECRET"))
          assert(!details.value.contains("REFRESH_SECRET"))
        case other => fail(s"expected DecodeFailed, got $other")

  test(
    "initial bootstrap expiry returns without a second password grant and retries bootstrap"
  ):
    withClient(): (client, mock, _, _) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "session has expired")
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_1")
      )

      val first = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)

      assertEquals(first, Left(LuxmedError.SessionExpired))
      assertEquals(
        mock.requests.count(_.body.contains("grant_type=password")),
        1
      )

      val second = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)

      assertEquals(second, Right("AT1"))
      assertEquals(
        mock.requests.count(_.body.contains("grant_type=password")),
        1
      )

  test("persistence failure retries the store without another HTTP request"):
    withClient(FailOnceStore()): (client, mock, _, _) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_1")
      )
      val first = runAsync:
        client.authenticate()
      assert(first.left.exists(_.isInstanceOf[LuxmedError.PersistenceFailed]))
      val requestCount = mock.requests.size
      val second = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assertEquals(second, Right("AT1"))
      assertEquals(mock.requests.size, requestCount)

  test(
    "bootstrap failure after refresh resumes without reusing the old refresh token"
  ):
    withClient(): (client, mock, fake, _) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_1")
      )
      runAsync:
        client.authenticate()
      fake.advance(Duration.ofSeconds(301))
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      mock.enqueue(status = 503, body = "temporary failure")
      val first = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assert(first.isLeft)
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_2")
      )
      val second = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assertEquals(second, Right("AT2"))
      assertEquals(
        mock.requests.count(_.body.contains("grant_type=refresh_token")),
        1
      )

  test(
    "bootstrap expiry after refresh returns without password fallback and retries bootstrap"
  ):
    withClient(): (client, mock, fake, _) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_1")
      )
      runAsync:
        client.authenticate()
      fake.advance(Duration.ofSeconds(301))
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "session has expired")
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_2")
      )

      val first = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)

      assertEquals(first, Left(LuxmedError.SessionExpired))
      assertEquals(
        mock.requests.count(_.body.contains("grant_type=password")),
        1
      )

      val second = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)

      assertEquals(second, Right("AT2"))
      assertEquals(
        mock.requests.count(_.body.contains("grant_type=refresh_token")),
        1
      )
      assertEquals(
        mock.requests.count(_.body.contains("grant_type=password")),
        1
      )

  test(
    "refresh rejection clears stale state and performs one password fallback"
  ):
    withClient(): (client, mock, fake, store) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_1")
      )
      runAsync:
        client.authenticate()
      fake.advance(Duration.ofSeconds(301))
      mock.enqueue(
        status = 401,
        body =
          """{"error":{"code":1,"message":"You have been logged out due to inactivity."}}"""
      )
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_2")
      )
      val result = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assertEquals(result, Right("AT2"))
      assertEquals(
        mock.requests.count(_.body.contains("grant_type=password")),
        2
      )
      assertEquals(
        store.load().map(_.map(_.refreshToken.value)),
        Right(Some("RT2"))
      )

  test("invalid refresh grant performs one password fallback"):
    withClient(): (client, mock, fake, _) =>
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_1")
      )
      runAsync:
        client.authenticate()
      fake.advance(Duration.ofSeconds(301))
      mock.enqueue(status = 401, body = """{"error":"invalid_grant"}""")
      mock.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      mock.enqueue(status = 200, body = "")
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Authorization-Token" -> "Bearer JWT_2")
      )
      val result = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assertEquals(result, Right("AT2"))
      assertEquals(
        mock.requests.count(_.body.contains("grant_type=refresh_token")),
        1
      )
      assertEquals(
        mock.requests.count(_.body.contains("grant_type=password")),
        2
      )

  test("a fresh client loads a non-expiring stored session"):
    withClient(): (client, mock, fake, store) =>
      val stored = LuxmedSession(
        accessToken = Secret("AT_STORED"),
        tokenType = TokenType.Bearer,
        refreshToken = Secret("RT_STORED"),
        expiresAt = fake.now().plusSeconds(600),
        jwtToken = Secret("JWT_STORED"),
        cookies = CookieJar.empty
      )
      assertEquals(store.replace(None, stored), Right(()))
      val result = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assertEquals(result, Right("AT_STORED"))
      assertEquals(mock.requests, Nil)
