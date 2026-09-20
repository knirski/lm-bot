package lmbot.backend.db

import java.lang.reflect.Array as ReflectArray
import java.sql.Types
import java.sql.{PreparedStatement, ResultSet}
import java.time.LocalDateTime

import scala.IArray

import com.augustnagro.magnum.DbCodec

/** Custom Magnum `DbCodec` instances that Magnum's auto-derivation does not
  * cover:
  *
  *   - `List[Long]`/`List[String]` for Postgres array columns, read through
  *     PostgreSQL's JDBC-native `getArray` path.
  *   - `LocalDateTime` for `timestamp` (without time zone) columns, which store
  *     Warsaw-local wall-clock values.
  */

private def readArrayText(rs: ResultSet, pos: Int): List[String] =
  Option(rs.getArray(pos))
    .map { array =>
      try
        val raw = array.getArray
        (0 until ReflectArray.getLength(raw)).toList.map { index =>
          ReflectArray.get(raw, index).toString
        }
      finally array.free()
    }
    .getOrElse(Nil)

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

/** `timestamp` without time zone ↔ Warsaw-local `LocalDateTime`. */
given localDateTimeCodec: DbCodec[LocalDateTime] =
  new DbCodec[LocalDateTime]:
    def queryRepr: String = "?"
    def cols: IArray[Int] = IArray(Types.TIMESTAMP)
    def readSingle(rs: ResultSet, pos: Int): LocalDateTime =
      rs.getObject(pos, classOf[LocalDateTime])
    def writeSingle(
        value: LocalDateTime,
        ps: PreparedStatement,
        pos: Int
    ): Unit =
      ps.setObject(pos, value)
