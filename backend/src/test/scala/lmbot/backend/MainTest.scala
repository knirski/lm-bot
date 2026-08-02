package lmbot.backend

import lmbot.backend.config.Config

class MainTest extends munit.FunSuite:

  private val minimal = Map(
    "DATABASE_URL" -> "jdbc:postgresql://localhost:5432/lmbot",
    "DATABASE_USER" -> "lmbot",
    "DATABASE_PASSWORD" -> "secret",
    "LMBOT_MASTER_KEY" -> "zipI+cHXewVqZsFi8jDDrAglsYK9B3fXZMswhyxr2hk="
  )

  test("production rejects mock Luxmed mode"):
    assertEquals(
      Main.requireLiveLuxmedApi(configWith(liveLuxmedApi = false)),
      Left("LIVE_LUXMED_API=true is required in production")
    )

  test("production accepts live Luxmed mode"):
    assertEquals(
      Main.requireLiveLuxmedApi(configWith(liveLuxmedApi = true)),
      Right(())
    )

  private def configWith(liveLuxmedApi: Boolean): Config =
    val Right(config) = Config.fromEnv(minimal): @unchecked
    config.copy(liveLuxmedApi = liveLuxmedApi)
