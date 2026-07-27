package lmbot.frontend.api

import lmbot.shared.api.{ApiError, AuthEndpoints, LoginRequest}
import lmbot.shared.domain.UserView
import sttp.client3.FetchBackend
import sttp.model.Uri
import sttp.tapir.client.sttp.SttpClientInterpreter

import scala.concurrent.{ExecutionContext, Future}

/** Derived from the shared endpoint descriptions, so the client cannot drift
  * from the server (spec §5.1).
  */
class ApiClient(baseUri: Uri):

  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

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

  /** Calls a tapir-derived client function and converts transport failures to
    * ApiError values, so callers handle exactly one error type.
    */
  private def call[T](f: => Future[Either[ApiError, T]]): Future[Either[ApiError, T]] =
    f.recover: e =>
      Left(ApiError.Unexpected(Option(e.getMessage).getOrElse("network request failed")))

  def login(req: LoginRequest): Future[Either[ApiError, UserView]] =
    call(loginFn(req).map(_.map((view, _) => view)))

  /** The session cookie is `HttpOnly`, so page scripts cannot read it. We pass
    * `None` as the security input and let the browser attach the real cookie
    * to the request — which it does, because the API is same-origin.
    */
  def me(): Future[Either[ApiError, UserView]] = call(meFn(None)(()))

  def logout(): Future[Either[ApiError, Unit]] = call(logoutFn(None)(()).map(_ => Right(())))
