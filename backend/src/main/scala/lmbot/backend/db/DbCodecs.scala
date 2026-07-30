package lmbot.backend.db

import java.lang.reflect.Array as ReflectArray
import java.sql.ResultSet
import java.sql.Types

import scala.IArray

import com.augustnagro.magnum.DbCodec

/** Custom Magnum `DbCodec` instances for PostgreSQL array and date/time types.
  *
  * Magnum's auto-derivation for `PostgresDbType` handles `OffsetDateTime` but
  * not `LocalDate`/`LocalTime` or `List[Long]`/`List[String]` for arrays. These
  * codecs fill those gaps using the PostgreSQL JDBC driver's native support.
  */

private def parseArrayText(value: String): List[String] =
  val body = value.stripPrefix("{").stripSuffix("}")
  if body.isEmpty then Nil
  else
    val values = scala.collection.mutable.ListBuffer.empty[String]
    val current = new StringBuilder
    var quoted = false
    var escaped = false
    body.foreach {
      case '\\' if quoted && !escaped => escaped = true
      case '\\' if quoted && escaped  =>
        current.append('\\')
        escaped = false
      case '"' if !escaped => quoted = !quoted
      case ',' if !quoted  =>
        values += current.result()
        current.clear()
      case char =>
        current.append(if escaped && char == '\\' then '\\' else char)
        escaped = false
    }
    values += current.result()
    values.toList

private def readArrayText(rs: ResultSet, pos: Int): List[String] =
  try
    val arr = rs.getArray(pos)
    if arr == null then Nil
    else
      val raw = arr.getArray
      (0 until ReflectArray.getLength(raw)).toList.map { index =>
        ReflectArray.get(raw, index).toString
      }
  catch
    case _: java.sql.SQLException =>
      Option(rs.getString(pos)).map(parseArrayText).getOrElse(Nil)

given longListCodec: DbCodec[List[Long]] with
  def queryRepr: String = "?"
  def cols: IArray[Int] = IArray(Types.ARRAY)
  def readSingle(rs: java.sql.ResultSet, pos: Int): List[Long] =
    readArrayText(rs, pos).map(_.toLong)
  def writeSingle(
      value: List[Long],
      ps: java.sql.PreparedStatement,
      pos: Int
  ): Unit =
    val conn = ps.getConnection
    val jdbcArr = conn.createArrayOf("bigint", value.map(Long.box).toArray)
    ps.setArray(pos, jdbcArr)

given stringListCodec: DbCodec[List[String]] with
  def queryRepr: String = "?"
  def cols: IArray[Int] = IArray(Types.ARRAY)
  def readSingle(rs: java.sql.ResultSet, pos: Int): List[String] =
    readArrayText(rs, pos)
  def writeSingle(
      value: List[String],
      ps: java.sql.PreparedStatement,
      pos: Int
  ): Unit =
    val conn = ps.getConnection
    val jdbcArr = conn.createArrayOf("text", value.toArray)
    ps.setArray(pos, jdbcArr)
