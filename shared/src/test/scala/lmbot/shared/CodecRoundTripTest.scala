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

  // Round-tripping alone cannot catch codec/schema drift: a discriminated object
  // round-trips perfectly while the Tapir Schema advertises a string. These two
  // tests pin the actual bytes, so the declared contract and the wire cannot
  // diverge unnoticed.
  test("Role serialises as a bare JSON string, matching its string-based Schema"):
    assertEquals(writeToString(Role.Admin), "\"Admin\"")
    assertEquals(writeToString(Role.User), "\"User\"")

  test("UserView carries role as a string, not a discriminated object"):
    val json = writeToString(UserView(1L, "admin", "admin", Role.Admin, telegramLinked = false))
    assert(json.contains("\"role\":\"Admin\""), s"unexpected role encoding: $json")
    assert(!json.contains("\"type\""), s"role leaked a discriminator: $json")

  test("a login request does not serialise its password into the log-friendly toString"):
    // The wire format must carry the password; the *rendering* must not.
    val req = LoginRequest("krzysiek", "s3cret")
    assert(!req.toString.contains("s3cret"), s"password leaked in toString: ${req.toString}")

  test("endpoints are described with the expected methods and paths"):
    assertEquals(AuthEndpoints.login.showPathTemplate(), "/api/auth/login")
    assertEquals(AuthEndpoints.me.showPathTemplate(), "/api/auth/me")
    assertEquals(AuthEndpoints.logout.showPathTemplate(), "/api/auth/logout")
    assertEquals(AuthEndpoints.sessionCookieName, "lmbot_session")
