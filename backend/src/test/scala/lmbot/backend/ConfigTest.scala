package lmbot.backend

import scala.concurrent.duration.*

import lmbot.backend.config.{AppVersion, Config, Port, Secret}

class ConfigTest extends munit.FunSuite:

  private val requiredOnly = Map(
    "DATABASE_URL" -> "jdbc:postgresql://localhost:5432/lmbot",
    "DATABASE_USER" -> "lmbot",
    "DATABASE_PASSWORD" -> "secret",
    "LMBOT_MASTER_KEY" -> "zipI+cHXewVqZsFi8jDDrAglsYK9B3fXZMswhyxr2hk="
  )

  private val minimal = requiredOnly

  test("production resource supplies non-secret defaults"):
    val result = Config.fromEnv(requiredOnly, "application.conf")
    assertEquals(result.map(_.httpHost), Right("0.0.0.0"))
    assertEquals(result.map(_.httpPort.value), Right(8080))
    assertEquals(result.map(_.cookieSecure), Right(true))

  test("development resource supplies local defaults"):
    val result = Config.fromEnv(Map.empty, "application-dev.conf")
    assertEquals(
      result.map(_.dbUrl),
      Right("jdbc:postgresql://localhost:15432/lmbot")
    )
    assertEquals(result.map(_.embeddedPg), Right(true))
    assertEquals(result.map(_.liveLuxmedApi), Right(false))

  test("environment values override the selected resource"):
    val result = Config.fromEnv(
      requiredOnly.updated("HTTP_PORT", "9000"),
      "application.conf"
    )
    assertEquals(result.map(_.httpPort.value), Right(9000))

  test("a minimal environment yields a config with sensible defaults"):
    Config.fromEnv(minimal) match
      case Right(c) =>
        assertEquals(c.dbUrl, "jdbc:postgresql://localhost:5432/lmbot")
        assertEquals(c.httpHost, "0.0.0.0")
        assertEquals(c.httpPort.value, 8080)
        assertEquals(c.cookieSecure, true)
        assertEquals(c.adminUsername, None)
        assertEquals(c.masterKey.bytes.length, 32)
      case Left(errs) => fail(s"expected success, got $errs")

  test("session TTL is a finite Scala duration"):
    val Right(config) =
      Config.fromEnv(requiredOnly, "application.conf"): @unchecked
    assertEquals(config.sessionTtl, 7.days)

  test("missing required variables are all reported at once"):
    Config.fromEnv(Map.empty) match
      case Right(c)     => fail(s"expected failure, got $c")
      case Left(errors) =>
        assert(errors.exists(_.contains("DATABASE_URL")))
        assert(errors.exists(_.contains("DATABASE_USER")))
        assert(errors.exists(_.contains("DATABASE_PASSWORD")))
        assertEquals(
          errors.count(_.contains("LMBOT_MASTER_KEY")),
          1,
          s"missing LMBOT_MASTER_KEY error: $errors"
        )

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

  test("live Luxmed API is disabled by default"):
    val Right(config) = Config.fromEnv(minimal): @unchecked
    assertEquals(config.liveLuxmedApi, false)

  test("embedded PostgreSQL is disabled by default"):
    val Right(config) = Config.fromEnv(minimal): @unchecked
    assertEquals(config.embeddedPg, false)

  test("embedded PostgreSQL can be enabled with true or 1"):
    for flag <- List("true", "1") do
      val Right(config) =
        Config.fromEnv(minimal.updated("EMBEDDED_PG", flag)): @unchecked
      assertEquals(config.embeddedPg, true)

  test("live Luxmed API can be enabled explicitly"):
    val Right(config) =
      Config.fromEnv(minimal.updated("LIVE_LUXMED_API", "true")): @unchecked
    assertEquals(config.liveLuxmedApi, true)

  test("live Luxmed API can be explicitly disabled"):
    val Right(config) =
      Config.fromEnv(minimal.updated("LIVE_LUXMED_API", "false")): @unchecked
    assertEquals(config.liveLuxmedApi, false)

  test("an invalid live Luxmed API flag is rejected"):
    val result = Config.fromEnv(minimal.updated("LIVE_LUXMED_API", "yes"))
    assert(result.left.exists(_.exists(_.contains("LIVE_LUXMED_API"))))

  test("an empty live Luxmed API flag is rejected"):
    val result = Config.fromEnv(minimal.updated("LIVE_LUXMED_API", ""))
    assert(result.left.exists(_.exists(_.contains("LIVE_LUXMED_API"))))

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

  test("wrong-typed secrets do not appear in configuration diagnostics"):
    val secretValue = "42"
    val result = Config.fromEnv(
      minimal.removed("DATABASE_PASSWORD"),
      "application-wrong-secret.conf"
    )
    result match
      case Right(config) => fail(s"expected failure, got $config")
      case Left(errors)  =>
        assert(errors.exists(_.contains("Secret")), errors.mkString("; "))
        assert(!errors.mkString.contains(secretValue))

  test("config never renders secrets in toString"):
    val Right(c) =
      Config.fromEnv(minimal ++ Map("ADMIN_PASSWORD" -> "hunter2")): @unchecked
    val rendered = c.toString
    assert(!rendered.contains("secret"), s"db password leaked: $rendered")
    assert(!rendered.contains("hunter2"), s"admin password leaked: $rendered")

  test("port outside valid range is rejected"):
    val result = Config.fromEnv(minimal + ("HTTP_PORT" -> "0"))
    assert(result.left.exists(_.exists(_.contains("Port"))))

  test("zero and negative session TTLs are rejected"):
    assert(Config.fromEnv(requiredOnly.updated("SESSION_TTL_DAYS", "0")).isLeft)
    assert(
      Config.fromEnv(requiredOnly.updated("SESSION_TTL_DAYS", "-1")).isLeft
    )

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

  test(
    "a secret containing HOCON substitution syntax is never reinterpreted"
  ):
    // DATABASE_URL/PASSWORD and LMBOT_MASTER_KEY bypass PureConfig/Typesafe
    // Config entirely, on principle: they are free text an operator or
    // attacker controls, not something to run through a general-purpose
    // parsing pipeline, even one that (as this test also proves) already
    // treats env-var-sourced values as literals rather than re-parsed HOCON.
    val weird = "p@ss${word}!"
    val env = minimal.updated("DATABASE_PASSWORD", weird)
    Config.fromEnv(env) match
      case Right(c)   => assertEquals(c.dbPassword.value, weird)
      case Left(errs) => fail(s"expected success, got $errs")
