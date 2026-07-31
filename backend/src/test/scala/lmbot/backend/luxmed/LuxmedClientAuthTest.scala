package lmbot.backend.luxmed

import java.time.Duration
import java.util.UUID

import gears.async.{Async, Future}
import lmbot.backend.config.{AppVersion, SafeDiagnostic, Secret}
import lmbot.backend.luxmed.model.*
import lmbot.backend.luxmed.support.{
  FakeTime,
  GearsTest,
  LuxmedResponseScripts,
  StubLuxmedBackend
}
import sttp.client3.Request
import sttp.model.Uri

class LuxmedClientAuthTest extends munit.FunSuite with GearsTest:

  private val testUuid = UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")

  private val testConfig = LuxmedConfig(
    oldApi = Uri.unsafeParse("http://localhost:1/PatientPortalMobileAPI/api"),
    newApi = Uri.unsafeParse("http://localhost:1/PatientPortal"),
    appVersion = AppVersion.unsafeFromString("4.44.0"),
    deviceUuid = testUuid
  )

  private def requestPath(request: Request[?, ?]): String =
    "/" + request.uri.path.mkString("/")

  private def requestQuery(request: Request[?, ?]): Option[String] =
    val params = request.uri.paramsMap
    if params.isEmpty then None
    else
      Some(
        params.toList
          .flatMap { case (k, v) => s"$k=$v" :: Nil }
          .sorted
          .mkString("&")
      )

  private def requestHeader(
      request: Request[?, ?],
      name: String
  ): Option[String] =
    request.headers.find(_.name.equalsIgnoreCase(name)).map(_.value)

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
  )(body: (LuxmedClient, StubLuxmedBackend, FakeTime, SessionStore) => T): T =
    val stub = StubLuxmedBackend()
    val transport = LuxmedTransport.withBackend(testConfig, stub.backend)
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
    body(client, stub, fake, store)

  private def enqueueRealisticAuthFlow(
      stub: StubLuxmedBackend,
      accessToken: String = "AT1",
      refreshToken: String = "RT1",
      jwtToken: String = "JWT_TOKEN_1",
      sessionCookie: String = "ASP.NET_SessionId=sess1",
      expiresIn: Int = 600
  ): Unit =
    LuxmedResponseScripts
      .realisticAuthFlow(
        accessToken,
        refreshToken,
        jwtToken,
        sessionCookie,
        expiresIn
      )
      .foreach: response =>
        stub.enqueue(response.status, response.headers, response.body)

  private def enqueueRealisticBootstrapFlow(
      stub: StubLuxmedBackend,
      jwtToken: String,
      sessionCookie: String = "ASP.NET_SessionId=sess1"
  ): Unit =
    LuxmedResponseScripts
      .realisticBootstrapFlow(jwtToken, sessionCookie)
      .foreach: response =>
        stub.enqueue(response.status, response.headers, response.body)

  test("three-step auth flow succeeds with realistic mock responses"):
    withClient(): (client, stub, _, _) =>
      enqueueRealisticAuthFlow(stub)
      val result = runAsync:
        client.authenticate()
      assert(result.isRight, s"expected success, got $result")
      val session = result.toOption.get
      assertEquals(session.refreshToken.value, "RT1")
      assertEquals(session.jwtToken.value, "JWT_TOKEN_1")
      assertEquals(session.tokenType, TokenType.Bearer)
      val requests = stub.requests
      assertEquals(requests.map(_.method.method), List("POST", "GET", "GET"))
      assertEquals(
        requests.map(requestPath),
        List(
          "/PatientPortalMobileAPI/api/token",
          "/PatientPortal/Account/LogInToApp",
          "/PatientPortal/NewPortal/Page/Reservation"
        )
      )
      assertEquals(
        requestQuery(requests(1)),
        Some("app=search&client=3&lang=pl")
      )
      val body0 = stub.bodyString(requests(0))
      assert(body0.contains("client_id=Android"))
      assert(body0.contains("grant_type=password"))
      assert(body0.contains("username=user%40example.com"))
      assert(body0.contains("password=password123"))
      assert(
        requestHeader(requests(0), "Content-Type")
          .exists(_.startsWith("application/x-www-form-urlencoded"))
      )
      assertEquals(requestHeader(requests(1), "Authorization"), Some("AT1"))
      assertEquals(
        requestHeader(requests(1), "X-Requested-With"),
        Some("pl.luxmed.pp")
      )
      // ReservationPage request includes session cookie plus GlobalLang (added after LogInToApp)
      val cookieHeader = requestHeader(requests(2), "Cookie")
      assert(
        cookieHeader.exists(h =>
          h.contains("ASP.NET_SessionId=sess1") && h.contains("GlobalLang=pl")
        ),
        s"expected cookies with ASP.NET_SessionId and GlobalLang, got $cookieHeader"
      )

  test("authenticate stores session in the store"):
    withClient(): (client, stub, _, _) =>
      enqueueRealisticAuthFlow(
        stub,
        accessToken = "AT1",
        refreshToken = "RT1",
        jwtToken = "JWT_1"
      )
      runAsync:
        client.authenticate()
      val stored = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assertEquals(stored, Right("AT1"))

  test("authenticate replaces an existing stored session"):
    withClient(): (client, stub, fake, store) =>
      val oldSession = LuxmedSession(
        accessToken = Secret("AT_OLD"),
        tokenType = TokenType.Bearer,
        refreshToken = Secret("RT_OLD"),
        expiresAt = fake.now().plusSeconds(600),
        jwtToken = Secret("JWT_OLD"),
        cookies = CookieJar.empty
      )
      assertEquals(store.replace(None, oldSession), Right(()))
      enqueueRealisticAuthFlow(
        stub,
        accessToken = "AT_NEW",
        refreshToken = "RT_NEW",
        jwtToken = "JWT_NEW"
      )
      val result = runAsync:
        client.authenticate()
      assertEquals(result.map(_.refreshToken.value), Right("RT_NEW"))
      assertEquals(
        store.load().map(_.map(_.refreshToken.value)),
        Right(Some("RT_NEW"))
      )

  test("missing JWT in response is ProtocolViolation"):
    withClient(): (client, stub, _, _) =>
      // Token endpoint -- 200 with WAF cookies but ReservationPage has no Authorization-Token
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      stub.enqueue(
        status = 200,
        body = ""
      )
      stub.enqueue(status = 200, body = "")
      val result = runAsync:
        client.authenticate()
      assert(result.left.exists:
        case LuxmedError.ProtocolViolation(_) => true
        case _                                => false)

  test("refresh happens when session is expiring"):
    withClient(): (client, stub, _, _) =>
      // Auth with expires_in=30 so session is immediately expiring
      enqueueRealisticAuthFlow(
        stub,
        accessToken = "AT1",
        refreshToken = "RT1",
        jwtToken = "JWT_1",
        expiresIn = 30
      )
      // Refresh: token response + realistic bootstrap
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      enqueueRealisticBootstrapFlow(stub, jwtToken = "JWT_2")
      runAsync:
        client.authenticate()
      val result = runAsync:
        client.withSession: (_, session) =>
          Right(session.refreshToken.value)
      assertEquals(result, Right("RT2"))

  test("301 seconds remaining does not refresh"):
    withClient(): (client, stub, fake, _) =>
      enqueueRealisticAuthFlow(
        stub,
        accessToken = "AT1",
        refreshToken = "RT1",
        jwtToken = "JWT_1"
      )
      runAsync:
        client.authenticate()
      fake.advance(Duration.ofSeconds(299))
      val result = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assertEquals(result, Right("AT1"))
      assertEquals(
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=refresh_token")
        ),
        0
      )

  test("300 seconds remaining refreshes exactly once"):
    withClient(): (client, stub, fake, _) =>
      enqueueRealisticAuthFlow(
        stub,
        accessToken = "AT1",
        refreshToken = "RT1",
        jwtToken = "JWT_1"
      )
      runAsync:
        client.authenticate()
      fake.advance(Duration.ofSeconds(300))
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      enqueueRealisticBootstrapFlow(stub, jwtToken = "JWT_2")
      val result = runAsync:
        client.withSession: (_, session) =>
          Right(session.refreshToken.value)
      assertEquals(result, Right("RT2"))
      assertEquals(
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=refresh_token")
        ),
        1
      )

  test("concurrent session operations perform one refresh transaction"):
    withClient(): (client, stub, fake, _) =>
      enqueueRealisticAuthFlow(
        stub,
        accessToken = "AT1",
        refreshToken = "RT1",
        jwtToken = "JWT_1"
      )
      runAsync:
        client.authenticate()
      fake.advance(Duration.ofSeconds(301))
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      enqueueRealisticBootstrapFlow(stub, jwtToken = "JWT_2")
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
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=refresh_token")
        ),
        1
      )

  test("malformed OAuth success does not expose tokens in DecodeFailed"):
    withClient(): (client, stub, _, _) =>
      stub.enqueue(
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
    withClient(): (client, stub, _, _) =>
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      stub.enqueue(status = 200, body = "session has expired")
      stub.enqueue(status = 200, body = "")
      stub.enqueue(
        status = 200,
        headers = List("Authorization-Token" -> "Bearer JWT_1"),
        body = ""
      )

      val first = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)

      assertEquals(first, Left(LuxmedError.SessionExpired))
      assertEquals(
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=password")
        ),
        1
      )

      val second = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)

      assertEquals(second, Right("AT1"))
      assertEquals(
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=password")
        ),
        1
      )

  test("persistence failure retries the store without another HTTP request"):
    withClient(FailOnceStore()): (client, stub, _, _) =>
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      stub.enqueue(status = 200, body = "")
      stub.enqueue(
        status = 200,
        headers = List("Authorization-Token" -> "Bearer JWT_1"),
        body = ""
      )
      val first = runAsync:
        client.authenticate()
      assert(first.left.exists(_.isInstanceOf[LuxmedError.PersistenceFailed]))
      val requestCount = stub.requests.size
      val second = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assertEquals(second, Right("AT1"))
      assertEquals(stub.requests.size, requestCount)

  test(
    "bootstrap failure after refresh resumes without reusing the old refresh token"
  ):
    withClient(): (client, stub, fake, _) =>
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      stub.enqueue(status = 200, body = "")
      stub.enqueue(
        status = 200,
        headers = List("Authorization-Token" -> "Bearer JWT_1"),
        body = ""
      )
      runAsync:
        client.authenticate()
      fake.advance(Duration.ofSeconds(301))
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      stub.enqueue(status = 503, body = "temporary failure")
      val first = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assert(first.isLeft)
      stub.enqueue(status = 200, body = "")
      stub.enqueue(
        status = 200,
        headers = List("Authorization-Token" -> "Bearer JWT_2"),
        body = ""
      )
      val second = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assertEquals(second, Right("AT2"))
      assertEquals(
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=refresh_token")
        ),
        1
      )

  test(
    "bootstrap expiry after refresh returns without password fallback and retries bootstrap"
  ):
    withClient(): (client, stub, fake, _) =>
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      stub.enqueue(status = 200, body = "")
      stub.enqueue(
        status = 200,
        headers = List("Authorization-Token" -> "Bearer JWT_1"),
        body = ""
      )
      runAsync:
        client.authenticate()
      fake.advance(Duration.ofSeconds(301))
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      stub.enqueue(status = 200, body = "session has expired")
      stub.enqueue(status = 200, body = "")
      stub.enqueue(
        status = 200,
        headers = List("Authorization-Token" -> "Bearer JWT_2"),
        body = ""
      )

      val first = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)

      assertEquals(first, Left(LuxmedError.SessionExpired))
      assertEquals(
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=password")
        ),
        1
      )

      val second = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)

      assertEquals(second, Right("AT2"))
      assertEquals(
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=refresh_token")
        ),
        1
      )
      assertEquals(
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=password")
        ),
        1
      )

  test(
    "refresh rejection clears stale state and performs one password fallback"
  ):
    withClient(): (client, stub, fake, store) =>
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      stub.enqueue(status = 200, body = "")
      stub.enqueue(
        status = 200,
        headers = List("Authorization-Token" -> "Bearer JWT_1"),
        body = ""
      )
      runAsync:
        client.authenticate()
      fake.advance(Duration.ofSeconds(301))
      stub.enqueue(
        status = 401,
        body =
          """{"error":{"code":1,"message":"You have been logged out due to inactivity."}}"""
      )
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      stub.enqueue(status = 200, body = "")
      stub.enqueue(
        status = 200,
        headers = List("Authorization-Token" -> "Bearer JWT_2"),
        body = ""
      )
      val result = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assertEquals(result, Right("AT2"))
      assertEquals(
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=password")
        ),
        2
      )
      assertEquals(
        store.load().map(_.map(_.refreshToken.value)),
        Right(Some("RT2"))
      )

  test("invalid refresh grant performs one password fallback"):
    withClient(): (client, stub, fake, _) =>
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      stub.enqueue(status = 200, body = "")
      stub.enqueue(
        status = 200,
        headers = List("Authorization-Token" -> "Bearer JWT_1"),
        body = ""
      )
      runAsync:
        client.authenticate()
      fake.advance(Duration.ofSeconds(301))
      stub.enqueue(status = 401, body = """{"error":"invalid_grant"}""")
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      stub.enqueue(status = 200, body = "")
      stub.enqueue(
        status = 200,
        headers = List("Authorization-Token" -> "Bearer JWT_2"),
        body = ""
      )
      val result = runAsync:
        client.withSession: (_, session) =>
          Right(session.accessToken.value)
      assertEquals(result, Right("AT2"))
      assertEquals(
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=refresh_token")
        ),
        1
      )
      assertEquals(
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=password")
        ),
        2
      )

  test("a fresh client loads a non-expiring stored session"):
    withClient(): (client, stub, fake, store) =>
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
      assertEquals(stub.requests, Nil)

  // -- Retry-policy tests (Task 8) --

  test(
    "RateLimited passes through and does not trigger reauthentication"
  ):
    withClient(): (client, stub, _, _) =>
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      stub.enqueue(status = 200, body = "")
      stub.enqueue(
        status = 200,
        headers = List("Authorization-Token" -> "Bearer JWT_1"),
        body = ""
      )
      runAsync:
        client.authenticate()
      stub.enqueue(status = 429, body = """{"message":"slow down"}""")
      val result = runAsync:
        client.cities()
      assertEquals(result, Left(LuxmedError.RateLimited))
      // No additional password grant was triggered
      assertEquals(
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=password")
        ),
        1
      )

  test(
    "VersionRejected passes through without password fallback"
  ):
    withClient(): (client, stub, _, _) =>
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      stub.enqueue(status = 200, body = "")
      stub.enqueue(
        status = 200,
        headers = List("Authorization-Token" -> "Bearer JWT_1"),
        body = ""
      )
      runAsync:
        client.authenticate()
      stub.enqueue(
        status = 409,
        body =
          """{"ErrorCode":301,"Message":"Obecnie zainstalowana wersja aplikacji nie jest wspierana przez nowy system Portalu Pacjenta. Zaktualizuj aplikację do najnowszej wersji, aby móc z niej korzystać."}"""
      )
      val result = runAsync:
        client.cities()
      assert(result.left.exists:
        case LuxmedError.VersionRejected(_) => true
        case _                              => false)
      // No additional password grant should occur
      assertEquals(
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=password")
        ),
        1
      )

  test(
    "SessionExpired on operation triggers refresh and retry exactly once"
  ):
    withClient(): (client, stub, _, _) =>
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT1","expires_in":600,"refresh_token":"RT1","token_type":"bearer"}"""
      )
      stub.enqueue(status = 200, body = "")
      stub.enqueue(
        status = 200,
        headers = List("Authorization-Token" -> "Bearer JWT_1"),
        body = ""
      )
      runAsync:
        client.authenticate()
      // First cities() call gets SessionExpired
      stub.enqueue(status = 200, body = "session has expired")
      // Reauthentication via password grant + bootstrap
      stub.enqueue(
        status = 200,
        body =
          """{"access_token":"AT2","expires_in":600,"refresh_token":"RT2","token_type":"bearer"}"""
      )
      stub.enqueue(status = 200, body = "")
      stub.enqueue(
        status = 200,
        headers = List("Authorization-Token" -> "Bearer JWT_2"),
        body = ""
      )
      // Retried cities() succeeds
      stub.enqueue(status = 200, body = """[{"id":70,"name":"Białystok"}]""")
      val result = runAsync:
        client.cities()
      assert(result.isRight, s"expected success after retry, got $result")
      // Two password grants: one for initial auth, one for reauth after expiry
      assertEquals(
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=password")
        ),
        2
      )
      // No refresh-token grant: session-expired during op triggered full reauth
      assertEquals(
        stub.requests.count(r =>
          stub.bodyString(r).contains("grant_type=refresh_token")
        ),
        0
      )
