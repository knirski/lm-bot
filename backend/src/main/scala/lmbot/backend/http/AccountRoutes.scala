package lmbot.backend.http

import lmbot.backend.account.AccountService
import lmbot.backend.auth.AuthService
import lmbot.shared.api.AccountEndpoints
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint

/** Translates HTTP to service calls and back. No policy lives here. */
class AccountRoutes(auth: AuthService, accounts: AccountService):

  private val createRoute: ServerEndpoint[Any, Identity] =
    AccountEndpoints.create
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure { user => request =>
        AsyncBoundary.run(accounts.link(user.id, request))
      }

  private val listRoute: ServerEndpoint[Any, Identity] =
    AccountEndpoints.list
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure(user => (_: Unit) => accounts.list(user.id))

  private val deleteRoute: ServerEndpoint[Any, Identity] =
    AccountEndpoints.delete
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure(user => accountId => accounts.delete(user.id, accountId))

  val endpoints: List[ServerEndpoint[Any, Identity]] =
    List(createRoute, listRoute, deleteRoute)
