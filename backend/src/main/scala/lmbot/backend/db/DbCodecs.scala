package lmbot.backend.db

import java.lang.reflect.Array as ReflectArray
import java.sql.Types
import java.sql.{PreparedStatement, ResultSet}

import scala.IArray

import com.augustnagro.magnum.DbCodec

/** Custom Magnum `DbCodec` instances for `List[Long]`/`List[String]` Postgres
  * array columns, which Magnum's auto-derivation for `PostgresDbType` does not
  * cover.
  *
  * Reads use PostgreSQL's JDBC-native `getArray` path.
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
