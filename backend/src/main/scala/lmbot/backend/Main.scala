package lmbot.backend

import java.time.Instant
import java.util.UUID

import scala.jdk.CollectionConverters.*

import lmbot.backend.config.Config
import lmbot.backend.crypto.AesGcm
import lmbot.backend.db.AccountRepo
import lmbot.backend.dev.{MockAccountSeed, MockLuxmedServer}
import lmbot.backend.luxmed.LuxmedConfig
import lmbot.shared.domain.UserId
import org.slf4j.LoggerFactory

/** Composition root: everything is wired by hand, in one readable place (spec
  * §5.7.5 — no DI framework, no reflection).
  */
object Main:

  private val log = LoggerFactory.getLogger(getClass)

  private[backend] def luxmedConfig(
      config: Config,
      mockLuxmed: Option[MockLuxmedServer],
      deviceUuid: UUID
  ): LuxmedConfig =
    mockLuxmed match
      case Some(mock) =>
        LuxmedConfig(
          oldApi = mock.oldApi,
          newApi = mock.newApi,
          appVersion = config.luxmedAppVersion,
          deviceUuid = deviceUuid
        )
      case None =>
        LuxmedConfig.production(config.luxmedAppVersion, deviceUuid)

  private def accountSeeder(
      mockLuxmed: Option[MockLuxmedServer]
  ): AccountSeeder =
    mockLuxmed.fold(AccountSeeder.noop)(_ =>
      new AccountSeeder:
        override def ensure(
            owner: UserId,
            accounts: AccountRepo,
            crypto: AesGcm
        ): Unit =
          MockAccountSeed.ensure(owner, accounts, crypto, () => Instant.now())
    )

  def main(args: Array[String]): Unit =
    Config.fromEnv(System.getenv().asScala.toMap) match
      case Left(errors) =>
        errors.foreach(e => log.error(s"Configuration error: $e"))
        sys.exit(1)

      case Right(config) =>
        val mockLuxmed =
          if config.liveLuxmedApi then
            log.info("Using the live Luxmed API")
            None
          else
            log.warn(
              "LIVE_LUXMED_API is not true; using the local mock Luxmed API"
            )
            Some(MockLuxmedServer.start())

        val application =
          try
            BackendApplication.start(
              config,
              luxmedConfig(config, mockLuxmed, UUID.randomUUID()),
              accountSeeder(mockLuxmed)
            )
          catch
            case t: Throwable =>
              mockLuxmed.foreach(_.close())
              throw t

        Runtime.getRuntime.addShutdownHook(
          Thread: () =>
            log.info("Shutting down")
            application.close()
            mockLuxmed.foreach(_.close())
        )
