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
  /** `discriminatorFieldName = None` is what makes a parameterless Scala 3 enum
    * serialise as a bare JSON string rather than `{"type":"Admin"}`.
    *
    * Without it the codec and the Tapir `Schema` disagree: the schema below
    * declares `Role` string-based, so the generated description would claim
    * `"Admin"` while the wire carried an object. Both sides of this API share
    * the codec, so nothing breaks today — but the declared contract would be a
    * lie, which defeats the point of §5.1's single shared definition and would
    * mislead any non-Scala consumer.
    */
  private inline def config =
    CodecMakerConfig.withTransientDefault(false).withDiscriminatorFieldName(None)

  given JsonValueCodec[Role]         = JsonCodecMaker.make(config)
  given JsonValueCodec[UserView]     = JsonCodecMaker.make(config)
  given JsonValueCodec[LoginRequest] = JsonCodecMaker.make(config)
  given JsonValueCodec[ErrorBody]    = JsonCodecMaker.make(config)

  given Schema[Role]         = Schema.derivedEnumeration[Role].defaultStringBased
  given Schema[UserView]     = Schema.derived
  given Schema[LoginRequest] = Schema.derived
  given Schema[ErrorBody]    = Schema.derived
