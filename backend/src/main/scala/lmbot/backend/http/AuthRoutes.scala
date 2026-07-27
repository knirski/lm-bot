package lmbot.backend.http

import lmbot.backend.auth.AuthService
import lmbot.shared.api.AuthEndpoints
import sttp.tapir.server.ServerEndpoint

import java.time.Duration

/** Translates HTTP to service calls and back. No policy lives here. */
class AuthRoutes(auth: AuthService, cookieSecure: Boolean, sessionTtl: Duration):

  private val loginRoute: ServerEndpoint[Any, sttp.shared.Identity] =
    AuthEndpoints.login.serverLogicPure { req =>
      auth
        .login(req.username, req.password)
        .map { (view, token) => (view, Some(SessionCookie.issue(token, cookieSecure, sessionTtl))) }
    }

  private val meRoute: ServerEndpoint[Any, sttp.shared.Identity] =
    AuthEndpoints.me
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure(user => (_: Unit) => Right(user.toView))

  private val logoutRoute: ServerEndpoint[Any, sttp.shared.Identity] =
    AuthEndpoints.logout
      .serverSecurityLogicPure(token => Right(token))
      .serverLogicPure { token => (_: Unit) =>
        auth.logout(token)
        Right(Some(SessionCookie.clear(cookieSecure)))
      }

  val endpoints: List[ServerEndpoint[Any, sttp.shared.Identity]] = List(loginRoute, meRoute, logoutRoute)
