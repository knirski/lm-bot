package lmbot.shared

import lmbot.shared.api.ApiError
import lmbot.shared.domain.Role

class ApiErrorTest extends munit.FunSuite:

  test("each error carries its HTTP status"):
    assertEquals(ApiError.Unauthorized.status, 401)
    assertEquals(ApiError.Forbidden.status, 403)
    assertEquals(ApiError.NotFound.status, 404)
    assertEquals(ApiError.Conflict("dup").status, 409)
    assertEquals(ApiError.Validation("bad").status, 422)

  test("detail-carrying errors expose the detail as the message"):
    assertEquals(ApiError.Conflict("username taken").message, "username taken")
    assertEquals(ApiError.Validation("too short").message, "too short")

  test("fromWire round-trips every case"):
    val all = List(
      ApiError.Unauthorized,
      ApiError.Forbidden,
      ApiError.NotFound,
      ApiError.Conflict("dup"),
      ApiError.Validation("bad")
    )
    all.foreach: e =>
      assertEquals(ApiError.fromWire(e.status, e.code, e.message), e)

  test("fromWire degrades unknown codes rather than throwing"):
    val unknown = ApiError.fromWire(418, "teapot", "short and stout")
    assertEquals(unknown.status, 500)
    assert(unknown.message.contains("teapot"))

  test("Role maps to and from its wire string"):
    assertEquals(Role.asString(Role.Admin), "admin")
    assertEquals(Role.asString(Role.User), "user")
    assertEquals(Role.fromString("admin"), Some(Role.Admin))
    assertEquals(Role.fromString("user"), Some(Role.User))
    assertEquals(Role.fromString("root"), None)
