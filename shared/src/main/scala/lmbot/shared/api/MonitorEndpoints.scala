package lmbot.shared.api

import lmbot.shared.api.Codecs.given
import lmbot.shared.domain.{MonitorDraft, MonitorId, MonitorView}
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*

/** Endpoint descriptions only — no logic, per spec §5.7.4. Both the server and
  * the browser client are derived from these.
  */
object MonitorEndpoints:

  private val securedBase = SecuredEndpoints.base("api" / "monitors")

  val create
      : Endpoint[Option[String], MonitorDraft, ApiError, MonitorView, Any] =
    securedBase.post
      .in(jsonBody[MonitorDraft])
      .out(jsonBody[MonitorView])

  val list: Endpoint[Option[String], Unit, ApiError, List[MonitorView], Any] =
    securedBase.get
      .out(jsonBody[List[MonitorView]])

  private val securedIdBase = SecuredEndpoints.base(
    "api" / "monitors" / path[MonitorId]("monitorId")
  )

  val get: Endpoint[Option[String], MonitorId, ApiError, MonitorView, Any] =
    securedIdBase.get
      .out(jsonBody[MonitorView])

  val update: Endpoint[
    Option[String],
    (MonitorId, MonitorDraft),
    ApiError,
    MonitorView,
    Any
  ] =
    securedIdBase.put
      .in(jsonBody[MonitorDraft])
      .out(jsonBody[MonitorView])

  val pause: Endpoint[Option[String], MonitorId, ApiError, Unit, Any] =
    securedIdBase.post
      .in("pause")

  val resume: Endpoint[Option[String], MonitorId, ApiError, Unit, Any] =
    securedIdBase.post
      .in("resume")

  val delete: Endpoint[Option[String], MonitorId, ApiError, Unit, Any] =
    securedIdBase.delete
