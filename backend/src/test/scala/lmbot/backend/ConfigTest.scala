package lmbot.backend

import lmbot.backend.config.{AppVersion, Config, Port, Secret}

class ConfigTest extends munit.FunSuite:

  private val minimal = Map(
    "DATABASE_URL" -> "jdbc:postgresql://localhost:5432/lmbot",
    "DATABASE_USER" -> "lmbot",
    "DATABASE_PASSWORD" -> "secret",
    "LMBOT_MASTER_KEY" -> "zipI+cHXewVqZsFi8jDDrAglsYK9B3fXZMswhyxr2hk="
  )

  test("a minimal environment yields a config with sensible defaults"):
    Config.fromEnv(minimal) match
      case Right(c) =>
        assertEquals(c.dbUrl, "jdbc:postgresql://localhost:5432/lmbot")
        assertEquals(c.httpHost, "0.0.0.0")
        assertEquals(c.httpPort.value, 8080)
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
        assert(errors.exists(_.contains("LMBOT_MASTER_KEY")))

  test("port and secure-cookie flag are overridable"):
    val env = minimal ++ Map(
      "HTTP_PORT" -> "9000",
      "COOKIE_SECURE" -> "false",
      "HTTP_HOST" -> "127.0.0.1"
    )
    Config.fromEnv(env) match
      case Right(c) =>
        assertEquals(c.httpPort.value, 9000)
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

  test("Luxmed app version defaults to the measured refresh-compatible floor"):
    val Right(config) = Config.fromEnv(minimal): @unchecked
    assertEquals(config.luxmedAppVersion.value, "4.44.0")

  test("Luxmed app version is configurable without changing the client"):
    val Right(config) =
      Config.fromEnv(
        minimal.updated("LUXMED_APP_VERSION", "4.45.1")
      ): @unchecked
    assertEquals(config.luxmedAppVersion.value, "4.45.1")

  test("an empty Luxmed app version is rejected"):
    val result = Config.fromEnv(minimal.updated("LUXMED_APP_VERSION", ""))
    assert(
      result.left.exists(_.contains("LUXMED_APP_VERSION must not be empty"))
    )

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

  test("port outside valid range is rejected"):
    val result = Config.fromEnv(minimal + ("HTTP_PORT" -> "0"))
    assert(result.left.exists(_.exists(_.contains("Port"))))

  test("app version below minimum floor is rejected"):
    val result = Config.fromEnv(
      minimal.updated("LUXMED_APP_VERSION", "4.43.0")
    )
    assert(result.left.exists(_.exists(_.contains("AppVersion"))))

  test("non-parseable app version is rejected"):
    val result = Config.fromEnv(
      minimal.updated("LUXMED_APP_VERSION", "not-a-version")
    )
    assert(result.left.exists(_.exists(_.contains("AppVersion"))))

  test("master key must decode to exactly 32 bytes"):
    val good = Config.fromEnv(minimal)
    assert(good.isRight, s"expected success, got $good")

    val tooShort = Config.fromEnv(
      minimal.updated("LMBOT_MASTER_KEY", "c2hvcnQ=")
    )
    assert(tooShort.isLeft, "too-short key must be rejected")

    val invalidB64 = Config.fromEnv(
      minimal.updated("LMBOT_MASTER_KEY", "!!!not-valid-base64!!!")
    )
    assert(invalidB64.isLeft, "invalid base64 must be rejected")
