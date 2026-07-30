package lmbot.backend.luxmed

import java.util.UUID

import gears.async.Async
import lmbot.backend.config.AppVersion
import lmbot.backend.luxmed.model.LuxmedEndpoint
import lmbot.backend.luxmed.support.{
  GearsTest,
  RealHttpLuxmedServer,
  StubLuxmedBackend
}
import sttp.model.Uri

class ErrorClassificationTest extends munit.FunSuite with GearsTest:

  private val testConfig = LuxmedConfig(
    oldApi = Uri.unsafeParse("http://localhost:1/PatientPortalMobileAPI/api"),
    newApi = Uri.unsafeParse("http://localhost:1/PatientPortal"),
    appVersion = AppVersion.unsafeFromString("4.44.0"),
    deviceUuid = UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")
  )

  private val testPermit = new RequestPermit:
    def beforeRequest()(using Async): Unit = ()

  private def withStubTransport[T](
      body: (LuxmedTransport, StubLuxmedBackend) => T
  ): T =
    val stub = StubLuxmedBackend()
    val transport = LuxmedTransport.withBackend(testConfig, stub.backend)
    body(transport, stub)

  // -- Real-HTTP tests (proving JDK backend behavior) --

  private def withRealTransport[T](
      body: (LuxmedTransport, RealHttpLuxmedServer) => T
  ): T =
    val mock = RealHttpLuxmedServer()
    try
      val config = LuxmedConfig(
        oldApi = Uri.unsafeParse(s"${mock.baseUri}/PatientPortalMobileAPI/api"),
        newApi = Uri.unsafeParse(s"${mock.baseUri}/PatientPortal"),
        appVersion = AppVersion.unsafeFromString("4.44.0"),
        deviceUuid = UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")
      )
      val transport = LuxmedTransport.production(config)
      body(transport, mock)
    finally mock.close()

  test("transport does not follow a session-expiry redirect"):
    withRealTransport: (transport, mock) =>
      mock.enqueue(
        status = 302,
        headers = Map("Location" -> "/PatientPortal/LogOn"),
        body = ""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assertEquals(result, Left(LuxmedError.SessionExpired))
      assertEquals(mock.requests.size, 1)

  test("cookie values preserve equals signs"):
    withRealTransport: (transport, mock) =>
      mock.enqueue(
        status = 200,
        headers = Map("Set-Cookie" -> "token=abc==; Path=/"),
        body = "ok"
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assertEquals(
        result.toOption
          .flatMap(_.cookies.find(_._1 == "token"))
          .map { case (_, secret) => secret.value },
        Some("abc==")
      )

  test("connection failure is NetworkFailure"):
    val config = LuxmedConfig(
      oldApi = Uri.unsafeParse("http://localhost:1/PatientPortalMobileAPI/api"),
      newApi = Uri.unsafeParse("http://localhost:1/PatientPortal"),
      appVersion = AppVersion.unsafeFromString("4.44.0"),
      deviceUuid = UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")
    )
    val transport = LuxmedTransport.production(config)
    val result = runAsync:
      given RequestPermit = testPermit
      transport.oldApiGet(LuxmedEndpoint.Token)
    assert(result.left.exists {
      case LuxmedError.NetworkFailure(_) => true
      case _                             => false
    })

  test("302 to UniversalLink is session expired"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(
        status = 302,
        headers = List("Location" -> "/PatientPortal/UniversalLink"),
        body = ""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assertEquals(result, Left(LuxmedError.SessionExpired))

  test("redirect body naming LogOn is session expired"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(status = 302, body = "/PatientPortal/LogOn")
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assertEquals(result, Left(LuxmedError.SessionExpired))

  test("429 is RateLimited"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(status = 429, body = """{"message":"slow down"}""")
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assertEquals(result, Left(LuxmedError.RateLimited))

  test("a 409 credential message is AuthFailed"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(
        status = 409,
        body = """{"error":{"code":1,"message":"invalid login or password"}}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assertEquals(result, Left(LuxmedError.AuthFailed))

  test("Polish credential message 409 is AuthFailed"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(
        status = 409,
        body =
          """{"error":{"code":1,"message":"nieprawidłowy login lub hasło"}}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assertEquals(result, Left(LuxmedError.AuthFailed))

  test("a 409 without credential message is ApiRejected"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(
        status = 409,
        body = """{"error":{"code":2,"message":"some other error"}}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assert(result.isLeft)
      assert(result.left.exists {
        case LuxmedError.ApiRejected(_) => true
        case _                          => false
      })

  test("list-shaped error envelope is ApiRejected"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(
        status = 400,
        body = """{"errors":[{"code":"invalid","message":"bad request"}]}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assert(result.left.exists {
        case LuxmedError.ApiRejected(_) => true
        case _                          => false
      })

  test("map-shaped error envelope is ApiRejected"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(
        status = 400,
        body = """{"errors":{"password":["Password is required"]}}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assert(result.left.exists {
        case LuxmedError.ApiRejected(_) => true
        case _                          => false
      })

  test("session has expired in body is SessionExpired"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(
        status = 200,
        body = """{"message":"Your session has expired"}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assertEquals(result, Left(LuxmedError.SessionExpired))

  test("logged out due to inactivity is SessionExpired"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(
        status = 401,
        body =
          """{"error":{"code":1,"message":"You have been logged out due to inactivity."}}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assertEquals(result, Left(LuxmedError.SessionExpired))

  test("5xx is Transient"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(status = 503, body = "Service Unavailable")
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assertEquals(result, Left(LuxmedError.Transient(503)))

  test("old app version error is VersionRejected"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(
        status = 409,
        body =
          """{"ErrorCode":301,"Message":"Obecnie zainstalowana wersja aplikacji nie jest wspierana przez nowy system Portalu Pacjenta. Zaktualizuj aplikację do najnowszej wersji, aby móc z niej korzystać."}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assert(result.left.exists {
        case LuxmedError.VersionRejected(_) => true
        case _                              => false
      })

  test("challenge-shaped response is UnexpectedAuthResponse"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(
        status = 200,
        body =
          """{"challengeId":"challenge-1","method":"sms","message":"Additional verification required"}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assert(result.left.exists {
        case LuxmedError.UnexpectedAuthResponse(_) => true
        case _                                     => false
      })

  test("transport diagnostics redact response secrets"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(
        status = 200,
        headers = List(
          "Authorization-Token" -> "Bearer JWT_SECRET",
          "Set-Cookie" -> "session=COOKIE_SECRET"
        ),
        body =
          """{"access_token":"ACCESS_SECRET","refresh_token":"REFRESH_SECRET","password":"PASSWORD_SECRET","email":"person@example.com","phone":"501 234 567"}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      val rendered = result.toString
      List(
        "ACCESS_SECRET",
        "REFRESH_SECRET",
        "PASSWORD_SECRET",
        "JWT_SECRET",
        "COOKIE_SECRET",
        "person@example.com",
        "501 234 567"
      ).foreach(secret => assert(!rendered.contains(secret), s"leaked $secret"))

  test("error diagnostics redact bearer authorization values"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(
        status = 200,
        body = """{"challengeId":"x","authorization":"Bearer AUTH_SECRET"}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assert(!result.toString.contains("AUTH_SECRET"))

  test("error diagnostics redact JWT cookies and phone-like values"):
    withStubTransport: (transport, stub) =>
      stub.enqueue(
        status = 200,
        body =
          """{"challengeId":"challenge-1","jwt":"JWT_SECRET","cookie":"COOKIE_SECRET","phone":"501 234 567","email":"person@example.com"}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      val rendered = result.toString
      List(
        "JWT_SECRET",
        "COOKIE_SECRET",
        "501 234 567",
        "person@example.com"
      ).foreach(secret => assert(!rendered.contains(secret), s"leaked $secret"))

  test("redaction replaces secret characters rather than preserving prefixes"):
    assertEquals(
      LuxmedRedaction.summary("""{"jwt":"J","password":"P"}"""),
      """{"jwt":"***","password":"***"}"""
    )
