package lmbot.backend.luxmed.model

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter
}

/** The type of OAuth token returned by Luxmed's password and refresh grants.
  *
  * Currently only "bearer" is observed on the wire, but modelling it as a
  * closed enum lets the compiler (not the next developer) decide what happens
  * when a new value appears.
  */
enum TokenType(val wireValue: String):
  case Bearer extends TokenType("bearer")

  /** Parse from the wire string. Unknown values are still returned as parsed so
    * they round-trip; they are not rejected at decode time.
    */
  def wireName: String = wireValue

object TokenType:

  private val valuesByWire =
    TokenType.values.map(tt => tt.wireValue -> tt).toMap

  def fromWire(s: String): TokenType =
    valuesByWire.getOrElse(s, Bearer)

  given JsonValueCodec[TokenType] with
    def decodeValue(in: JsonReader, default: TokenType): TokenType =
      TokenType.fromWire(in.readString(null))
    def encodeValue(x: TokenType, out: JsonWriter): Unit =
      out.writeVal(x.wireValue)
    def nullValue: TokenType = null.asInstanceOf[TokenType]
