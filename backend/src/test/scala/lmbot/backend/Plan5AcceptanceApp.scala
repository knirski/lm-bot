package lmbot.backend

import java.net.InetSocketAddress
import java.time.{Duration, OffsetDateTime}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, Executors}

import scala.util.Random

import com.augustnagro.magnum.Transactor
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import gears.async.{Async, Future}
import lmbot.backend.account.{
  AccountClientFactory,
  AccountClientRegistry,
  AccountService,
  DictionaryService
}
import lmbot.backend.auth.{AdminBootstrap, AuthService}
import lmbot.backend.config.{Config, Secret}
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
  AccountHealth,
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
import lmbot.backend.support.{EmbeddedPg, Sleeper}
import lmbot.shared.domain.{MonitorId, UserId}
import org.slf4j.LoggerFactory
import sttp.model.Uri

/** A test-scope main for the Plan 5 browser acceptance run: the full
  * composition graph — database, auth, accounts, monitors, the engine, the
  * Telegram boundary, and the settings routes — with the two owned external
  * boundaries substituted.
  *
  * Luxmed is answered by the Plan 4 loopback stub, extended with a
  * `terms/index` route that returns a slot per day inside the requested window.
  * Telegram is answered by [[FakeTelegramServer]], selected by
  * `TELEGRAM_API_BASE`. Everything else is real: the repositories, the
  * services, the engine, and the poller.
  *
  * The engine's `Sleeper` caps every wait at 200 ms so a browser run does not
  * take minutes; the waits themselves are unit-tested with their real
  * durations.
  *
  * Run it with:
  * {{{
  *   sbt "backend/Test/runMain lmbot.backend.Plan5AcceptanceApp"
  * }}}
  *
  * The control server (its URL is printed) exists for the scenarios a browser
  * cannot perform by itself:
  *
  *   - `POST /start?chatId=555&code=<code>` queues a `/start <code>` update for
  *     the poller to consume.
  *   - `POST /fail-terms?times=3` makes the Luxmed stub answer the next terms
  *     searches with malformed JSON, driving the retry budget.
  *   - `GET /status` reports every monitor's state, last-check summary, and
  *     event counts, plus every Telegram message the app has sent.
  */
object Plan5AcceptanceApp:

  private val log = LoggerFactory.getLogger(getClass)

  /** Caps every engine and poller wait so the acceptance run finishes in
    * seconds. The real durations live in `EnginePolicy` and are unit-tested.
    */
  private object FastForwardSleeper extends Sleeper:
    private val cap = Duration.ofMillis(200)
    def sleep(duration: Duration)(using Async): Unit =
      val wait = if duration.compareTo(cap) < 0 then duration else cap
      Thread.sleep(wait.toMillis)

  final private case class Graph(server: HttpServer, worker: BackgroundWorker)

  def main(args: Array[String]): Unit =
    val dbPort = Plan4AcceptanceConfig.freePort()
    val httpPort = Plan4AcceptanceConfig.freePort()
    val controlPort = Plan4AcceptanceConfig.freePort()

    val db = EmbeddedPg.startForDev(dbPort)
    val luxmed = Plan4AcceptanceConfig.StubLuxmedServer()
    val telegram = FakeTelegramServer()
    val config = Plan4AcceptanceConfig
      .config(dbPort, httpPort)
      .copy(
        telegramBotToken = Some(Secret("acceptance-telegram-token")),
        telegramBotUsername = Some("acceptance_bot"),
        telegramApiBase = telegram.baseUri
      )

    val ds = Database.dataSource(
      config.dbUrl,
      config.dbUser,
      config.dbPassword.value
    )
    Database.migrate(ds)
    val xa = Database.transactor(ds)
    val crypto = AesGcm(config.masterKey)

    bootstrapAdmin(config, UserRepo(xa))

    val graph = AtomicReference(start(config, xa, luxmed, crypto))
    val control =
      startControl(controlPort, config, xa, luxmed, telegram)

    log.info(s"Acceptance app:      http://${config.httpHost}:$httpPort")
    log.info(s"Acceptance control:  http://${config.httpHost}:$controlPort")
    log.info(s"Sign in as:          ${Plan4AcceptanceConfig.adminUsername}")
    log.info(s"Luxmed stub:         ${luxmed.baseUri}")
    log.info(s"Fake Telegram:       ${telegram.baseUri}")

    Runtime.getRuntime.addShutdownHook(
      Thread: () =>
        log.info("Shutting down")
        graph.get().worker.close()
        graph.get().server.stop(0)
        control.stop(0)
        telegram.close()
        luxmed.close()
        ds.close()
        db.close()
    )

    CountDownLatch(1).await()

  private def bootstrapAdmin(config: Config, users: UserRepo): Unit =
    AdminBootstrap(users).run(
      config.adminUsername,
      config.adminPassword.map(_.value)
    ) match
      case AdminBootstrap.Outcome.Created(username) =>
        log.info(s"Created initial admin account '$username'")
      case AdminBootstrap.Outcome.SkippedUsersExist =>
        log.info("Users already exist; skipping admin bootstrap")
      case AdminBootstrap.Outcome.MissingCredentials =>
        log.warn("No admin credentials; nobody can log in")

  private def start(
      config: Config,
      xa: Transactor,
      luxmed: Plan4AcceptanceConfig.StubLuxmedServer,
      crypto: AesGcm
  ): Graph =
    val users = UserRepo(xa)
    val sessions = SessionRepo(xa)
    val auth =
      AuthService(
        users,
        sessions,
        config.sessionTtl,
        () => OffsetDateTime.now()
      )
    val authRoutes = AuthRoutes(auth, config.cookieSecure, config.sessionTtl)

    val accountRepo = AccountRepo(xa)
    val luxmedBaseConfig = LuxmedConfig(
      oldApi = luxmed.oldApi,
      newApi = luxmed.newApi,
      appVersion = config.luxmedAppVersion,
      deviceUuid = UUID.randomUUID()
    )
    val accountClients = AccountClientFactory.production(
      xa = xa,
      accounts = accountRepo,
      baseConfig = luxmedBaseConfig,
      crypto = crypto
    )
    val registry = AccountClientRegistry.production(accountRepo, accountClients)
    val accountService = AccountService(accountRepo, accountClients, crypto)
    val accountRoutes = AccountRoutes(auth, accountService)

    val monitorRepo = MonitorRepo(xa)
    val eventRepo = MonitorEventRepo(xa)
    val monitorService = MonitorService(monitorRepo, accountRepo, eventRepo)
    val monitorRoutes = MonitorRoutes(auth, monitorService)

    val now = () => OffsetDateTime.now()
    val telegramLink =
      TelegramLinkService(users, config.telegramBotUsername, now)
    val settingsRoutes = SettingsRoutes(auth, telegramLink)

    val bot = TelegramBot.production(
      config.telegramBotToken.get,
      Uri.unsafeParse(config.telegramApiBase)
    )
    val notifier = NotificationService(
      users,
      Some(TelegramNotificationChannel(bot)),
      eventRepo,
      now
    )
    val health =
      AccountHealth(accountRepo, monitorRepo, eventRepo, notifier, now)
    val dictionaryRoutes =
      DictionaryRoutes(auth, DictionaryService(registry, health))
    val engine = MonitorEngine(
      monitorRepo,
      accountRepo,
      MonitorCheck(
        LuxmedSlotSearch(registry.forAccount),
        eventRepo,
        notifier,
        now
      ),
      notifier,
      health,
      eventRepo,
      FastForwardSleeper,
      () => Random.nextDouble(),
      now
    )
    val poller =
      TelegramLinkPoller(bot, telegramLink, users, FastForwardSleeper)

    val server = Server.start(
      config.httpHost,
      config.httpPort.value,
      HealthRoutes.endpoints ++ authRoutes.endpoints ++
        accountRoutes.endpoints ++ dictionaryRoutes.endpoints ++
        monitorRoutes.endpoints ++ settingsRoutes.endpoints ++
        StaticRoutes.endpoints
    )

    val worker = BackgroundWorker.start("plan5-acceptance")(stop ?=>
      Async.group:
        Future(poller.run(stop))
        engine.run(stop)
    )
    Graph(server, worker)

  private def startControl(
      port: Int,
      config: Config,
      xa: Transactor,
      luxmed: Plan4AcceptanceConfig.StubLuxmedServer,
      telegram: FakeTelegramServer
  ): HttpServer =
    val server = HttpServer.create(
      InetSocketAddress(Plan4AcceptanceConfig.host, port),
      0
    )
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
    server.createContext(
      "/",
      exchange =>
        try
          val path = exchange.getRequestURI.getRawPath
          val params = queryParams(exchange)
          val body = path match
            case "/start" =>
              val chatId =
                params.get("chatId").flatMap(_.toLongOption).getOrElse(555L)
              val code = params.getOrElse("code", "")
              telegram.enqueueStart(code, chatId)
              s"""{"enqueued":true,"chatId":$chatId}"""
            case "/fail-terms" =>
              val times =
                params.get("times").flatMap(_.toIntOption).getOrElse(3)
              luxmed.failTermsNext(times)
              s"""{"failingTermsFor":$times}"""
            case "/status" =>
              status(xa, config, telegram)
            case other =>
              s"""{"error":"unknown control path","path":${quote(other)}}"""
          respond(exchange, 200, body)
        catch
          case t: Throwable =>
            log.error("Control request failed", t)
            respond(
              exchange,
              500,
              s"""{"error":${quote(
                  Option(t.getMessage).getOrElse(t.toString)
                )}}"""
            )
    )
    server.start()
    server

  private def queryParams(exchange: HttpExchange): Map[String, String] =
    Option(exchange.getRequestURI.getQuery) match
      case None        => Map.empty
      case Some(query) =>
        query
          .split('&')
          .flatMap: pair =>
            pair.split("=", 2) match
              case Array(key, value) =>
                Some(
                  key -> java.net.URLDecoder.decode(
                    value,
                    java.nio.charset.StandardCharsets.UTF_8
                  )
                )
              case _ => None
          .toMap

  /** What the acceptance scenarios read: every monitor's state and event
    * counts, and every message the app sent to Telegram.
    */
  private def status(
      xa: Transactor,
      config: Config,
      telegram: FakeTelegramServer
  ): String =
    val adminId = config.adminUsername
      .flatMap(UserRepo(xa).findByUsername)
      .map(row => UserId(row.id))
    val monitors =
      adminId.toList.flatMap(ownerId => MonitorRepo(xa).listOwned(ownerId))
    val monitorJson = monitors.map: monitor =>
      val counts = MonitorEventRepo(xa)
        .listRecent(MonitorId(monitor.id), 200)
        .groupBy(_.kind)
        .view
        .mapValues(_.size)
        .toMap
      val kinds = counts.toList
        .map((kind, count) => s"${quote(kind)}:$count")
        .mkString(",")
      s"""{"id":${monitor.id},"state":"${monitor.state}",""" +
        s""""lastCheckSummary":${quoteOpt(monitor.lastCheckSummary)},""" +
        s""""eventKinds":{$kinds}}"""
    val messages = telegram.messages
      .map((chatId, text) => s"""{"chatId":$chatId,"text":${quote(text)}}""")
    s"""{"monitors":[${monitorJson.mkString(",")}],""" +
      s""""messages":[${messages.mkString(",")}]}"""

  private def respond(exchange: HttpExchange, status: Int, body: String): Unit =
    try
      val bytes = body.getBytes("UTF-8")
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(status, bytes.length)
      exchange.getResponseBody.write(bytes)
    finally exchange.close()

  private def quoteOpt(value: Option[String]): String =
    value.map(quote).getOrElse("null")

  /** Minimal JSON string literal encoder for the control responses. */
  private def quote(value: String): String =
    val escaped = value.flatMap:
      case '"'                 => "\\\""
      case '\\'                => "\\\\"
      case '\n'                => "\\n"
      case '\r'                => "\\r"
      case '\t'                => "\\t"
      case c if c.toInt < 0x20 => f"\\u${c.toInt}%04x"
      case c                   => c.toString
    s""""$escaped""""
