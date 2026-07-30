package lmbot.backend.db

import java.lang.reflect.Array as ReflectArray
import java.sql.{PreparedStatement, ResultSet, SQLException}
import java.sql.Types

import scala.IArray
import scala.collection.mutable.ListBuffer

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
    val values = ListBuffer.empty[String]
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
    Option(arr)
      .map { array =>
        val raw = array.getArray
        (0 until ReflectArray.getLength(raw)).toList.map { index =>
          ReflectArray.get(raw, index).toString
        }
      }
      .getOrElse(Nil)
  catch
    case _: SQLException =>
      Option(rs.getString(pos)).map(parseArrayText).getOrElse(Nil)

private def arrayCodec[A](
    sqlType: String,
    decode: String => A,
    encode: A => AnyRef
): DbCodec[List[A]] =
  new DbCodec[List[A]]:
    def queryRepr: String = "?"
    def cols: IArray[Int] = IArray(Types.ARRAY)
    def readSingle(rs: ResultSet, pos: Int): List[A] =
      readArrayText(rs, pos).map(decode)
    def writeSingle(
        value: List[A],
        ps: PreparedStatement,
        pos: Int
    ): Unit =
      val conn = ps.getConnection
      val jdbcArr = conn.createArrayOf(sqlType, value.map(encode).toArray)
      ps.setArray(pos, jdbcArr)

given longListCodec: DbCodec[List[Long]] =
  arrayCodec("bigint", _.toLong, Long.box)

given stringListCodec: DbCodec[List[String]] =
  arrayCodec("text", identity, identity)
