package lmbot.backend.luxmed

import lmbot.backend.config.Secret
import lmbot.backend.luxmed.support.{FakeTime, GearsTest, MockLuxmedServer}
import lmbot.backend.luxmed.model.*
import sttp.model.Uri
import java.util.UUID
import java.time.Duration

class LuxmedClientAuthTest extends munit.FunSuite with GearsTest:

  private val testUuid = UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")

  private final class FailOnceStore extends SessionStore:
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
        appVersion = "4.44.0",
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
      mock.enqueue(status = 401, body = "session has expired")
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

  test("a fresh client loads a non-expiring stored session"):
    withClient(): (client, mock, fake, store) =>
      val stored = LuxmedSession(
        accessToken = Secret("AT_STORED"),
        tokenType = "bearer",
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
