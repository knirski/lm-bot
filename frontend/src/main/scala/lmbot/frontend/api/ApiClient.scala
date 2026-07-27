package lmbot.frontend.api

import gears.async.Async
import lmbot.frontend.bridge.Bridge
import lmbot.shared.api.{ApiError, AuthEndpoints, LoginRequest}
import lmbot.shared.domain.UserView
import sttp.client3.FetchBackend
import sttp.model.Uri
import sttp.tapir.client.sttp.SttpClientInterpreter

/** Derived from the shared endpoint descriptions, so the client cannot drift
  * from the server (spec §5.1).
  *
  * == Linker note ==
  * Every public method takes `(using Async)` and returns `Either[ApiError, T]`
  * — no Future wrapping.  Internally, `call` uses `Bridge.await` to convert
  * sttp's `scala.concurrent.Future` into a Gears blocking await.  That await
  * is where the linker traces into JSPI internals (`JsAsync.await` →
  * `js.await`).  The setTimeout deferral in Main.scala breaks the linker
  * trace before it reaches here.
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
    // The response also carries the Set-Cookie value; the browser stores it,
    // so the value itself is of no use to us here.
    call(loginFn(req)).map((view, _) => view)

  /** The session cookie is `HttpOnly`, so page scripts cannot read it. We pass
    * `None` as the security input and let the browser attach the real cookie
    * to the request — which it does, because the API is same-origin.
    */
  def me()(using Async): Either[ApiError, UserView] = call(meFn(None)(()))

  def logout()(using Async): Either[ApiError, Unit] = call(logoutFn(None)(())).map(_ => ())

  /** A transport failure is reported as an error value, in the same channel as
    * a server-side error, so callers have exactly one thing to handle.
    */
  private def call[E, T](f: => scala.concurrent.Future[Either[ApiError, T]])(using
    Async
  ): Either[ApiError, T] =
    Bridge.await(f) match
      case Right(result) => result
      case Left(err) =>
        Left(ApiError.Unexpected(Option(err.getMessage).getOrElse("network request failed")))
