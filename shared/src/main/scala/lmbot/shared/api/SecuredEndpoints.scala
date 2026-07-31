package lmbot.shared.api

import lmbot.shared.api.Codecs.given
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*

/** The one home for the session cookie name and the shared `errorOut` mapping,
  * both of which used to be duplicated verbatim in `AuthEndpoints`.
  */
object SecuredEndpoints:

  val sessionCookieName: String = "lmbot_session"

  /** One error output for every endpoint. `ApiError` knows its own status, so
    * this maps cleanly in both directions without a `oneOf` variant list.
    */
  val errorOut: EndpointOutput[ApiError] =
    statusCode
      .and(jsonBody[ErrorBody])
      .map[ApiError] { case (sc, body) =>
        ApiError.fromWire(sc.code, body.code, body.message)
      } { e =>
        (StatusCode(e.status), ErrorBody(e.code, e.message))
      }

  def public[I](
      path: EndpointInput[I]
  ): Endpoint[Unit, I, ApiError, Unit, Any] =
    endpoint.in(path).errorOut(errorOut)

  def base[I](
      path: EndpointInput[I]
  ): Endpoint[Option[String], I, ApiError, Unit, Any] =
    public(path).securityIn(cookie[Option[String]](sessionCookieName))
