package lmbot.backend.dev

import java.util.UUID

import lmbot.backend.config.Config
import lmbot.backend.luxmed.LuxmedConfig
import lmbot.backend.{AccountSeeder, ApplicationLifecycle}

/** Development composition support for the unified backend launcher. */
object DevMain:

  private[backend] def luxmedConfig(
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

  private[backend] def accountSeeder(
      mock: Option[MockLuxmedServer]
  ): AccountSeeder =
    mock.fold(AccountSeeder.noop)(_ => MockAccountSeed)

  private[backend] def installShutdownHook(
      application: AutoCloseable,
      mock: Option[AutoCloseable],
      register: Thread => Unit
  ): Unit =
    ApplicationLifecycle.installShutdownHook(
      application :: mock.toList,
      register
    )
