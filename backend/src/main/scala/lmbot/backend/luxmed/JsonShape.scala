package lmbot.backend.luxmed

import java.io.PushbackReader
import java.io.StringReader

import scala.collection.immutable.SortedMap

/** A value-free JSON shape representation for conformance testing.
  *
  * Retains object keys and array/object structure but discards every scalar
  * value. Used to compare mock fixture shapes against live API responses
  * without storing or leaking real values.
  */
enum JsonShape:
  case Obj(fields: SortedMap[String, JsonShape])
  case Arr(elementShapes: Set[JsonShape])
  case Str
  case Num
  case Bool
  case Null

object JsonShape:

  /** Parse a JSON string into a shape, discarding all scalar values.
    *
    * Throws on invalid JSON syntax.
    */
  def parse(json: String): JsonShape =
    val reader = PushbackReader(StringReader(json), 2)
    val result = parseValue(reader)
    val trailing = skipWs(reader)
    if trailing >= 0 then
      throw IllegalArgumentException(
        s"Trailing content after JSON value: '${trailing.toChar}'"
      )
    result

  private def parseValue(reader: PushbackReader): JsonShape =
    val c = nextNonWs(reader)
    c match
      case '{' => parseObject(reader)
      case '[' => parseArray(reader)
      case '"' => skipString(reader); JsonShape.Str
      case 't' => skipLit(reader, "rue"); JsonShape.Bool
      case 'f' => skipLit(reader, "a" + "lse"); JsonShape.Bool
      case 'n' => skipLit(reader, "ull"); JsonShape.Null
      case '-' | '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' =>
        skipNum(reader); JsonShape.Num
      case _ =>
        throw IllegalArgumentException(
          s"Unexpected JSON character: '${c.toChar}' (code $c)"
        )

  private def parseObject(reader: PushbackReader): JsonShape =
    val fields = SortedMap.newBuilder[String, JsonShape]
    var c = nextNonWs(reader)
    if c != '}' then
      reader.unread(c)
      var done = false
      while !done do
        val key = readStr(reader)
        val colon = nextNonWs(reader)
        if colon != ':' then
          throw IllegalArgumentException(
            s"Expected ':' in object, got '${colon.toChar}'"
          )
        val value = parseValue(reader)
        fields += (key -> value)
        c = nextNonWs(reader)
        if c == '}' then done = true
        else if c != ',' then
          throw IllegalArgumentException(
            s"Expected ',' or '}' in object, got '${c.toChar}'"
          )
    JsonShape.Obj(fields.result())

  private def parseArray(reader: PushbackReader): JsonShape =
    val elements = Set.newBuilder[JsonShape]
    var c = nextNonWs(reader)
    if c != ']' then
      reader.unread(c)
      var done = false
      while !done do
        elements += parseValue(reader)
        c = nextNonWs(reader)
        if c == ']' then done = true
        else if c != ',' then
          throw IllegalArgumentException(
            s"Expected ',' or ']' in array, got '${c.toChar}'"
          )
    JsonShape.Arr(elements.result())

  private def readStr(reader: PushbackReader): String =
    val sb = StringBuilder()
    var c = reader.read()
    if c != '"' then
      throw IllegalArgumentException(
        s"Expected string start '\"' but got '${c.toChar}'"
      )
    c = reader.read()
    while c != '"' && c >= 0 do
      if c == '\\' then
        sb.append(c.toChar)
        c = reader.read()
      sb.append(c.toChar)
      c = reader.read()
    if c < 0 then
      throw IllegalArgumentException("Unterminated string: unexpected end of input")
    sb.result()

  private def skipString(reader: PushbackReader): Unit =
    var c = reader.read()
    while c != '"' && c >= 0 do
      if c == '\\' then reader.read()
      c = reader.read()

  private def skipNum(reader: PushbackReader): Unit =
    var c = reader.read()
    while c >= 0 && (Character.isDigit(
        c
      ) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-')
    do c = reader.read()
    if c >= 0 then reader.unread(c)

  private def skipLit(reader: PushbackReader, expected: String): Unit =
    expected.foreach { ch =>
      val c = reader.read()
      if c != ch then
        throw IllegalArgumentException(
          s"Expected '$ch' in literal but got '${c.toChar}'"
        )
    }

  private def nextNonWs(reader: PushbackReader): Int =
    var c = reader.read()
    while c >= 0 && (c == ' ' || c == '\t' || c == '\n' || c == '\r') do
      c = reader.read()
    c

  private def skipWs(reader: PushbackReader): Int =
    var c = reader.read()
    while c >= 0 && (c == ' ' || c == '\t' || c == '\n' || c == '\r') do
      c = reader.read()
    c
end JsonShape
