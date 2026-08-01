package lmbot.backend.db

import java.time.OffsetDateTime

import com.augustnagro.magnum.{Transactor, connect, sql, transact}
import lmbot.shared.domain.UserId

class SessionRepo(xa: Transactor):

  def insert(
      tokenHash: String,
      userId: UserId,
      expiresAt: OffsetDateTime
  ): Unit =
    transact(xa):
      sql"""insert into sessions (token_hash, user_id, expires_at)
          values ($tokenHash, ${userId.value}, $expiresAt)""".update.run()
      ()

  def find(tokenHash: String): Option[SessionRow] = connect(xa):
    sql"select * from sessions where token_hash = $tokenHash"
      .query[SessionRow]
      .run()
      .headOption

  def delete(tokenHash: String): Unit = transact(xa):
    sql"delete from sessions where token_hash = $tokenHash".update.run()
    ()

  def deleteExpired(now: OffsetDateTime): Int = transact(xa):
    // Use to_timestamp to avoid memgres's broken < comparison with
    // OffsetDateTime parameters.  Millisecond precision via Double avoids
    // truncating sub-second expiry differences while sidestepping the
    // timestamptz wire-format issue entirely.
    val epochSeconds = now.toInstant.toEpochMilli.toDouble / 1000.0
    sql"delete from sessions where expires_at < to_timestamp($epochSeconds)".update
      .run()
