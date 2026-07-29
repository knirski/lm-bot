package lmbot.backend.luxmed.model

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  JsonCodecMaker
}
import lmbot.backend.config.Secret
import java.time.*
import java.time.format.{DateTimeFormatter, DateTimeParseException}
import java.util.UUID

/** Wire codecs for all Luxmed wire models.
  *
  * OAuthTokens has a config that maps to snake_case wire names (access_token,
  * expires_in, refresh_token, token_type) while every other Luxmed model uses
  * camelCase. All NewPortal models use `newPortalConfig` which skips unexpected
  * upstream fields.
  *
  * LuxmedDateTime uses a custom decoder that tries ISO_OFFSET_DATE_TIME first
  * and falls back to ISO_LOCAL_DATE_TIME, normalising both to Europe/Warsaw.
  *
  * ServiceVariant uses a custom codec because jsoniter does not support
  * recursive types in derived codecs.
  *
  * Opaque ID codecs (DoctorId, FacilityId, etc.) are defined in their companion
  * objects and picked up automatically by the derived codecs.
  */
object WireCodecs:

  // -- Configurations --

  private inline def newPortalConfig: CodecMakerConfig =
    CodecMakerConfig
      .withSkipUnexpectedFields(true)
      .withTransientDefault(false)

  private inline def oauthConfig: CodecMakerConfig =
    CodecMakerConfig
      .withSkipUnexpectedFields(true)
      .withFieldNameMapper {
        case "accessToken"  => "access_token"
        case "expiresIn"    => "expires_in"
        case "refreshToken" => "refresh_token"
        case "tokenType"    => "token_type"
        case other          => other
      }

  // -- Secret codec --

  given JsonValueCodec[Secret] with
    def decodeValue(in: JsonReader, default: Secret): Secret =
      Secret(in.readString(null))
    def encodeValue(x: Secret, out: JsonWriter): Unit =
      out.writeVal(x.value)
    def nullValue: Secret = null.asInstanceOf[Secret]

  // -- LuxmedDateTime codec (handles dual datetime format) --

  given JsonValueCodec[LuxmedDateTime] with
    private val warsawZone = ZoneId.of("Europe/Warsaw")

    def decodeValue(in: JsonReader, default: LuxmedDateTime): LuxmedDateTime =
      val raw = in.readString(null)
      if raw == null then nullValue
      else
        val zdt =
          try ZonedDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
          catch
            case _: DateTimeParseException =>
              val ldt =
                LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
              ldt.atZone(warsawZone)
        LuxmedDateTime(zdt.withZoneSameInstant(warsawZone))

    def encodeValue(x: LuxmedDateTime, out: JsonWriter): Unit =
      out.writeVal(x.value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))

    def nullValue: LuxmedDateTime = null.asInstanceOf[LuxmedDateTime]

  // -- LocalDate codec (ISO_LOCAL_DATE) --

  given JsonValueCodec[LocalDate] with
    def decodeValue(in: JsonReader, default: LocalDate): LocalDate =
      LocalDate.parse(in.readString(null))
    def encodeValue(x: LocalDate, out: JsonWriter): Unit =
      out.writeVal(x.toString)
    def nullValue: LocalDate = null.asInstanceOf[LocalDate]

  // -- LocalTime codec (HH:mm:ss) --

  given JsonValueCodec[LocalTime] with
    def decodeValue(in: JsonReader, default: LocalTime): LocalTime =
      LocalTime.parse(in.readString(null))
    def encodeValue(x: LocalTime, out: JsonWriter): Unit =
      out.writeVal(x.toString)
    def nullValue: LocalTime = null.asInstanceOf[LocalTime]

  // -- UUID codec --

  given JsonValueCodec[UUID] with
    def decodeValue(in: JsonReader, default: UUID): UUID =
      UUID.fromString(in.readString(null))
    def encodeValue(x: UUID, out: JsonWriter): Unit =
      out.writeVal(x.toString)
    def nullValue: UUID = null.asInstanceOf[UUID]

  // -- OAuthTokens derived codec (snake_case wire names) --
  // TokenType has its own codec in its companion object, which is picked up
  // automatically by the derived codec.

  given JsonValueCodec[OAuthTokens] = JsonCodecMaker.make(oauthConfig)

  // -- Custom ServiceVariant codec (recursive children) --

  given JsonValueCodec[ServiceVariant] with
    def decodeValue(in: JsonReader, default: ServiceVariant): ServiceVariant =
      if in.isNextToken('n') then nullValue
      else
        var id: ServiceVariantId = ServiceVariantId(0L)
        var name: String = ""
        var expanded: Boolean = false
        var children: List[ServiceVariant] = Nil
        var isTelemedicine: Boolean = false
        var paymentType: Long = 0L
        while !in.isNextToken('}') do
          in.readKeyAsString() match
            case "id"             => id = ServiceVariantId(in.readLong())
            case "name"           => name = in.readString(null)
            case "expanded"       => expanded = in.readBoolean()
            case "children"       => children = readChildrenArray(in)
            case "isTelemedicine" => isTelemedicine = in.readBoolean()
            case "paymentType"    => paymentType = in.readLong()
            case _                => in.skip()
        ServiceVariant(
          id,
          name,
          expanded,
          children,
          isTelemedicine,
          paymentType
        )

    def encodeValue(x: ServiceVariant, out: JsonWriter): Unit =
      out.writeObjectStart()
      out.writeKey("id")
      out.writeVal(x.id.value)
      out.writeKey("name")
      out.writeVal(x.name)
      out.writeKey("expanded")
      out.writeVal(x.expanded)
      out.writeKey("children")
      out.writeArrayStart()
      x.children.foreach(encodeValue(_, out))
      out.writeArrayEnd()
      out.writeKey("isTelemedicine")
      out.writeVal(x.isTelemedicine)
      out.writeKey("paymentType")
      out.writeVal(x.paymentType)
      out.writeObjectEnd()

    def nullValue: ServiceVariant = null.asInstanceOf[ServiceVariant]

  private def readChildrenArray(in: JsonReader): List[ServiceVariant] =
    val codec = summon[JsonValueCodec[ServiceVariant]]
    if in.isNextToken('n') then Nil
    else
      val builder = List.newBuilder[ServiceVariant]
      while !in.isNextToken(']') do
        builder += codec.decodeValue(in, null.asInstanceOf[ServiceVariant])
      builder.result()

  // -- Derived NewPortal codecs (camelCase, skip unexpected fields) --
  // Opaque ID codecs are in their companion objects and auto-resolved.

  given JsonValueCodec[City] = JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[Doctor] = JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[Facility] = JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[FacilitiesAndDoctors] =
    JsonCodecMaker.make(newPortalConfig)

  given JsonValueCodec[PreparationItem] = JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[AdditionalData] = JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[Term] = JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[TermsForDay] = JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[TermsForService] = JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[TermsResponse] = JsonCodecMaker.make(newPortalConfig)

  given JsonValueCodec[Valuation] = JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[RelatedVisit] = JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[LockTermResponseValue] =
    JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[LockTermResponse] = JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[LockTermRequest] = JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[ConfirmValue] = JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[ConfirmResponse] = JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[ConfirmRequest] = JsonCodecMaker.make(newPortalConfig)
  given JsonValueCodec[XsrfToken] = JsonCodecMaker.make(newPortalConfig)
