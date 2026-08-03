package lmbot.backend

import scala.collection.mutable.ListBuffer

import lmbot.backend.config.Config

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

  private def configWith(liveLuxmedApi: Boolean): Config =
    val Right(config) = Config.fromEnv(minimal): @unchecked
    config.copy(liveLuxmedApi = liveLuxmedApi)
