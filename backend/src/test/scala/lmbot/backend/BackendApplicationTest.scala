package lmbot.backend

import java.net.ServerSocket
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import lmbot.backend.config.{AppVersion, Config, MasterKey, Port, Secret}
import lmbot.backend.crypto.AesGcm
import lmbot.backend.db.AccountRepo
import lmbot.backend.luxmed.LuxmedConfig
import lmbot.backend.support.EmbeddedPg
import lmbot.shared.domain.UserId

class BackendApplicationTest extends munit.FunSuite:

  test("runs the supplied account seeder after admin bootstrap"):
    val pg = EmbeddedPg.startForTest(port = 0)
    val seen = AtomicReference[Option[UserId]](None)
    val seeder = new AccountSeeder:
      override def ensure(
          owner: UserId,
          accounts: AccountRepo,
          crypto: AesGcm
      ): Unit =
        seen.set(Some(owner))

    val application =
      BackendApplication.start(
        configWithAdmin(pg.jdbcUrl, pg.username, pg.password),
        LuxmedConfig
          .production(AppVersion.unsafeFromString("4.44.0"), UUID.randomUUID()),
        seeder
      )
    try
      assert(seen.get().nonEmpty)
    finally
      try
        application.close()
        application.close()
      finally pg.close()

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
        .fromInt(freePort())
        .fold(error => throw IllegalStateException(error), identity),
      cookieSecure = false,
      sessionTtl = Duration.ofDays(7),
      luxmedAppVersion = AppVersion.unsafeFromString("4.44.0"),
      adminUsername = Some("application-test-admin"),
      adminPassword = Some(Secret("application-test-password")),
      masterKey = MasterKey
        .fromBase64(Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7)))
        .fold(error => throw IllegalStateException(error), identity)
    )

  private def freePort(): Int =
    val socket = ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
