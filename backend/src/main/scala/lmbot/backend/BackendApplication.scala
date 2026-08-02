package lmbot.backend

import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean

import com.zaxxer.hikari.HikariDataSource
import lmbot.backend.account.{
  AccountClientFactory,
  AccountService,
  DictionaryService
}
import lmbot.backend.auth.{AdminBootstrap, AuthService}
import lmbot.backend.config.Config
import lmbot.backend.crypto.AesGcm
import lmbot.backend.db.{
  AccountRepo,
  Database,
  MonitorRepo,
  SessionRepo,
  UserRepo
}
import lmbot.backend.http.{
  AccountRoutes,
  AuthRoutes,
  DictionaryRoutes,
  HealthRoutes,
  MonitorRoutes,
  Server,
  StaticRoutes
}
import lmbot.backend.luxmed.LuxmedConfig
import lmbot.backend.monitor.MonitorService
import lmbot.backend.support.{EmbeddedDb, EmbeddedPg}
import lmbot.shared.domain.UserId
import org.slf4j.LoggerFactory
import sttp.tapir.server.jdkhttp.HttpServer

final class BackendApplication private (
    server: HttpServer,
    dataSource: HikariDataSource,
    embeddedDb: Option[EmbeddedDb]
) extends AutoCloseable:

  private val closed = AtomicBoolean(false)

  override def close(): Unit =
    if closed.compareAndSet(false, true) then
      try server.stop(3)
      finally
        try dataSource.close()
        finally embeddedDb.foreach(_.close())

object BackendApplication:

  private val log = LoggerFactory.getLogger(getClass)

  def start(
      config: Config,
      luxmedConfig: LuxmedConfig,
      accountSeeder: AccountSeeder
  ): BackendApplication =
    val embeddedDb = startEmbeddedDb()
    val dataSource =
      try
        Database.dataSource(
          config.dbUrl,
          config.dbUser,
          config.dbPassword.value
        )
      catch
        case t: Throwable =>
          embeddedDb.foreach(_.close())
          throw t

    try
      Database.migrate(dataSource)
      val xa = Database.transactor(dataSource)
      val users = UserRepo(xa)
      val sessions = SessionRepo(xa)

      AdminBootstrap(users).run(
        config.adminUsername,
        config.adminPassword.map(_.value)
      ) match
        case AdminBootstrap.Outcome.Created(username) =>
          log.info(s"Created initial admin account '$username'")
        case AdminBootstrap.Outcome.SkippedUsersExist =>
          log.info("Users already exist; skipping admin bootstrap")
        case AdminBootstrap.Outcome.MissingCredentials =>
          log.warn(
            "No users exist and ADMIN_USERNAME/ADMIN_PASSWORD are not both set — " +
              "nobody can log in. Set them and restart."
          )

      val crypto = AesGcm(config.masterKey)
      val accountRepo = AccountRepo(xa)
      config.adminUsername
        .flatMap(users.findByUsername)
        .map(owner => UserId(owner.id))
        .foreach(accountSeeder.ensure(_, accountRepo, crypto))

      val auth = AuthService(
        users,
        sessions,
        config.sessionTtl,
        () => OffsetDateTime.now()
      )
      val authRoutes =
        AuthRoutes(auth, config.cookieSecure, config.sessionTtl)
      val accountClients = AccountClientFactory.production(
        xa,
        accountRepo,
        luxmedConfig,
        crypto
      )
      val accountService =
        AccountService(accountRepo, accountClients, crypto)
      val accountRoutes = AccountRoutes(auth, accountService)
      val dictionaryService = DictionaryService(accountClients)
      val dictionaryRoutes = DictionaryRoutes(auth, dictionaryService)
      val monitorRepo = MonitorRepo(xa)
      val monitorService = MonitorService(monitorRepo, accountRepo)
      val monitorRoutes = MonitorRoutes(auth, monitorService)

      val server = Server.start(
        config.httpHost,
        config.httpPort.value,
        HealthRoutes.endpoints ++ authRoutes.endpoints ++
          accountRoutes.endpoints ++ dictionaryRoutes.endpoints ++
          monitorRoutes.endpoints ++ StaticRoutes.endpoints
      )

      log.info(
        s"lm-bot listening on ${config.httpHost}:${server.getAddress.getPort}"
      )
      new BackendApplication(server, dataSource, embeddedDb)
    catch
      case t: Throwable =>
        try dataSource.close()
        finally embeddedDb.foreach(_.close())
        throw t

  private def startEmbeddedDb(): Option[EmbeddedDb] =
    if sys.env.get("EMBEDDED_PG").exists(v => v == "true" || v == "1") then
      log.info("Starting embedded database on port 15432")
      Some(EmbeddedPg.startForDev(15432))
    else None
