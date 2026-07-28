package lmbot.backend.luxmed

import lmbot.backend.luxmed.support.{GearsTest, MockLuxmedServer}
import gears.async.Async
import sttp.model.Uri
import java.util.UUID

class ErrorClassificationTest extends munit.FunSuite with GearsTest:

  private def withTransport[T](
      body: (LuxmedTransport, MockLuxmedServer) => T
  ): T =
    val mock = MockLuxmedServer()
    try
      val config = LuxmedConfig(
        oldApi = Uri.unsafeParse(s"${mock.baseUri}/PatientPortalMobileAPI/api"),
        newApi = Uri.unsafeParse(s"${mock.baseUri}/PatientPortal"),
        appVersion = "4.44.0",
        deviceUuid = UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")
      )
      val transport = LuxmedTransport(config)
      body(transport, mock)
    finally mock.close()

  private val testPermit = new RequestPermit:
    def beforeRequest()(using Async): Unit = ()

  test("transport does not follow a session-expiry redirect"):
    withTransport: (transport, mock) =>
      mock.enqueue(
        status = 302,
        headers = Map("Location" -> "/PatientPortal/LogOn"),
        body = ""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet("/redirect")
      assertEquals(result, Left(LuxmedError.SessionExpired))
      assertEquals(mock.requests.size, 1)

  test("302 to UniversalLink is session expired"):
    withTransport: (transport, mock) =>
      mock.enqueue(
        status = 302,
        headers = Map("Location" -> "/PatientPortal/UniversalLink"),
        body = ""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet("/redirect")
      assertEquals(result, Left(LuxmedError.SessionExpired))

  test("429 is RateLimited"):
    withTransport: (transport, mock) =>
      mock.enqueue(status = 429, body = """{"message":"slow down"}""")
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet("/limited")
      assertEquals(result, Left(LuxmedError.RateLimited))

  test("a 409 credential message is AuthFailed"):
    withTransport: (transport, mock) =>
      mock.enqueue(
        status = 409,
        body = """{"error":{"code":1,"message":"invalid login or password"}}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet("/token")
      assertEquals(result, Left(LuxmedError.AuthFailed))

  test("Polish credential message 409 is AuthFailed"):
    withTransport: (transport, mock) =>
      mock.enqueue(
        status = 409,
        body =
          """{"error":{"code":1,"message":"nieprawidłowy login lub hasło"}}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet("/token")
      assertEquals(result, Left(LuxmedError.AuthFailed))

  test("a 409 without credential message is ApiRejected"):
    withTransport: (transport, mock) =>
      mock.enqueue(
        status = 409,
        body = """{"error":{"code":2,"message":"some other error"}}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet("/token")
      assert(result.isLeft)
      assert(result.left.exists {
        case LuxmedError.ApiRejected(_) => true
        case _                          => false
      })

  test("session has expired in body is SessionExpired"):
    withTransport: (transport, mock) =>
      mock.enqueue(
        status = 200,
        body = """{"message":"Your session has expired"}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet("/check")
      assertEquals(result, Left(LuxmedError.SessionExpired))

  test("5xx is Transient"):
    withTransport: (transport, mock) =>
      mock.enqueue(status = 503, body = "Service Unavailable")
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet("/down")
      assertEquals(result, Left(LuxmedError.Transient(503)))

  test("old app version error is VersionRejected"):
    withTransport: (transport, mock) =>
      mock.enqueue(
        status = 409,
        body =
          """{"ErrorCode":301,"Message":"Obecnie zainstalowana wersja aplikacji nie jest wspierana przez nowy system Portalu Pacjenta. Zaktualizuj aplikację do najnowszej wersji, aby móc z niej korzystać."}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet("/token")
      assert(result.left.exists {
        case LuxmedError.VersionRejected(_) => true
        case _                              => false
      })

  test("challenge-shaped response is UnexpectedAuthResponse"):
    withTransport: (transport, mock) =>
      mock.enqueue(
        status = 200,
        body =
          """{"challengeId":"challenge-1","method":"sms","message":"Additional verification required"}"""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet("/login")
      assert(result.left.exists {
        case LuxmedError.UnexpectedAuthResponse(_) => true
        case _                                     => false
      })

  test("connection failure is NetworkFailure"):
    val config = LuxmedConfig(
      oldApi = Uri.unsafeParse("http://localhost:1/PatientPortalMobileAPI/api"),
      newApi = Uri.unsafeParse("http://localhost:1/PatientPortal"),
      appVersion = "4.44.0",
      deviceUuid = UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")
    )
    val transport = LuxmedTransport(config)
    val result = runAsync:
      given RequestPermit = testPermit
      transport.oldApiGet("/token")
    assert(result.left.exists {
      case LuxmedError.NetworkFailure(_) => true
      case _                             => false
    })
