package lmbot.backend

import java.time.OffsetDateTime

import scala.concurrent.duration.*

import lmbot.backend.auth.{AuthService, Passwords}
import lmbot.backend.db.{SessionRepo, UserRepo}
import lmbot.backend.http.{AuthRoutes, HealthRoutes, Server, SettingsRoutes}
import lmbot.backend.notify.TelegramLinkService
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.{Role, UserId}
import sttp.client3.*
import sttp.model.{StatusCode, Uri}

/** Drives the real settings endpoints over real HTTP against real Postgres. */
class SettingsHttpApiTest extends PostgresSuite:

  private val ttl = 7.days
  private val http = HttpClientSyncBackend()

  private def login(baseUri: Uri, username: String): String =
    UserRepo(xa).insert(username, username, Passwords.hash("s3cret"), Role.User)
    val response = basicRequest
      .post(uri"$baseUri/api/auth/login")
      .body(s"""{"username":"$username","password":"s3cret"}""")
      .contentType("application/json")
      .send(http)
    response
      .headers("Set-Cookie")
      .flatMap(_.split(";").headOption)
      .collectFirst {
        case kv if kv.startsWith("lmbot_session=") =>
          kv.drop("lmbot_session=".length)
      }
      .getOrElse(fail("no session cookie in login response"))

  private def withServer[A](
      botUsername: Option[String],
      username: String
  )(body: (Uri, String) => A): A =
    val auth = AuthService(
      UserRepo(xa),
      SessionRepo(xa),
      ttl,
      () => OffsetDateTime.now()
    )
    val telegram = TelegramLinkService(
      UserRepo(xa),
      botUsername,
      () => OffsetDateTime.now()
    )
    val server = Server.start(
      "127.0.0.1",
      0,
      HealthRoutes.endpoints ++ AuthRoutes(
        auth,
        cookieSecure = false,
        sessionTtl = ttl
      ).endpoints ++ SettingsRoutes(auth, telegram).endpoints
    )
    val baseUri = uri"http://127.0.0.1:${server.getAddress.getPort}"
    try body(baseUri, login(baseUri, username))
    finally server.stop(0)

  test("status reports Telegram as unavailable when no bot is configured"):
    withServer(None, "settings-unavailable"): (baseUri, token) =>
      val r = basicRequest
        .get(uri"$baseUri/api/settings/telegram")
        .cookie("lmbot_session", token)
        .send(http)

      assertEquals(r.code, StatusCode.Ok)
      assertEquals(
        r.body,
        Right("""{"available":false,"linked":false}""")
      )

  test("link-code conflicts when no bot is configured"):
    withServer(None, "settings-conflict"): (baseUri, token) =>
      val r = basicRequest
        .post(uri"$baseUri/api/settings/telegram/link-code")
        .cookie("lmbot_session", token)
        .send(http)

      assertEquals(r.code, StatusCode.Conflict)
      val body = r.body.fold(identity, identity)
      assert(
        body.contains("not configured"),
        s"unexpected body: $body"
      )

  test("a configured bot returns a code, a deep link, and status"):
    withServer(Some("lm_bot"), "settings-linked"): (baseUri, token) =>
      val status = basicRequest
        .get(uri"$baseUri/api/settings/telegram")
        .cookie("lmbot_session", token)
        .send(http)
      assertEquals(
        status.body,
        Right(
          """{"available":true,"linked":false,"botUsername":"lm_bot"}"""
        )
      )

      val link = basicRequest
        .post(uri"$baseUri/api/settings/telegram/link-code")
        .cookie("lmbot_session", token)
        .send(http)
      assertEquals(link.code, StatusCode.Ok)
      assert(
        link.body.toOption.exists(_.contains("https://t.me/lm_bot?start=")),
        s"deep link missing: ${link.body}"
      )

  test("unlink clears a linked chat"):
    withServer(Some("lm_bot"), "settings-unlink"): (baseUri, token) =>
      val userId =
        UserId(UserRepo(xa).findByUsername("settings-unlink").get.id)
      UserRepo(xa).setTelegramChatId(userId, 4242L)

      val before = basicRequest
        .get(uri"$baseUri/api/settings/telegram")
        .cookie("lmbot_session", token)
        .send(http)
      assert(
        before.body.toOption.exists(_.contains("\"linked\":true")),
        s"expected linked=true: ${before.body}"
      )

      val unlink = basicRequest
        .delete(uri"$baseUri/api/settings/telegram")
        .cookie("lmbot_session", token)
        .send(http)
      assertEquals(unlink.code, StatusCode.Ok)

      val after = basicRequest
        .get(uri"$baseUri/api/settings/telegram")
        .cookie("lmbot_session", token)
        .send(http)
      assert(
        after.body.toOption.exists(_.contains("\"linked\":false")),
        s"expected linked=false: ${after.body}"
      )

  test("settings require authentication"):
    withServer(Some("lm_bot"), "settings-anonymous"): (baseUri, _) =>
      val r = basicRequest
        .get(uri"$baseUri/api/settings/telegram")
        .send(http)

      assertEquals(r.code, StatusCode.Unauthorized)
