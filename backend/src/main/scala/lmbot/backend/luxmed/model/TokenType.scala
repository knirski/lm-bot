package lmbot.backend.luxmed.model

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}

/** The type of OAuth token returned by Luxmed's password and refresh grants.
  *
  * Currently only "bearer" is observed on the wire. Unknown values are
  * preserved via [[Custom]] so they round-trip without data loss — the decoder
  * never silently discards a wire value.
  */
enum TokenType(val wireValue: String):
  case Bearer extends TokenType("bearer")

  /** An unrecognised token type. The raw wire value is retained so re-encoding
    * produces the original string, even if the application has no specific
    * logic for it.
    */
  case Custom(raw: String) extends TokenType(raw)

object TokenType:

  private val knownWireValues: Set[String] = Set("bearer")

  def fromWire(s: String): TokenType =
    if knownWireValues.contains(s) then Bearer
    else Custom(s)

  given JsonValueCodec[TokenType] with
    def decodeValue(in: JsonReader, default: TokenType): TokenType =
      TokenType.fromWire(in.readString(null))
    def encodeValue(x: TokenType, out: JsonWriter): Unit =
      out.writeVal(x.wireValue)
    def nullValue: TokenType = null.asInstanceOf[TokenType]
