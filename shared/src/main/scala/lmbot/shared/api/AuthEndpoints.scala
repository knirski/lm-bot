package lmbot.shared.api

import lmbot.shared.api.Codecs.given
import lmbot.shared.domain.UserView
import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*

/** Endpoint descriptions only — no logic, per spec §5.7.4. Both the server
  * (Task 8) and the browser client (Task 9) are derived from these.
  */
object AuthEndpoints:

  /** Re-exported from `SecuredEndpoints`, the one home for this constant. */
  val sessionCookieName: String = SecuredEndpoints.sessionCookieName

  private val base = SecuredEndpoints.public("api" / "auth")

  /** The session cookie is `HttpOnly`, so browser JS cannot read it. The client
    * therefore always passes `None` here and lets the browser attach the real
    * cookie itself; the server reads whatever actually arrived.
    */
  private val securedBase = SecuredEndpoints.base("api" / "auth")

  /** The session cookie is declared with `setCookieOpt`, not `setCookie`.
    *
    * `setCookie` is `setCookieOpt` plus a decode that *fails* when the header
    * is absent, and `Set-Cookie` is a forbidden response header for browser
    * JavaScript — the Fetch spec never exposes it. Since the browser client is
    * derived from this very endpoint, `setCookie` would make every successful
    * login undecodable on the client (`DecodeResult.Missing`) even though the
    * request succeeded and the browser stored the cookie. The optional form
    * decodes to `None` in the browser and `Some(...)` on the server, which is
    * exactly the asymmetry reality has.
    */
  val login: Endpoint[
    Unit,
    LoginRequest,
    ApiError,
    (UserView, Option[CookieValueWithMeta]),
    Any
  ] =
    base.post
      .in("login")
      .in(jsonBody[LoginRequest])
      .out(jsonBody[UserView])
      .out(setCookieOpt(sessionCookieName))

  val me: Endpoint[Option[String], Unit, ApiError, UserView, Any] =
    securedBase.get
      .in("me")
      .out(jsonBody[UserView])

  val logout: Endpoint[Option[String], Unit, ApiError, Option[
    CookieValueWithMeta
  ], Any] =
    securedBase.post
      .in("logout")
      .out(setCookieOpt(sessionCookieName))
