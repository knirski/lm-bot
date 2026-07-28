package lmbot.backend

import lmbot.backend.config.{Config, Secret}

class ConfigTest extends munit.FunSuite:

  private val minimal = Map(
    "DATABASE_URL" -> "jdbc:postgresql://localhost:5432/lmbot",
    "DATABASE_USER" -> "lmbot",
    "DATABASE_PASSWORD" -> "secret"
  )

  test("a minimal environment yields a config with sensible defaults"):
    Config.fromEnv(minimal) match
      case Right(c) =>
        assertEquals(c.dbUrl, "jdbc:postgresql://localhost:5432/lmbot")
        assertEquals(c.httpHost, "0.0.0.0")
        assertEquals(c.httpPort, 8080)
        assertEquals(c.cookieSecure, true)
        assertEquals(c.sessionTtl.toDays, 7L)
        assertEquals(c.adminUsername, None)
      case Left(errs) => fail(s"expected success, got $errs")

  test("missing required variables are all reported at once"):
    Config.fromEnv(Map.empty) match
      case Right(c)     => fail(s"expected failure, got $c")
      case Left(errors) =>
        assert(errors.exists(_.contains("DATABASE_URL")))
        assert(errors.exists(_.contains("DATABASE_USER")))
        assert(errors.exists(_.contains("DATABASE_PASSWORD")))

  test("port and secure-cookie flag are overridable"):
    val env = minimal ++ Map(
      "HTTP_PORT" -> "9000",
      "COOKIE_SECURE" -> "false",
      "HTTP_HOST" -> "127.0.0.1"
    )
    Config.fromEnv(env) match
      case Right(c) =>
        assertEquals(c.httpPort, 9000)
        assertEquals(c.cookieSecure, false)
        assertEquals(c.httpHost, "127.0.0.1")
      case Left(errs) => fail(s"expected success, got $errs")

  test("a non-numeric port is rejected"):
    Config.fromEnv(minimal + ("HTTP_PORT" -> "eighty")) match
      case Right(c)     => fail(s"expected failure, got $c")
      case Left(errors) => assert(errors.exists(_.contains("HTTP_PORT")))

  test("admin bootstrap credentials are picked up when both are present"):
    val env =
      minimal ++ Map("ADMIN_USERNAME" -> "root", "ADMIN_PASSWORD" -> "hunter2")
    Config.fromEnv(env) match
      case Right(c) =>
        assertEquals(c.adminUsername, Some("root"))
        assertEquals(c.adminPassword, Some(Secret("hunter2")))
      case Left(errs) => fail(s"expected success, got $errs")

  test("secrets are wrapped so their value is reachable but not rendered"):
    val Right(c) = Config.fromEnv(minimal): @unchecked
    assertEquals(c.dbPassword.value, "secret")
    assertEquals(c.dbPassword.toString, "***")

  test("config never renders secrets in toString"):
    val Right(c) =
      Config.fromEnv(minimal ++ Map("ADMIN_PASSWORD" -> "hunter2")): @unchecked
    val rendered = c.toString
    assert(!rendered.contains("secret"), s"db password leaked: $rendered")
    assert(!rendered.contains("hunter2"), s"admin password leaked: $rendered")
