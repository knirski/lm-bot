package lmbot.backend.db

import com.augustnagro.magnum.{
  DbCodec,
  Id,
  PostgresDbType,
  SqlNameMapper,
  Table
}

import java.time.OffsetDateTime

/** Persistence shapes. `role` is a `String` rather than the shared `Role` enum
  * on purpose: deriving a Magnum `DbCodec` for `Role` would drag a JVM-only
  * dependency into the cross-compiled `shared` module. Conversion happens at
  * the repository boundary instead.
  */
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class UserRow(
    @Id id: Long,
    username: String,
    displayName: String,
    passwordHash: String,
    role: String,
    telegramChatId: Option[Long],
    disabled: Boolean,
    createdAt: OffsetDateTime,
    updatedAt: OffsetDateTime
) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class SessionRow(
    @Id tokenHash: String,
    userId: Long,
    expiresAt: OffsetDateTime,
    createdAt: OffsetDateTime
) derives DbCodec
