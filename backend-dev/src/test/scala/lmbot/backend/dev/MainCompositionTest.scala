package lmbot.backend.dev

import java.util.UUID

import lmbot.backend.config.Config

class MainCompositionTest extends munit.FunSuite:

  private val minimal = Map(
    "DATABASE_URL" -> "jdbc:postgresql://localhost:5432/lmbot",
    "DATABASE_USER" -> "lmbot",
    "DATABASE_PASSWORD" -> "secret",
    "LMBOT_MASTER_KEY" -> "zipI+cHXewVqZsFi8jDDrAglsYK9B3fXZMswhyxr2hk="
  )

  test("mock mode selects the loopback Luxmed endpoints"):
    val config = Config.fromEnv(minimal).toOption.get
    val mock = MockLuxmedServer.start()
    try
      val selected =
        DevMain.luxmedConfig(config, Some(mock), UUID.randomUUID())
      assertEquals(selected.oldApi, mock.oldApi)
      assertEquals(selected.newApi, mock.newApi)
    finally mock.close()

  test("live mode selects the production Luxmed endpoints"):
    val config =
      Config.fromEnv(minimal.updated("LIVE_LUXMED_API", "true")).toOption.get
    val selected = DevMain.luxmedConfig(config, None, UUID.randomUUID())
    assert(
      selected.oldApi.toString.startsWith("https://portalpacjenta.luxmed.pl")
    )
    assert(
      selected.newApi.toString.startsWith("https://portalpacjenta.luxmed.pl")
    )
