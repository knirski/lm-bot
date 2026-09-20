package lmbot.shared.api

import lmbot.shared.api.Codecs.given
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*

/** Endpoint descriptions only — no logic, per spec §5.7.4. Both the server and
  * the browser client are derived from these.
  */
object SettingsEndpoints:

  private val securedBase =
    SecuredEndpoints.base("api" / "settings" / "telegram")

  val status
      : Endpoint[Option[String], Unit, ApiError, TelegramSettingsView, Any] =
    securedBase.get
      .out(jsonBody[TelegramSettingsView])

  val linkCode
      : Endpoint[Option[String], Unit, ApiError, TelegramLinkCodeView, Any] =
    securedBase.post
      .in("link-code")
      .out(jsonBody[TelegramLinkCodeView])

  val unlink: Endpoint[Option[String], Unit, ApiError, Unit, Any] =
    securedBase.delete
