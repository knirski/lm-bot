package lmbot.shared.api

import java.time.DayOfWeek

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  JsonCodecMaker
}
import lmbot.shared.domain.*
import sttp.tapir.{Codec, CodecFormat, Schema}

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
    CodecMakerConfig
      .withTransientDefault(false)
      .withTransientEmpty(false)
      .withDiscriminatorFieldName(None)

  given JsonValueCodec[Role] = JsonCodecMaker.make(config)
  given JsonValueCodec[UserView] = JsonCodecMaker.make(config)
  given JsonValueCodec[LoginRequest] = JsonCodecMaker.make(config)
  given JsonValueCodec[ErrorBody] = JsonCodecMaker.make(config)

  // --- Opaque ID codecs (JSON numbers) ---

  given JsonValueCodec[AccountId] = JsonCodecMaker.make(config)
  given JsonValueCodec[MonitorId] = JsonCodecMaker.make(config)

  // --- Enum codecs with custom string mappings ---
  // AccountStatus and MonitorState use lowercase/snake_case wire values to
  // match the pinned test expectations, rather than the case constructor names.

  given JsonValueCodec[AccountStatus] with
    def decodeValue(in: JsonReader, default: AccountStatus): AccountStatus =
      val raw = in.readString(null)
      AccountStatus
        .fromWire(raw)
        .getOrElse(in.enumValueError(s"unexpected AccountStatus: $raw"))

    def encodeValue(x: AccountStatus, out: JsonWriter): Unit =
      out.writeVal(x.wireName)

    def nullValue: AccountStatus = null.asInstanceOf[AccountStatus]

  given JsonValueCodec[MonitorState] with
    def decodeValue(in: JsonReader, default: MonitorState): MonitorState =
      val raw = in.readString(null)
      MonitorState
        .fromWire(raw)
        .getOrElse(in.enumValueError(s"unexpected MonitorState: $raw"))

    def encodeValue(x: MonitorState, out: JsonWriter): Unit =
      out.writeVal(x.wireName)

    def nullValue: MonitorState = null.asInstanceOf[MonitorState]

  // --- Domain type codecs ---

  private def dayOfWeekDisplayName(d: DayOfWeek): String =
    val lower = d.toString.toLowerCase
    s"${lower.head.toUpper}${lower.tail}"

  given JsonValueCodec[DayOfWeek] with
    def decodeValue(in: JsonReader, default: DayOfWeek): DayOfWeek =
      val raw = in.readString(null)
      if raw == null then in.enumValueError("null DayOfWeek")
      else
        try DayOfWeek.valueOf(raw.toUpperCase)
        catch
          case _: IllegalArgumentException =>
            in.enumValueError(s"unexpected DayOfWeek: $raw")

    def encodeValue(x: DayOfWeek, out: JsonWriter): Unit =
      out.writeVal(dayOfWeekDisplayName(x))

    def nullValue: DayOfWeek = null.asInstanceOf[DayOfWeek]

  given JsonValueCodec[AccountView] = JsonCodecMaker.make(config)
  given JsonValueCodec[LinkAccountRequest] = JsonCodecMaker.make(config)
  given JsonValueCodec[NamedId] = JsonCodecMaker.make(config)
  given JsonValueCodec[MonitorDraft] = JsonCodecMaker.make(config)
  given JsonValueCodec[MonitorView] = JsonCodecMaker.make(config)
  given JsonValueCodec[DictionaryCity] = JsonCodecMaker.make(config)
  given JsonValueCodec[DictionaryService] = JsonCodecMaker.make(config)
  given JsonValueCodec[DictionaryFacility] = JsonCodecMaker.make(config)
  given JsonValueCodec[DictionaryDoctor] = JsonCodecMaker.make(config)
  given JsonValueCodec[FacilitiesDoctorsResponse] = JsonCodecMaker.make(config)

  // --- List codecs (jsoniter-scala does not auto-derive these) ---
  // Explicit names avoid Scala 3's synthetic name collision when multiple
  // `given` instances share the same erased outer type (JsonValueCodec[List[_]]).

  given listAccountViewCodec: JsonValueCodec[List[AccountView]] =
    JsonCodecMaker.make(config)
  given listMonitorViewCodec: JsonValueCodec[List[MonitorView]] =
    JsonCodecMaker.make(config)
  given listDictionaryCityCodec: JsonValueCodec[List[DictionaryCity]] =
    JsonCodecMaker.make(config)
  given listDictionaryServiceCodec: JsonValueCodec[List[
    DictionaryService
  ]] = JsonCodecMaker.make(config)
  given listNamedIdCodec: JsonValueCodec[List[NamedId]] =
    JsonCodecMaker.make(config)

  // --- Path codecs for opaque IDs ---

  given Codec[String, AccountId, CodecFormat.TextPlain] =
    Codec.long.map(AccountId(_))(_.value)

  given Codec[String, MonitorId, CodecFormat.TextPlain] =
    Codec.long.map(MonitorId(_))(_.value)

  // --- Tapir schemas ---

  given Schema[Role] = Schema.derivedEnumeration[Role].defaultStringBased
  given Schema[UserView] = Schema.derived
  given Schema[LoginRequest] = Schema.derived
  given Schema[ErrorBody] = Schema.derived

  given Schema[AccountId] =
    Schema.schemaForLong.map(id => Some(AccountId(id)))(_.value)
  given Schema[MonitorId] =
    Schema.schemaForLong.map(id => Some(MonitorId(id)))(_.value)

  given Schema[AccountStatus] =
    Schema.derivedEnumeration[AccountStatus](
      encode = Some((status: AccountStatus) => status.wireName)
    )
  given Schema[MonitorState] =
    Schema.derivedEnumeration[MonitorState](
      encode = Some((state: MonitorState) => state.wireName)
    )

  given Schema[AccountView] = Schema.derived
  given Schema[LinkAccountRequest] = Schema.derived
  given Schema[NamedId] = Schema.derived

  // java.time types used in MonitorDraft / MonitorView
  given Schema[DayOfWeek] =
    Schema.schemaForString.map { s =>
      try Some(DayOfWeek.valueOf(s.toUpperCase))
      catch case _: IllegalArgumentException => None
    }(dayOfWeekDisplayName(_))
  // LocalDate and LocalTime have built-in Tapir schemas

  given Schema[MonitorDraft] = Schema.derived
  given Schema[MonitorView] = Schema.derived
  given Schema[DictionaryCity] = Schema.derived
  given Schema[DictionaryService] = Schema.derived
  given Schema[DictionaryFacility] = Schema.derived
  given Schema[DictionaryDoctor] = Schema.derived
  given Schema[FacilitiesDoctorsResponse] = Schema.derived
