package lmbot.shared

import com.github.plokhotnyuk.jsoniter_scala.core.*
import lmbot.shared.api.*
import lmbot.shared.api.Codecs.given
import lmbot.shared.domain.{Role, UserView}

class CodecRoundTripTest extends munit.FunSuite:

  test("LoginRequest round-trips"):
    val original = LoginRequest("krzysiek", "correct horse battery staple")
    val json     = writeToString(original)
    assertEquals(readFromString[LoginRequest](json), original)

  test("UserView round-trips for both roles"):
    List(Role.Admin, Role.User).foreach: role =>
      val original = UserView(7L, "mom", "Mom", role, telegramLinked = true)
      assertEquals(readFromString[UserView](writeToString(original)), original)

  test("ErrorBody round-trips"):
    val original = ErrorBody("conflict", "username taken")
    assertEquals(readFromString[ErrorBody](writeToString(original)), original)

  test("a login request does not serialise its password into the log-friendly toString"):
    // The wire format must carry the password; the *rendering* must not.
    val req = LoginRequest("krzysiek", "s3cret")
    assert(!req.toString.contains("s3cret"), s"password leaked in toString: ${req.toString}")

  test("endpoints are described with the expected methods and paths"):
    assertEquals(AuthEndpoints.login.showPathTemplate(), "/api/auth/login")
    assertEquals(AuthEndpoints.me.showPathTemplate(), "/api/auth/me")
    assertEquals(AuthEndpoints.logout.showPathTemplate(), "/api/auth/logout")
    assertEquals(AuthEndpoints.sessionCookieName, "lmbot_session")
