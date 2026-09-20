package lmbot.backend.http

import lmbot.backend.auth.AuthService
import lmbot.backend.notify.TelegramLinkService
import lmbot.shared.api.SettingsEndpoints
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint

/** Translates HTTP to `TelegramLinkService`. No policy lives here: status, code
  * creation, expiry, and consumption are the service's, and the authenticated
  * user is the only scope it can act on.
  */
class SettingsRoutes(auth: AuthService, telegram: TelegramLinkService):

  private val statusRoute: ServerEndpoint[Any, Identity] =
    SettingsEndpoints.status
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure(user => (_: Unit) => telegram.status(user.id))

  private val linkCodeRoute: ServerEndpoint[Any, Identity] =
    SettingsEndpoints.linkCode
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure(user => (_: Unit) => telegram.createCode(user.id))

  private val unlinkRoute: ServerEndpoint[Any, Identity] =
    SettingsEndpoints.unlink
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure(user => (_: Unit) => telegram.unlink(user.id))

  val endpoints: List[ServerEndpoint[Any, Identity]] =
    List(statusRoute, linkCodeRoute, unlinkRoute)
