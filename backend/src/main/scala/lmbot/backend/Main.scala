package lmbot.backend

import java.util.UUID

import scala.jdk.CollectionConverters.*

import lmbot.backend.config.Config
import lmbot.backend.dev.{DevMain, MockLuxmedServer}
import lmbot.backend.luxmed.LuxmedConfig
import org.slf4j.LoggerFactory

/** Composition root: everything is wired by hand, in one readable place (spec
  * §5.7.5 — no DI framework, no reflection).
  */
object Main:

  private val log = LoggerFactory.getLogger(getClass)
  private val productionResource = "application.conf"
  private val developmentResource = "application-dev.conf"

  private[backend] def configResource(env: Map[String, String]): String =
    env.getOrElse("LMBOT_CONFIG_RESOURCE", productionResource)

  private[backend] def requireLiveLuxmedApi(
      config: Config
  ): Either[String, Unit] =
    Either.cond(
      config.liveLuxmedApi,
      (),
      "LIVE_LUXMED_API=true is required in production"
    )

  private[backend] def installShutdownHook(
      application: AutoCloseable,
      register: Thread => Unit
  ): Unit =
    ApplicationLifecycle.installShutdownHook(List(application), register)

  private[backend] def run(
      env: Map[String, String],
      deviceUuid: UUID,
      startMock: () => MockLuxmedServer,
      startApplication: (Config, LuxmedConfig, AccountSeeder) => AutoCloseable,
      registerShutdownHook: Thread => Unit
  ): Either[List[String], Unit] =
    val resourceName = configResource(env)
    Config
      .fromEnv(env, resourceName)
      .flatMap: config =>
        val startupCheck =
          if resourceName == developmentResource then Right(())
          else requireLiveLuxmedApi(config).left.map(List(_))

        startupCheck.map: _ =>
          val mock =
            if config.liveLuxmedApi then None
            else Some(startMock())

          if mock.isDefined then
            log.info("Starting development backend with mock Luxmed API")
          else log.info("Starting backend with live Luxmed API")

          val application =
            try
              startApplication(
                config,
                DevMain.luxmedConfig(config, mock, deviceUuid),
                DevMain.accountSeeder(mock)
              )
            catch
              case error: Throwable =>
                ApplicationLifecycle.closeAfterFailure(mock.toList, error)
                throw error

          mock match
            case None =>
              installShutdownHook(application, registerShutdownHook)
            case Some(server) =>
              DevMain.installShutdownHook(
                application,
                Some(server),
                registerShutdownHook
              )

  def main(args: Array[String]): Unit =
    run(
      System.getenv().asScala.toMap,
      UUID.randomUUID(),
      () => MockLuxmedServer.start(),
      BackendApplication.start,
      thread => Runtime.getRuntime.addShutdownHook(thread)
    ) match
      case Left(errors) =>
        errors.foreach(e => log.error(s"Configuration error: $e"))
        sys.exit(1)
      case Right(_) => ()
