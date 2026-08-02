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

  private[dev] def accountSeeder(
      mock: Option[MockLuxmedServer]
  ): AccountSeeder =
    mock.fold(AccountSeeder.noop)(_ => MockAccountSeed)

  private[dev] def closeAfterFailure(
      application: Option[AutoCloseable],
      mock: Option[AutoCloseable],
      primary: Throwable
  ): Unit =
    (application.toList ++ mock.toList).foreach: resource =>
      try resource.close()
      catch
        case cleanup: Throwable =>
          if cleanup ne primary then primary.addSuppressed(cleanup)

  private[dev] def installShutdownHook(
      application: AutoCloseable,
      mock: Option[AutoCloseable],
      register: Thread => Unit
  ): Unit =
    val hook = Thread: () =>
      try application.close()
      catch
        case error: Throwable =>
          closeAfterFailure(None, mock, error)
          throw error
      mock.foreach(_.close())

    try register(hook)
    catch
      case error: Throwable =>
        closeAfterFailure(Some(application), mock, error)
        throw error

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

        val application =
          try
            val selectedLuxmedConfig =
              luxmedConfig(config, mock, UUID.randomUUID())
            BackendApplication.start(
              config,
              selectedLuxmedConfig,
              accountSeeder(mock)
            )
          catch
            case error: Throwable =>
              closeAfterFailure(None, mock, error)
              throw error

        installShutdownHook(
          application,
          mock,
          thread => Runtime.getRuntime.addShutdownHook(thread)
        )
