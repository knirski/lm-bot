package lmbot.frontend.api

import gears.async.Async
import lmbot.frontend.bridge.Bridge
import lmbot.shared.api.{ApiError, AuthEndpoints, LoginRequest}
import lmbot.shared.domain.UserView
import org.scalajs.dom
import sttp.client3.FetchBackend
import sttp.model.Uri
import sttp.tapir.client.sttp.SttpClientInterpreter

/** Derived from the shared endpoint descriptions, so the client cannot drift
  * from the server (spec §5.1).
  *
  * Every method takes `(using Async)` and returns `Either[ApiError, T]`. The
  * foreign Future that sttp's Scala.js backend returns is never named here —
  * `Bridge` is the only place allowed to mention it (spec §5.7.1).
  */
class ApiClient(baseUri: Uri):

  // Lazy so that merely constructing an ApiClient touches no browser API —
  // the pure `update` tests build one and never make a call.
  private lazy val backend     = FetchBackend()
  private lazy val interpreter = SttpClientInterpreter()

  private lazy val loginFn =
    interpreter.toClientThrowDecodeFailures(AuthEndpoints.login, Some(baseUri), backend)
  private lazy val meFn =
    interpreter.toSecureClientThrowDecodeFailures(AuthEndpoints.me, Some(baseUri), backend)
  private lazy val logoutFn =
    interpreter.toSecureClientThrowDecodeFailures(AuthEndpoints.logout, Some(baseUri), backend)

  def login(req: LoginRequest)(using Async): Either[ApiError, UserView] =
    // The cookie output is always `None` here: the browser stores the cookie but
    // never exposes `Set-Cookie` to scripts (see AuthEndpoints.login).
    Bridge.awaitEither(loginFn(req))(transportFailure).map((view, _) => view)

  /** The session cookie is `HttpOnly`, so page scripts cannot read it. We pass
    * `None` as the security input and let the browser attach the real cookie
    * to the request — which it does, because the API is same-origin.
    */
  def me()(using Async): Either[ApiError, UserView] =
    Bridge.awaitEither(meFn(None)(()))(transportFailure)

  def logout()(using Async): Either[ApiError, Unit] =
    Bridge.awaitEither(logoutFn(None)(()))(transportFailure).map(_ => ())

  /** A transport or decode failure becomes an error value, in the same channel
    * as a server-side error, so callers have exactly one thing to handle.
    *
    * It is also logged. A decode failure here means client and server disagree
    * about a contract they share by construction — a build-level bug, not a user
    * error — and staying silent lets it masquerade as "wrong password", which is
    * precisely how the `setCookie` defect survived a full debugging round.
    */
  private def transportFailure(err: Throwable): ApiError =
    val message = Option(err.getMessage).getOrElse("network request failed")
    dom.console.error(s"lm-bot: API call failed: $message")
    ApiError.Unexpected(message)
