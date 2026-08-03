package lmbot.backend.dev

import java.util.UUID

import lmbot.backend.luxmed.LuxmedConfig
import lmbot.backend.{AccountSeeder, Main}

class MainCompositionTest extends munit.FunSuite:

  private val minimal = Map(
    "DATABASE_URL" -> "jdbc:postgresql://localhost:5432/lmbot",
    "DATABASE_USER" -> "lmbot",
    "DATABASE_PASSWORD" -> "secret",
    "LMBOT_MASTER_KEY" -> "zipI+cHXewVqZsFi8jDDrAglsYK9B3fXZMswhyxr2hk="
  )

  private val deviceUuid =
    UUID.fromString("00000000-0000-4000-8000-000000000009")

  test("development resource selects the mock boundary and seeder"):
    var mock: Option[MockLuxmedServer] = None
    var selected: Option[(LuxmedConfig, AccountSeeder)] = None
    var hook: Option[Thread] = None

    val result = Main.run(
      Map("LMBOT_CONFIG_RESOURCE" -> "application-dev.conf"),
      deviceUuid,
      () =>
        val started = MockLuxmedServer.start()
        mock = Some(started)
        started
      ,
      (_, luxmed, seeder) =>
        selected = Some((luxmed, seeder))
        new AutoCloseable:
          override def close(): Unit = ()
      ,
      thread => hook = Some(thread)
    )

    assertEquals(result, Right(()))
    val startedMock = mock.getOrElse(fail("mock server was not started"))
    val (luxmed, seeder) =
      selected.getOrElse(fail("application was not started"))
    assertEquals(luxmed.oldApi, startedMock.oldApi)
    assertEquals(luxmed.newApi, startedMock.newApi)
    assertEquals(seeder, MockAccountSeed)
    hook.getOrElse(fail("shutdown hook was not registered")).run()

  test("live configuration selects production and the no-op seeder"):
    var selected: Option[(LuxmedConfig, AccountSeeder)] = None
    var hook: Option[Thread] = None

    val result = Main.run(
      minimal.updated("LIVE_LUXMED_API", "true"),
      deviceUuid,
      () => fail("live mode must not start the mock server"),
      (_, luxmed, seeder) =>
        selected = Some((luxmed, seeder))
        new AutoCloseable:
          override def close(): Unit = ()
      ,
      thread => hook = Some(thread)
    )

    assertEquals(result, Right(()))
    val (luxmed, seeder) =
      selected.getOrElse(fail("application was not started"))
    assertEquals(
      luxmed.oldApi.toString,
      "https://portalpacjenta.luxmed.pl/PatientPortalMobileAPI/api"
    )
    assertEquals(
      luxmed.newApi.toString,
      "https://portalpacjenta.luxmed.pl/PatientPortal"
    )
    assertEquals(seeder, AccountSeeder.noop)
    hook.getOrElse(fail("shutdown hook was not registered")).run()
