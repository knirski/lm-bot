package lmbot.backend.dev

import java.util.UUID

import scala.jdk.CollectionConverters.*

import lmbot.backend.config.Config
import lmbot.backend.luxmed.LuxmedConfig
import lmbot.backend.{AccountSeeder, BackendApplication}
import org.slf4j.LoggerFactory

/** Development composition root with an optional loopback Luxmed boundary. */
object DevMain:

  private val log = LoggerFactory.getLogger(getClass)

  private[dev] def luxmedConfig(
      config: Config,
      mock: Option[MockLuxmedServer],
      deviceUuid: UUID
  ): LuxmedConfig =
    mock match
      case Some(server) =>
        LuxmedConfig(
          server.oldApi,
          server.newApi,
          config.luxmedAppVersion,
          deviceUuid
        )
      case None =>
        LuxmedConfig.production(config.luxmedAppVersion, deviceUuid)

  def main(args: Array[String]): Unit =
    Config.fromEnv(System.getenv().asScala.toMap) match
      case Left(errors) =>
        errors.foreach(e => log.error(s"Configuration error: $e"))
        sys.exit(1)

      case Right(config) =>
        val mock =
          if config.liveLuxmedApi then None
          else Some(MockLuxmedServer.start())

        if mock.isDefined then
          log.info("Starting development backend with mock Luxmed API")
        else log.info("Starting development backend with live Luxmed API")

        try
          val selectedLuxmedConfig =
            luxmedConfig(config, mock, UUID.randomUUID())
          val accountSeeder =
            mock.fold(AccountSeeder.noop)(_ => MockAccountSeed)
          val application = BackendApplication.start(
            config,
            selectedLuxmedConfig,
            accountSeeder
          )

          Runtime.getRuntime.addShutdownHook(
            Thread: () =>
              log.info("Shutting down development backend")
              try application.close()
              finally mock.foreach(_.close())
          )
        catch
          case error: Throwable =>
            mock.foreach(_.close())
            throw error
