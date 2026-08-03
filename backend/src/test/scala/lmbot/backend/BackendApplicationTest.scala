package lmbot.backend

import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

import lmbot.backend.config.{AppVersion, Config, MasterKey, Port, Secret}
import lmbot.backend.crypto.AesGcm
import lmbot.backend.db.{AccountRepo, Database, UserRepo}
import lmbot.backend.http.Server
import lmbot.backend.luxmed.LuxmedConfig
import lmbot.backend.support.EmbeddedPg
import lmbot.shared.domain.UserId

class BackendApplicationTest extends munit.FunSuite:

  final private class Resource(
      name: String,
      events: ListBuffer[String],
      failure: Option[Throwable]
  ) extends AutoCloseable:
    override def close(): Unit =
      events += name
      failure.foreach(error => throw error)

  test("close preserves the primary failure and closes every resource"):
    val events = ListBuffer.empty[String]
    val serverFailure = IllegalStateException("server cleanup")
    val dataSourceFailure = IllegalStateException("data source cleanup")
    val application = new BackendApplication(
      List(
        Resource("server", events, Some(serverFailure)),
        Resource("dataSource", events, Some(dataSourceFailure)),
        Resource("embeddedDb", events, None)
      )
    )

    val thrown = intercept[IllegalStateException](application.close())
    application.close()

    assertEquals(thrown, serverFailure)
    assertEquals(events.toList, List("server", "dataSource", "embeddedDb"))
    assertEquals(thrown.getSuppressed.toList, List(dataSourceFailure))

  test("runs the supplied account seeder after admin bootstrap"):
    val pg = EmbeddedPg.startForTest(port = 0)
    val config = configWithAdmin(pg.jdbcUrl, pg.username, pg.password)
    val inspectionDataSource =
      Database.dataSource(config.dbUrl, config.dbUser, config.dbPassword.value)
    val inspectionUsers = UserRepo(Database.transactor(inspectionDataSource))
    val calls = ConcurrentLinkedQueue[SeedCall]()
    val seeder = new AccountSeeder:
      override def ensure(
          owner: UserId,
          accounts: AccountRepo,
          crypto: AesGcm
      ): Unit =
        calls.add(
          SeedCall(
            owner,
            inspectionUsers.findById(owner).map(user => UserId(user.id))
          )
        )

    val application =
      BackendApplication.start(
        config,
        LuxmedConfig
          .production(AppVersion.unsafeFromString("4.44.0"), UUID.randomUUID()),
        seeder,
        (host, _, endpoints) => Server.start(host, 0, endpoints)
      )
    try
      inspectionUsers.findByUsername(adminUsername) match
        case Some(admin) =>
          val owner = UserId(admin.id)
          assertEquals(calls.asScala.toList, List(SeedCall(owner, Some(owner))))
        case None => fail("expected the configured admin to be persisted")
    finally
      try
        application.close()
        application.close()
      finally
        try inspectionDataSource.close()
        finally pg.close()

  final private case class SeedCall(
      owner: UserId,
      persistedOwner: Option[UserId]
  )

  private val adminUsername = "application-test-admin"

  private def configWithAdmin(
      dbUrl: String,
      dbUser: String,
      dbPassword: String
  ): Config =
    Config(
      dbUrl = dbUrl,
      dbUser = dbUser,
      dbPassword = Secret(dbPassword),
      httpHost = "127.0.0.1",
      httpPort = Port
        .fromInt(8080)
        .fold(error => throw IllegalStateException(error), identity),
      cookieSecure = false,
      sessionTtl = Duration.ofDays(7),
      luxmedAppVersion = AppVersion.unsafeFromString("4.44.0"),
      adminUsername = Some(adminUsername),
      adminPassword = Some(Secret("application-test-password")),
      masterKey = MasterKey
        .fromBase64(Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7)))
        .fold(error => throw IllegalStateException(error), identity)
    )
