package lmbot.backend

import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean

import scala.util.Random

import gears.async.{Async, Future}
import lmbot.backend.account.{
  AccountClientFactory,
  AccountClientRegistry,
  AccountService,
  DictionaryService
}
import lmbot.backend.auth.{AdminBootstrap, AuthService}
import lmbot.backend.config.Config
import lmbot.backend.crypto.AesGcm
import lmbot.backend.db.{
  AccountRepo,
  Database,
  MonitorEventRepo,
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
  SettingsRoutes,
  StaticRoutes
}
import lmbot.backend.luxmed.LuxmedConfig
import lmbot.backend.monitor.{
  LuxmedSlotSearch,
  MonitorCheck,
  MonitorEngine,
  MonitorService
}
import lmbot.backend.notify.{
  NotificationService,
  TelegramBot,
  TelegramLinkPoller,
  TelegramLinkService,
  TelegramNotificationChannel
}
import lmbot.backend.support.{EmbeddedDb, EmbeddedPg, Sleeper}
import lmbot.shared.domain.UserId
import org.slf4j.LoggerFactory
import sttp.model.Uri
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.jdkhttp.HttpServer

final class BackendApplication private[backend] (
    resources: List[AutoCloseable]
) extends AutoCloseable:

  private val closed = AtomicBoolean(false)

  override def close(): Unit =
    if closed.compareAndSet(false, true) then
      ApplicationLifecycle.closeAll(resources)

object BackendApplication:

  private[backend] type ServerStarter = (
      String,
      Int,
      List[ServerEndpoint[Any, Identity]]
  ) => HttpServer

  private val log = LoggerFactory.getLogger(getClass)

  def start(
      config: Config,
      luxmedConfig: LuxmedConfig,
      accountSeeder: AccountSeeder
  ): BackendApplication =
    start(
      config,
      luxmedConfig,
      accountSeeder,
      (host, port, endpoints) => Server.start(host, port, endpoints)
    )

  private[backend] def start(
      config: Config,
      luxmedConfig: LuxmedConfig,
      accountSeeder: AccountSeeder,
      startServer: ServerStarter
  ): BackendApplication =
    val embeddedDb = startEmbeddedDb(config.embeddedPg)
    val dataSource =
      ApplicationLifecycle.withCleanupOnFailure(embeddedDb.toList):
        Database.dataSource(
          config.dbUrl,
          config.dbUser,
          config.dbPassword.value
        )

    val (server, engine, telegramPoller) =
      ApplicationLifecycle.withCleanupOnFailure(
        dataSource :: embeddedDb.toList
      ):
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
        val accountClientRegistry =
          AccountClientRegistry.production(accountRepo, accountClients)
        val accountService =
          AccountService(accountRepo, accountClients, crypto)
        val accountRoutes = AccountRoutes(auth, accountService)
        val dictionaryService = DictionaryService(accountClientRegistry)
        val dictionaryRoutes = DictionaryRoutes(auth, dictionaryService)
        val monitorRepo = MonitorRepo(xa)
        val eventRepo = MonitorEventRepo(xa)
        val monitorService =
          MonitorService(monitorRepo, accountRepo, eventRepo)
        val monitorRoutes = MonitorRoutes(auth, monitorService)

        val now = () => OffsetDateTime.now()
        val telegramLink =
          TelegramLinkService(users, config.telegramBotUsername, now)
        val settingsRoutes = SettingsRoutes(auth, telegramLink)
        val telegramBot = config.telegramBotToken
          .zip(config.telegramBotUsername)
          .map: (token, _) =>
            TelegramBot.production(
              token,
              Uri.unsafeParse(config.telegramApiBase)
            )
        val notifier = NotificationService(
          users,
          telegramBot.map(TelegramNotificationChannel(_)),
          eventRepo,
          now
        )
        val engine = MonitorEngine(
          monitorRepo,
          accountRepo,
          MonitorCheck(
            LuxmedSlotSearch(accountClientRegistry.forAccount),
            eventRepo,
            notifier,
            now
          ),
          notifier,
          eventRepo,
          Sleeper.Default,
          () => Random.nextDouble(),
          now
        )
        val telegramPoller = telegramBot.map: bot =>
          TelegramLinkPoller(bot, telegramLink, users, Sleeper.Default)

        val server = startServer(
          config.httpHost,
          config.httpPort.value,
          HealthRoutes.endpoints ++ authRoutes.endpoints ++
            accountRoutes.endpoints ++ dictionaryRoutes.endpoints ++
            monitorRoutes.endpoints ++ settingsRoutes.endpoints ++
            StaticRoutes.endpoints
        )
        (server, engine, telegramPoller)

    val serverCleanup = serverResource(server)
    ApplicationLifecycle.withCleanupOnFailure(
      serverCleanup :: dataSource :: embeddedDb.toList
    ):
      val worker =
        BackgroundWorker.start("lm-bot-background")(stop ?=>
          Async.group:
            telegramPoller.foreach(poller => Future(poller.run(stop)))
            engine.run(stop)
        )
      val resources =
        worker :: serverCleanup :: dataSource :: embeddedDb.toList
      ApplicationLifecycle.withCleanupOnFailure(resources):
        log.info(
          s"lm-bot listening on ${config.httpHost}:${server.getAddress.getPort}"
        )
        new BackendApplication(resources)

  private def serverResource(server: HttpServer): AutoCloseable =
    new AutoCloseable:
      override def close(): Unit = server.stop(3)

  private def startEmbeddedDb(enabled: Boolean): Option[EmbeddedDb] =
    if enabled then
      log.info("Starting embedded database on port 15432")
      Some(EmbeddedPg.startForDev(15432))
    else None
