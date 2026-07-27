package lmbot.shared.api

import lmbot.shared.api.Codecs.given
import lmbot.shared.domain.UserView
import sttp.model.StatusCode
import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*

/** Endpoint descriptions only — no logic, per spec §5.7.4. Both the server
  * (Task 8) and the browser client (Task 9) are derived from these.
  */
object AuthEndpoints:

  val sessionCookieName: String = "lmbot_session"

  /** One error output for every endpoint. `ApiError` knows its own status, so
    * this maps cleanly in both directions without a `oneOf` variant list.
    */
  private val errorOut: EndpointOutput[ApiError] =
    statusCode
      .and(jsonBody[ErrorBody])
      .map[ApiError] { case (sc, body) => ApiError.fromWire(sc.code, body.code, body.message) } { e =>
        (StatusCode(e.status), ErrorBody(e.code, e.message))
      }

  private val base = endpoint.in("api" / "auth").errorOut(errorOut)

  /** The session cookie is `HttpOnly`, so browser JS cannot read it. The client
    * therefore always passes `None` here and lets the browser attach the real
    * cookie itself; the server reads whatever actually arrived.
    */
  private val securedBase = base.securityIn(cookie[Option[String]](sessionCookieName))

  val login: Endpoint[Unit, LoginRequest, ApiError, (UserView, CookieValueWithMeta), Any] =
    base.post
      .in("login")
      .in(jsonBody[LoginRequest])
      .out(jsonBody[UserView])
      .out(setCookie(sessionCookieName))

  val me: Endpoint[Option[String], Unit, ApiError, UserView, Any] =
    securedBase.get
      .in("me")
      .out(jsonBody[UserView])

  val logout: Endpoint[Option[String], Unit, ApiError, CookieValueWithMeta, Any] =
    securedBase.post
      .in("logout")
      .out(setCookie(sessionCookieName))
