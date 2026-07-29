package lmbot.backend

import scala.compiletime.uninitialized

import com.sun.net.httpserver.HttpServer
import lmbot.backend.http.{Server, StaticRoutes}
import sttp.client3.*
import sttp.model.StatusCode
import sttp.model.Uri

class StaticRoutesTest extends munit.FunSuite:

  private var server: HttpServer = uninitialized
  private var baseUri: Uri = uninitialized
  private val http = HttpClientSyncBackend()

  override def beforeAll(): Unit =
    server = Server.start("127.0.0.1", 0, StaticRoutes.endpoints)
    baseUri = uri"http://127.0.0.1:${server.getAddress.getPort}"

  override def afterAll(): Unit =
    if server != null then server.stop(0)

  test("the index page is served at the root"):
    val r = basicRequest.get(uri"$baseUri/").send(http)
    assertEquals(r.code, StatusCode.Ok)
    assert(
      r.body.exists(_.contains("""<div id="app">""")),
      s"unexpected body: ${r.body}"
    )

  test("a client-side route falls back to the index page so deep links work"):
    val r = basicRequest.get(uri"$baseUri/monitors/42").send(http)
    assertEquals(r.code, StatusCode.Ok)
    assert(r.body.exists(_.contains("""<div id="app">""")))

  test(
    "a path that looks like a file 404s instead of returning the index page"
  ):
    val r = basicRequest.get(uri"$baseUri/nope.js").send(http)
    assertEquals(r.code, StatusCode.NotFound)

  test("a missing asset under the assets prefix is a 404"):
    val r = basicRequest.get(uri"$baseUri/assets/missing.js").send(http)
    assertEquals(r.code, StatusCode.NotFound)
