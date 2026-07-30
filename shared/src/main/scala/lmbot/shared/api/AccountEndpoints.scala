package lmbot.shared.api

import lmbot.shared.api.Codecs.given
import lmbot.shared.domain.{AccountId, AccountView, LinkAccountRequest}
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*

/** Endpoint descriptions only — no logic, per spec §5.7.4. Both the server and
  * the browser client are derived from these.
  */
object AccountEndpoints:

  private val errorOut: EndpointOutput[ApiError] =
    statusCode
      .and(jsonBody[ErrorBody])
      .map[ApiError] { case (sc, body) =>
        ApiError.fromWire(sc.code, body.code, body.message)
      } { e =>
        (StatusCode(e.status), ErrorBody(e.code, e.message))
      }

  private val sessionCookieName: String = "lmbot_session"

  private val securedBase =
    endpoint
      .in("api" / "accounts")
      .errorOut(errorOut)
      .securityIn(cookie[Option[String]](sessionCookieName))

  val create: Endpoint[Option[
    String
  ], LinkAccountRequest, ApiError, AccountView, Any] =
    securedBase.post
      .in(jsonBody[LinkAccountRequest])
      .out(jsonBody[AccountView])

  val list: Endpoint[Option[String], Unit, ApiError, List[AccountView], Any] =
    securedBase.get
      .out(jsonBody[List[AccountView]])

  val delete: Endpoint[Option[String], AccountId, ApiError, Unit, Any] =
    securedBase.delete
      .in(path[AccountId]("accountId"))
