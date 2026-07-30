package lmbot.backend.db

import java.lang.reflect.Array as ReflectArray
import java.sql.Types

import scala.IArray

import com.augustnagro.magnum.DbCodec

/** Custom Magnum `DbCodec` instances for PostgreSQL array and date/time types.
  *
  * Magnum's auto-derivation for `PostgresDbType` handles `OffsetDateTime` but
  * not `LocalDate`/`LocalTime` or `List[Long]`/`List[String]` for arrays. These
  * codecs fill those gaps using the PostgreSQL JDBC driver's native support.
  */

given longListCodec: DbCodec[List[Long]] with
  def queryRepr: String = "?"
  def cols: IArray[Int] = IArray(Types.ARRAY)
  def readSingle(rs: java.sql.ResultSet, pos: Int): List[Long] =
    val arr = rs.getArray(pos)
    if arr == null then List.empty
    else
      val raw = arr.getArray
      (0 until ReflectArray.getLength(raw)).toList.map { index =>
        ReflectArray.get(raw, index).asInstanceOf[java.lang.Number].longValue
      }
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
    val arr = rs.getArray(pos)
    if arr == null then List.empty
    else
      val raw = arr.getArray
      (0 until ReflectArray.getLength(raw)).toList.map { index =>
        ReflectArray.get(raw, index).asInstanceOf[String]
      }
  def writeSingle(
      value: List[String],
      ps: java.sql.PreparedStatement,
      pos: Int
  ): Unit =
    val conn = ps.getConnection
    val jdbcArr = conn.createArrayOf("text", value.toArray)
    ps.setArray(pos, jdbcArr)
