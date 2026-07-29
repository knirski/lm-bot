package lmbot.backend.luxmed

import java.util.UUID

import gears.async.Async
import lmbot.backend.config.AppVersion
import lmbot.backend.luxmed.model.LuxmedEndpoint
import lmbot.backend.luxmed.support.{GearsTest, StubLuxmedBackend}
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.model.Uri

class StubLuxmedBackendTest extends munit.FunSuite with GearsTest:

  private val testConfig = LuxmedConfig(
    oldApi = Uri.unsafeParse("http://localhost:1/api"),
    newApi = Uri.unsafeParse("http://localhost:2/api"),
    appVersion = AppVersion.unsafeFromString("4.44.0"),
    deviceUuid = UUID.randomUUID()
  )

  private val testPermit = new RequestPermit:
    def beforeRequest()(using Async): Unit = ()

  test("production factory builds a transport"):
    val transport = LuxmedTransport.production(testConfig)
    assert(transport != null, "production factory should return a transport")

  test("withBackend accepts SttpBackendStub"):
    val backend = SttpBackendStub.synchronous
    val transport = LuxmedTransport.withBackend(testConfig, backend)
    assert(transport != null, "withBackend should return a transport")

  test("injected stub backend is used for requests"):
    val backend = SttpBackendStub.synchronous.whenAnyRequest
      .thenRespond("""{"result":"ok"}""")
    val transport = LuxmedTransport.withBackend(testConfig, backend)
    val result = runAsync:
      given RequestPermit = testPermit
      transport.oldApiGet(LuxmedEndpoint.Token)
    assert(result.isRight, s"expected Right, got $result")
    assertEquals(result.toOption.get.body, """{"result":"ok"}""")

  // --- StubLuxmedBackend FIFO and capture tests ---

  test("StubLuxmedBackend enqueues and dequeues responses in FIFO order"):
    val stub = StubLuxmedBackend()
    stub.enqueue(status = 200, body = "first")
    stub.enqueue(status = 200, body = "second")
    val r1 = basicRequest
      .get(uri"http://test.com/a")
      .response(asStringAlways)
      .send(stub.backend)
    val r2 = basicRequest
      .get(uri"http://test.com/b")
      .response(asStringAlways)
      .send(stub.backend)
    assertEquals(r1.body, "first")
    assertEquals(r2.body, "second")
    assertEquals(stub.requests.size, 2)

  test("StubLuxmedBackend fails deterministically on empty queue"):
    val stub = StubLuxmedBackend()
    intercept[RuntimeException]:
      basicRequest
        .get(uri"http://test.com/a")
        .response(asStringAlways)
        .send(stub.backend)

  test("StubLuxmedBackend captures request details"):
    val stub = StubLuxmedBackend()
    stub.enqueue(status = 200, body = "ok")
    basicRequest
      .get(uri"http://test.com/api/path?q=1")
      .response(asStringAlways)
      .send(stub.backend)
    assertEquals(
      stub.requests.head.uri.toString(),
      "http://test.com/api/path?q=1"
    )

  test("StubLuxmedBackend enqueue with headers preserves them"):
    val stub = StubLuxmedBackend()
    stub.enqueue(
      status = 200,
      headers = List(
        "Set-Cookie" -> "sess=abc",
        "Content-Type" -> "text/plain"
      ),
      body = "got cookies"
    )
    val resp = basicRequest
      .get(uri"http://test.com/")
      .response(asStringAlways)
      .send(stub.backend)
    assertEquals(resp.body, "got cookies")
    val cookies = resp.headers.filter(_.name.equalsIgnoreCase("Set-Cookie"))
    assert(cookies.nonEmpty, "expected Set-Cookie header")
