package lmbot.shared.api

import lmbot.shared.api.Codecs.given
import lmbot.shared.domain.{
  AccountId,
  DictionaryCity,
  DictionaryService,
  FacilitiesDoctorsResponse
}
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*

/** Endpoint descriptions only — no logic, per spec §5.7.4. Both the server and
  * the browser client are derived from these.
  */
object DictionaryEndpoints:

  private val errorOut: EndpointOutput[ApiError] =
    statusCode
      .and(jsonBody[ErrorBody])
      .map[ApiError] { case (sc, body) =>
        ApiError.fromWire(sc.code, body.code, body.message)
      } { e =>
        (StatusCode(e.status), ErrorBody(e.code, e.message))
      }

  private val sessionCookieName: String = "lmbot_session"

  private val securedDictionaryBase =
    endpoint
      .in("api" / "accounts" / path[AccountId]("accountId") / "dictionaries")
      .errorOut(errorOut)
      .securityIn(cookie[Option[String]](sessionCookieName))

  val cities: Endpoint[Option[String], AccountId, ApiError, List[
    DictionaryCity
  ], Any] =
    securedDictionaryBase.get
      .in("cities")
      .out(jsonBody[List[DictionaryCity]])

  val services: Endpoint[Option[String], AccountId, ApiError, List[
    DictionaryService
  ], Any] =
    securedDictionaryBase.get
      .in("services")
      .out(jsonBody[List[DictionaryService]])

  val facilitiesDoctors: Endpoint[
    Option[String],
    (AccountId, Long, Long),
    ApiError,
    FacilitiesDoctorsResponse,
    Any
  ] =
    securedDictionaryBase.get
      .in("facilities-doctors")
      .in(query[Long]("cityId"))
      .in(query[Long]("serviceId"))
      .out(jsonBody[FacilitiesDoctorsResponse])
