package lmbot.shared.api

import lmbot.shared.api.Codecs.given
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*

object SecuredEndpoints:

  val sessionCookieName: String = "lmbot_session"

  private val errorOut: EndpointOutput[ApiError] =
    statusCode
      .and(jsonBody[ErrorBody])
      .map[ApiError] { case (sc, body) =>
        ApiError.fromWire(sc.code, body.code, body.message)
      } { e =>
        (StatusCode(e.status), ErrorBody(e.code, e.message))
      }

  def base[I](path: EndpointInput[I]) =
    endpoint
      .in(path)
      .errorOut(errorOut)
      .securityIn(cookie[Option[String]](sessionCookieName))
