package lmbot.backend.http

import scala.concurrent.duration.FiniteDuration

import lmbot.backend.auth.AuthService
import lmbot.shared.api.AuthEndpoints
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint

/** Translates HTTP to service calls and back. No policy lives here. */
class AuthRoutes(
    auth: AuthService,
    cookieSecure: Boolean,
    sessionTtl: FiniteDuration
):

  private val loginRoute: ServerEndpoint[Any, Identity] =
    AuthEndpoints.login.serverLogicPure { req =>
      auth
        .login(req.username, req.password)
        .map { (view, token) =>
          (view, Some(SessionCookie.issue(token, cookieSecure, sessionTtl)))
        }
    }

  private val meRoute: ServerEndpoint[Any, Identity] =
    AuthEndpoints.me
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure(user => (_: Unit) => Right(user.toView))

  private val logoutRoute: ServerEndpoint[Any, Identity] =
    AuthEndpoints.logout
      .serverSecurityLogicPure(token => Right(token))
      .serverLogicPure { token => (_: Unit) =>
        auth.logout(token)
        Right(Some(SessionCookie.clear(cookieSecure)))
      }

  val endpoints: List[ServerEndpoint[Any, Identity]] =
    List(loginRoute, meRoute, logoutRoute)
