package lmbot.backend.db

import java.time.OffsetDateTime

import com.augustnagro.magnum.{Transactor, connect, sql, transact}
import lmbot.shared.domain.{Role, UserId}

class UserRepo(xa: Transactor):

  def count(): Long = connect(xa):
    sql"select count(*) from users".query[Long].run().head

  def findByUsername(username: String): Option[UserRow] = connect(xa):
    sql"select * from users where username = $username"
      .query[UserRow]
      .run()
      .headOption

  def findById(id: UserId): Option[UserRow] = connect(xa):
    sql"select * from users where id = ${id.value}"
      .query[UserRow]
      .run()
      .headOption

  def insert(
      username: String,
      displayName: String,
      passwordHash: String,
      role: Role
  ): UserRow =
    val roleStr = Role.asString(role)
    transact(xa):
      sql"""insert into users (username, display_name, password_hash, role)
            values ($username, $displayName, $passwordHash, $roleStr)
            returning *"""
        .query[UserRow]
        .run()
        .head

  def deleteById(id: UserId): Unit = transact(xa):
    sql"delete from users where id = ${id.value}".update.run()
    ()

  /** Stores the Argon2id hash of a one-time Telegram link code, replacing any
    * previous code.
    */
  def setTelegramLinkCode(
      userId: UserId,
      codeHash: String,
      expiresAt: OffsetDateTime
  ): Unit = transact(xa):
    sql"""update users
          set telegram_link_code_hash = $codeHash,
              telegram_link_code_expires_at = $expiresAt,
              updated_at = now()
          where id = ${userId.value}""".update.run()
    ()

  def clearTelegramLinkCode(userId: UserId): Unit = transact(xa):
    sql"""update users
          set telegram_link_code_hash = null,
              telegram_link_code_expires_at = null,
              updated_at = now()
          where id = ${userId.value}""".update.run()
    ()

  /** Users holding an unexpired link code — the candidates a `/start <code>` is
    * verified against, because Argon2id hashes cannot be looked up.
    */
  def listLinkableUsers(now: OffsetDateTime): Seq[UserRow] = connect(xa):
    sql"""select * from users
          where telegram_link_code_hash is not null
            and telegram_link_code_expires_at > $now
          order by id""".query[UserRow].run()

  def setTelegramChatId(userId: UserId, chatId: Long): Unit = transact(xa):
    sql"""update users
          set telegram_chat_id = $chatId, updated_at = now()
          where id = ${userId.value}""".update.run()
    ()

  def clearTelegramChatId(userId: UserId): Unit = transact(xa):
    sql"""update users
          set telegram_chat_id = null, updated_at = now()
          where id = ${userId.value}""".update.run()
    ()

  /** Every enabled admin — the recipients of ops notifications. */
  def listAdmins(): Seq[UserRow] = connect(xa):
    sql"""select * from users
          where role = 'admin' and disabled = false
          order by id""".query[UserRow].run()
