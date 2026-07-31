package lmbot.backend

import java.time.OffsetDateTime
import java.util.UUID

import scala.jdk.CollectionConverters.*

import lmbot.backend.account.{
  AccountClientFactory,
  AccountService,
  DictionaryService
}
import lmbot.backend.auth.{AdminBootstrap, AuthService}
import lmbot.backend.config.Config
import lmbot.backend.crypto.AesGcm
import lmbot.backend.db.{AccountRepo, Database, SessionRepo, UserRepo}
import lmbot.backend.http.{
  AccountRoutes,
  AuthRoutes,
  DictionaryRoutes,
  HealthRoutes,
  Server,
  StaticRoutes
}
import lmbot.backend.luxmed.LuxmedConfig
import lmbot.backend.support.EmbeddedDb
import lmbot.backend.support.EmbeddedPg
import org.slf4j.LoggerFactory

/** Composition root: everything is wired by hand, in one readable place (spec
  * §5.7.5 — no DI framework, no reflection).
  */
object Main:

  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    Config.fromEnv(System.getenv().asScala.toMap) match
      case Left(errors) =>
        errors.foreach(e => log.error(s"Configuration error: $e"))
        sys.exit(1)

      case Right(config) =>
        // Start embedded database in dev mode (EMBEDDED_PG env var set by
        // build.sbt's Compile / envVars).  The shutdown hook stops it when the
        // JVM exits.  Uses memgres by default; set EMBEDDED_DB=zonky for the
        // real PostgreSQL binary.
        if sys.env.get("EMBEDDED_PG").exists(v => v == "true" || v == "1") then
          log.info("Starting embedded database on port 15432")
          val pg: EmbeddedDb = EmbeddedPg.startForDev(15432)
          Runtime.getRuntime.addShutdownHook(Thread(() => pg.close()))

        val ds = Database.dataSource(
          config.dbUrl,
          config.dbUser,
          config.dbPassword.value
        )
        Database.migrate(ds)
        val xa = Database.transactor(ds)

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

        val auth = AuthService(
          users,
          sessions,
          config.sessionTtl,
          () => OffsetDateTime.now()
        )
        val routes = AuthRoutes(auth, config.cookieSecure, config.sessionTtl)

        val crypto = AesGcm(config.masterKey)
        val accountRepo = AccountRepo(xa)
        // `deviceUuid` here is a harmless placeholder: `AccountClientFactory`
        // always overrides it per-account with the device UUID stored for
        // that account.
        val luxmedBaseConfig =
          LuxmedConfig.production(config.luxmedAppVersion, UUID.randomUUID())
        val accountClients = AccountClientFactory.production(
          xa,
          accountRepo,
          luxmedBaseConfig,
          crypto
        )
        val accountService =
          AccountService(accountRepo, accountClients, crypto)
        val accountRoutes = AccountRoutes(auth, accountService)
        val dictionaryService = DictionaryService(accountClients)
        val dictionaryRoutes = DictionaryRoutes(auth, dictionaryService)

        val server = Server.start(
          config.httpHost,
          config.httpPort.value,
          HealthRoutes.endpoints ++ routes.endpoints ++
            accountRoutes.endpoints ++ dictionaryRoutes.endpoints ++
            StaticRoutes.endpoints
        )

        log.info(
          s"lm-bot listening on ${config.httpHost}:${server.getAddress.getPort}"
        )

        Runtime.getRuntime.addShutdownHook(
          Thread: () =>
            log.info("Shutting down")
            server.stop(3)
            ds.close()
        )
