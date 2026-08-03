package lmbot.backend

import scala.collection.mutable.ListBuffer

class ApplicationLifecycleTest extends munit.FunSuite:

  final private class Resource(
      name: String,
      events: ListBuffer[String],
      failure: Option[Throwable]
  ) extends AutoCloseable:
    override def close(): Unit =
      events += name
      failure.foreach(error => throw error)

  test("close preserves the first failure and suppresses later failures"):
    val events = ListBuffer.empty[String]
    val serverFailure = IllegalStateException("server cleanup")
    val dataSourceFailure = IllegalStateException("data source cleanup")
    val embeddedDbFailure = IllegalStateException("embedded database cleanup")

    val thrown = intercept[IllegalStateException]:
      ApplicationLifecycle.closeAll(
        List(
          Resource("server", events, Some(serverFailure)),
          Resource("dataSource", events, Some(dataSourceFailure)),
          Resource("embeddedDb", events, Some(embeddedDbFailure))
        )
      )

    assertEquals(thrown, serverFailure)
    assertEquals(events.toList, List("server", "dataSource", "embeddedDb"))
    assertEquals(
      thrown.getSuppressed.toList,
      List(dataSourceFailure, embeddedDbFailure)
    )

  test("startup preserves its failure while closing every acquired resource"):
    val events = ListBuffer.empty[String]
    val startupFailure = IllegalStateException("server startup")
    val dataSourceFailure = IllegalStateException("data source cleanup")
    val embeddedDbFailure = IllegalStateException("embedded database cleanup")

    val thrown = intercept[IllegalStateException]:
      ApplicationLifecycle.withCleanupOnFailure(
        List(
          Resource("dataSource", events, Some(dataSourceFailure)),
          Resource("embeddedDb", events, Some(embeddedDbFailure))
        )
      ):
        throw startupFailure

    assertEquals(thrown, startupFailure)
    assertEquals(events.toList, List("dataSource", "embeddedDb"))
    assertEquals(
      thrown.getSuppressed.toList,
      List(dataSourceFailure, embeddedDbFailure)
    )
