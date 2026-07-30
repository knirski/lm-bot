package lmbot.backend.luxmed

import java.util.UUID

import gears.async.Async
import lmbot.backend.config.AppVersion
import lmbot.backend.luxmed.model.LuxmedEndpoint
import lmbot.backend.luxmed.support.{
  GearsTest,
  MockResponse,
  RealHttpLuxmedServer
}
import sttp.model.Uri

/** Real-wire contract tests proving the JDK HTTP backend behavior that an sttp
  * stub cannot simulate.
  *
  * These tests use a real loopback [[RealHttpLuxmedServer]] and
  * [[LuxmedTransport.production]] to ensure the actual JDK HTTP client
  * serializes requests and interprets responses as the application expects.
  */
class WireContractTest extends munit.FunSuite with GearsTest:

  private val testPermit = new RequestPermit:
    def beforeRequest()(using Async): Unit = ()

  private def withRealServer[T](
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

  test("followRedirects(false) prevents a second request on 302"):
    withRealServer: (transport, mock) =>
      mock.enqueue(
        status = 302,
        headers = Map("Location" -> "/somewhere"),
        body = ""
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      // 302 with a generic Location (not /LogOn or /UniversalLink) is not
      // classified as SessionExpired, so it falls through (status 302 is not
      // a valid success for non-bootstrap endpoints).
      assert(result.isLeft)
      assertEquals(mock.requests.size, 1)

  test("preserves repeated raw Set-Cookie headers"):
    withRealServer: (transport, mock) =>
      mock.enqueue(
        MockResponse(
          status = 200,
          headers = Map(
            "Set-Cookie" -> List(
              "cookie1=value1; Path=/",
              "cookie2=value2; HttpOnly",
              "cookie3=value3; Domain=.example.com"
            )
          ),
          body = "ok"
        )
      )
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      val cookies = result.toOption.get.cookies
      assertEquals(cookies.size, 3)
      assertEquals(
        cookies.find(_._1 == "cookie1").map(_._2.value),
        Some("value1")
      )
      assertEquals(
        cookies.find(_._1 == "cookie2").map(_._2.value),
        Some("value2")
      )

  test("empty response body is accepted for release endpoint"):
    withRealServer: (transport, mock) =>
      mock.enqueue(status = 200, body = "")
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiGet(LuxmedEndpoint.Token)
      assert(result.isRight, s"expected success for empty body, got $result")
      assertEquals(result.toOption.get.body, "")

  test("POST form body is correctly serialized and received"):
    withRealServer: (transport, mock) =>
      mock.enqueue(status = 200, body = "ok")
      val result = runAsync:
        given RequestPermit = testPermit
        transport.oldApiPostForm(
          LuxmedEndpoint.Token,
          body = Map(
            "client_id" -> "Android",
            "grant_type" -> "password",
            "username" -> "user@test.com"
          )
        )
      assert(result.isRight, s"expected success, got $result")
      val recorded = mock.requests.last
      assert(recorded.body.contains("client_id=Android"))
      assert(recorded.body.contains("grant_type=password"))
      assert(recorded.body.contains("username=user%40test.com"))
      assert(
        recorded.headers.exists { (name, values) =>
          name.equalsIgnoreCase("Content-Type") &&
          values.exists(_.startsWith("application/x-www-form-urlencoded"))
        },
        s"Expected Content-Type with form encoding, got ${recorded.headers}"
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

  test("cookie values preserve equals signs after semicolon splitting"):
    withRealServer: (transport, mock) =>
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
