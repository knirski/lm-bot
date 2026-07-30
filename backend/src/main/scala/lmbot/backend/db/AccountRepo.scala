package lmbot.backend.db

import com.augustnagro.magnum.{Transactor, connect, sql, transact}
import lmbot.shared.domain.AccountId

class AccountRepo(xa: Transactor):

  def reserveId(): AccountId =
    val rawId = transact(xa):
      sql"select nextval('luxmed_account_id_seq')".query[Long].run().head
    AccountId(rawId)

  def insert(row: LuxmedAccountRow): LuxmedAccountRow = transact(xa):
    sql"""insert into luxmed_accounts
          (id, owner_user_id, label, encrypted_username,
           encrypted_password, encrypted_device_uuid, encrypted_session,
           status, status_reason, last_successful_login,
           created_at, updated_at)
          values (${row.id}, ${row.ownerUserId}, ${row.label},
                  ${row.encryptedUsername}, ${row.encryptedPassword},
                  ${row.encryptedDeviceUuid}, ${row.encryptedSession},
                  ${row.status}, ${row.statusReason},
                  ${row.lastSuccessfulLogin},
                  ${row.createdAt}, ${row.updatedAt})
          returning *"""
      .query[LuxmedAccountRow]
      .run()
      .head

  def findOwned(id: AccountId, ownerUserId: Long): Option[LuxmedAccountRow] =
    connect(xa):
      sql"""select a.* from luxmed_accounts a
            where a.id = ${id.value} and a.owner_user_id = $ownerUserId"""
        .query[LuxmedAccountRow]
        .run()
        .headOption

  def listOwned(ownerUserId: Long): Seq[LuxmedAccountRow] = connect(xa):
    sql"""select a.* from luxmed_accounts a
          where a.owner_user_id = $ownerUserId
          order by a.created_at desc"""
      .query[LuxmedAccountRow]
      .run()

  def deleteOwned(id: AccountId, ownerUserId: Long): Boolean = transact(xa):
    sql"""delete from luxmed_accounts a
          where a.id = ${id.value} and a.owner_user_id = $ownerUserId""".update
      .run() > 0

  def updateSessionCas(
      id: AccountId,
      encryptedSession: Option[String],
      ownerUserId: Long,
      expectedStatus: String
  ): Option[LuxmedAccountRow] = transact(xa):
    sql"""update luxmed_accounts a
          set encrypted_session = $encryptedSession, updated_at = now()
          where a.id = ${id.value}
            and a.owner_user_id = $ownerUserId
            and a.status = $expectedStatus
          returning a.*"""
      .query[LuxmedAccountRow]
      .run()
      .headOption
