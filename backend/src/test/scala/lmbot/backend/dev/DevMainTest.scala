package lmbot.backend.dev

import scala.collection.mutable.ListBuffer

import lmbot.backend.AccountSeeder

/** Lifecycle coverage retained for the unified launcher. */
class DevMainTest extends munit.FunSuite:

  final private class Resource(
      name: String,
      events: ListBuffer[String],
      failure: Option[Throwable]
  ) extends AutoCloseable:
    override def close(): Unit =
      events += name
      failure.foreach(error => throw error)

  test("seeder selection follows the mock boundary"):
    assertEquals(DevMain.accountSeeder(None), AccountSeeder.noop)

    val mock = MockLuxmedServer.start()
    try
      assertEquals(DevMain.accountSeeder(Some(mock)), MockAccountSeed)
    finally mock.close()

  test(
    "hook registration closes application before mock and preserves failure"
  ):
    val events = ListBuffer.empty[String]
    val applicationFailure = IllegalStateException("application cleanup")
    val mockFailure = IllegalStateException("mock cleanup")
    val registrationFailure = IllegalStateException("hook registration")
    val application = Resource("application", events, Some(applicationFailure))
    val mock = Resource("mock", events, Some(mockFailure))

    val thrown = intercept[IllegalStateException]:
      DevMain.installShutdownHook(
        application,
        Some(mock),
        _ => throw registrationFailure
      )

    assertEquals(thrown, registrationFailure)
    assertEquals(events.toList, List("application", "mock"))
    assertEquals(
      thrown.getSuppressed.toList,
      List(applicationFailure, mockFailure)
    )

  test(
    "shutdown preserves application failure and suppresses mock cleanup failure"
  ):
    val events = ListBuffer.empty[String]
    val applicationFailure = IllegalStateException("application shutdown")
    val mockFailure = IllegalStateException("mock shutdown")
    val application = Resource("application", events, Some(applicationFailure))
    val mock = Resource("mock", events, Some(mockFailure))
    var hook: Option[Thread] = None

    DevMain.installShutdownHook(
      application,
      Some(mock),
      thread => hook = Some(thread)
    )

    val thrown = intercept[IllegalStateException](
      hook.getOrElse(fail("shutdown hook was not registered")).run()
    )

    assertEquals(thrown, applicationFailure)
    assertEquals(events.toList, List("application", "mock"))
    assertEquals(thrown.getSuppressed.toList, List(mockFailure))
