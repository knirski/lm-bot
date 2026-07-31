package lmbot.backend.db

import java.lang.reflect.Array as ReflectArray
import java.sql.Types
import java.sql.{PreparedStatement, ResultSet, SQLException}

import scala.IArray
import scala.collection.mutable.ListBuffer

import com.augustnagro.magnum.DbCodec

/** Custom Magnum `DbCodec` instances for `List[Long]`/`List[String]` Postgres
  * array columns, which Magnum's auto-derivation for `PostgresDbType` does not
  * cover.
  *
  * `readArrayText` prefers `rs.getArray`, the JDBC-native path against a real
  * PostgreSQL connection. It falls back to `rs.getString` plus a hand-rolled
  * parser under Memgres (the default test backend), whose driver does not
  * implement `getArray`. `parseArrayText` implements PostgreSQL's array text
  * output format: comma-separated, double-quoted elements with `\`-escaping,
  * and no support for an element-level `NULL` (a `{NULL}` element decodes to
  * the literal string `"NULL"`, which this app never writes).
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
        current.append(char)
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
