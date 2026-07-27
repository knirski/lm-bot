package lmbot.shared.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import lmbot.shared.domain.{Role, UserView}
import sttp.tapir.Schema

/** The wire body for a failure. `ApiError` itself is not serialised directly:
  * the status travels as an HTTP status code, so only code and message go in
  * the body.
  */
case class ErrorBody(code: String, message: String)

object Codecs:
  private inline def config = CodecMakerConfig.withTransientDefault(false)

  given JsonValueCodec[Role]         = JsonCodecMaker.make(config)
  given JsonValueCodec[UserView]     = JsonCodecMaker.make(config)
  given JsonValueCodec[LoginRequest] = JsonCodecMaker.make(config)
  given JsonValueCodec[ErrorBody]    = JsonCodecMaker.make(config)

  given Schema[Role]         = Schema.derivedEnumeration[Role].defaultStringBased
  given Schema[UserView]     = Schema.derived
  given Schema[LoginRequest] = Schema.derived
  given Schema[ErrorBody]    = Schema.derived
