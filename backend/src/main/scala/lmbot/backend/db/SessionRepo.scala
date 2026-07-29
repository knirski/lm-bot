package lmbot.backend.db

import java.time.OffsetDateTime

import com.augustnagro.magnum.{Transactor, connect, sql, transact}

class SessionRepo(xa: Transactor):

  def insert(tokenHash: String, userId: Long, expiresAt: OffsetDateTime): Unit =
    transact(xa):
      sql"""insert into sessions (token_hash, user_id, expires_at)
          values ($tokenHash, $userId, $expiresAt)""".update.run()
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
    // Uses to_timestamp to avoid memgres's broken < comparison with
    // OffsetDateTime parameters.  epoch-second comparison sidesteps the
    // timestamptz wire-format issue entirely.
    sql"delete from sessions where expires_at < to_timestamp(${now.toEpochSecond})".update
      .run()
