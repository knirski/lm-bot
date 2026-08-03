package lmbot.backend

import java.util.UUID

import scala.collection.mutable.ListBuffer

import lmbot.backend.config.Config
import lmbot.backend.dev.MockLuxmedServer

class MainTest extends munit.FunSuite:

  final private class Resource(
      events: ListBuffer[String],
      failure: Option[Throwable]
  ) extends AutoCloseable:
    override def close(): Unit =
      events += "application"
      failure.foreach(error => throw error)

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

  test("production rejection does not generate a device identity"):
    var generated = false

    val result = Main.run(
      minimal.updated("LIVE_LUXMED_API", "false"), {
        generated = true
        UUID.fromString("00000000-0000-4000-8000-000000000010")
      },
      () => fail("rejected production configuration must not start a mock"),
      (_, _, _) => fail("rejected production configuration must not start"),
      _ => fail("rejected production configuration must not register a hook")
    )

    assertEquals(
      result,
      Left(List("LIVE_LUXMED_API=true is required in production"))
    )
    assert(!generated)

  test(
    "configuration resource defaults to production and selects development explicitly"
  ):
    assertEquals(Main.configResource(Map.empty), "application.conf")
    assertEquals(
      Main.configResource(
        Map("LMBOT_CONFIG_RESOURCE" -> "application-dev.conf")
      ),
      "application-dev.conf"
    )

  test("unknown configuration resources are rejected before startup"):
    val result = Main.run(
      Map("LMBOT_CONFIG_RESOURCE" -> "application-typo.conf"),
      UUID.randomUUID(),
      () => fail("unknown resources must not start a mock"),
      (_, _, _) => fail("unknown resources must not start the application"),
      _ => fail("unknown resources must not register a hook")
    )

    assertEquals(
      result,
      Left(List("Unknown configuration resource: application-typo.conf"))
    )

  test("failed hook registration closes the production application"):
    val events = ListBuffer.empty[String]
    val cleanupFailure = IllegalStateException("application cleanup")
    val registrationFailure = IllegalStateException("hook registration")
    val application = Resource(events, Some(cleanupFailure))

    val thrown = intercept[IllegalStateException]:
      Main.installShutdownHook(
        application,
        _ => throw registrationFailure
      )

    assertEquals(thrown, registrationFailure)
    assertEquals(events.toList, List("application"))
    assertEquals(thrown.getSuppressed.toList, List(cleanupFailure))

  test("application startup failure closes the development mock"):
    val mock = MockLuxmedServer.start()
    var closedByRun = false

    try
      val result = Main.run(
        Map("LMBOT_CONFIG_RESOURCE" -> "application-dev.conf"),
        UUID.randomUUID(),
        () => mock,
        (_, _, _) => throw IllegalStateException("application startup"),
        _ => fail("failed startup must not register a hook")
      )

      assertEquals(result, Left(List("Startup failed: application startup")))
      assert(mock.isClosed, "startup failure must close the development mock")
      closedByRun = true
    finally if !closedByRun then mock.close()

  private def configWith(liveLuxmedApi: Boolean): Config =
    val Right(config) = Config.fromEnv(minimal): @unchecked
    config.copy(liveLuxmedApi = liveLuxmedApi)
