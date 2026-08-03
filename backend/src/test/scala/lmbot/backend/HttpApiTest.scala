package lmbot.backend

import java.time.OffsetDateTime

import scala.compiletime.uninitialized
import scala.concurrent.duration.*

import com.augustnagro.magnum.{sql, transact}
import com.sun.net.httpserver.HttpServer
import lmbot.backend.auth.{AuthService, Passwords}
import lmbot.backend.db.{SessionRepo, UserRepo}
import lmbot.backend.http.{AuthRoutes, HealthRoutes, Server}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.Role
import sttp.client3.*
import sttp.model.{StatusCode, Uri}

/** Drives the real server over real HTTP against real Postgres. */
class HttpApiTest extends PostgresSuite:

  private val ttl = 7.days
  private var server: HttpServer = uninitialized
  private var baseUri: Uri = uninitialized
  private val http = HttpClientSyncBackend()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    val auth = AuthService(
      UserRepo(xa),
      SessionRepo(xa),
      ttl,
      () => OffsetDateTime.now()
    )
    val routes = AuthRoutes(auth, cookieSecure = false, sessionTtl = ttl)
    // Port 0 lets the OS choose, so tests never collide.
    server =
      Server.start("127.0.0.1", 0, HealthRoutes.endpoints ++ routes.endpoints)
    baseUri = uri"http://127.0.0.1:${server.getAddress.getPort}"

  override def afterEach(context: AfterEach): Unit =
    if server != null then server.stop(0)

  private def aUser(
      username: String = "krzysiek",
      password: String = "s3cret"
  ): Long =
    UserRepo(xa)
      .insert(username, "Krzysiek", Passwords.hash(password), Role.User)
      .id

  private def login(username: String, password: String) =
    basicRequest
      .post(uri"$baseUri/api/auth/login")
      .body(s"""{"username":"$username","password":"$password"}""")
      .contentType("application/json")
      .send(http)

  test("health responds ok without authentication"):
    val r = basicRequest.get(uri"$baseUri/health").send(http)
    assertEquals(r.code, StatusCode.Ok)
    assertEquals(r.body, Right("ok"))

  test("login succeeds and sets an HttpOnly SameSite=Lax session cookie"):
    aUser()
    val r = login("krzysiek", "s3cret")

    assertEquals(r.code, StatusCode.Ok)
    assert(r.body.exists(_.contains("krzysiek")))

    val setCookie = r.headers("Set-Cookie").mkString("; ")
    assert(
      setCookie.contains("lmbot_session="),
      s"no session cookie: $setCookie"
    )
    assert(
      setCookie.toLowerCase.contains("httponly"),
      s"not HttpOnly: $setCookie"
    )
    assert(setCookie.contains("SameSite=Lax"), s"not SameSite=Lax: $setCookie")

  test("login never echoes the password back"):
    aUser()
    val r = login("krzysiek", "s3cret")
    assert(!r.body.exists(_.contains("s3cret")))

  test("login with a wrong password is 401"):
    aUser()
    assertEquals(login("krzysiek", "nope").code, StatusCode.Unauthorized)

  test("me is 401 without a cookie"):
    assertEquals(
      basicRequest.get(uri"$baseUri/api/auth/me").send(http).code,
      StatusCode.Unauthorized
    )

  test("me returns the current user when the session cookie is presented"):
    aUser()
    val token = sessionCookieValue(login("krzysiek", "s3cret"))

    val r = basicRequest
      .get(uri"$baseUri/api/auth/me")
      .cookie("lmbot_session", token)
      .send(http)

    assertEquals(r.code, StatusCode.Ok)
    assert(r.body.exists(_.contains("krzysiek")))

  test("me is 403 once the user is disabled, even with a valid cookie"):
    val id = aUser()
    val token = sessionCookieValue(login("krzysiek", "s3cret"))
    transact(xa):
      sql"update users set disabled = true where id = $id".update.run()

    val r = basicRequest
      .get(uri"$baseUri/api/auth/me")
      .cookie("lmbot_session", token)
      .send(http)
    assertEquals(r.code, StatusCode.Forbidden)

  test("logout clears the cookie and invalidates the session"):
    aUser()
    val token = sessionCookieValue(login("krzysiek", "s3cret"))

    val out = basicRequest
      .post(uri"$baseUri/api/auth/logout")
      .cookie("lmbot_session", token)
      .send(http)
    assertEquals(out.code, StatusCode.Ok)
    assert(out.headers("Set-Cookie").mkString.contains("Max-Age=0"))

    val after = basicRequest
      .get(uri"$baseUri/api/auth/me")
      .cookie("lmbot_session", token)
      .send(http)
    assertEquals(after.code, StatusCode.Unauthorized)

  test("errors come back as the shared ErrorBody shape"):
    aUser()
    val r = login("krzysiek", "nope")
    assert(r.body.isLeft)
    val body = r.body.swap.getOrElse("")
    assert(body.contains("\"code\""), s"unexpected error body: $body")
    assert(body.contains("unauthorized"), s"unexpected error body: $body")

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
