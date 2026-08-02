package lmbot.backend

import java.util.UUID

import scala.jdk.CollectionConverters.*

import lmbot.backend.config.Config
import lmbot.backend.luxmed.LuxmedConfig
import org.slf4j.LoggerFactory

/** Composition root: everything is wired by hand, in one readable place (spec
  * §5.7.5 — no DI framework, no reflection).
  */
object Main:

  private val log = LoggerFactory.getLogger(getClass)

  private[backend] def requireLiveLuxmedApi(
      config: Config
  ): Either[String, Unit] =
    Either.cond(
      config.liveLuxmedApi,
      (),
      "LIVE_LUXMED_API=true is required in production"
    )

  def main(args: Array[String]): Unit =
    Config.fromEnv(System.getenv().asScala.toMap) match
      case Left(errors) =>
        errors.foreach(e => log.error(s"Configuration error: $e"))
        sys.exit(1)

      case Right(config) =>
        requireLiveLuxmedApi(config) match
          case Left(error) =>
            log.error(error)
            sys.exit(1)
          case Right(_) =>
            val application = BackendApplication.start(
              config,
              LuxmedConfig.production(
                config.luxmedAppVersion,
                UUID.randomUUID()
              ),
              AccountSeeder.noop
            )

            Runtime.getRuntime.addShutdownHook(
              Thread: () =>
                log.info("Shutting down")
                application.close()
            )
