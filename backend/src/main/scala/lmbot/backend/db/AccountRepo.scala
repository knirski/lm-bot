package lmbot.backend.db

import com.augustnagro.magnum.{Transactor, connect, sql, transact}
import lmbot.shared.domain.{AccountId, UserId}

class AccountRepo(xa: Transactor):

  def reserveId(): AccountId =
    val rawId = transact(xa):
      sql"select nextval('luxmed_account_id_seq')".query[Long].run().head
    AccountId(rawId)

  /** Inserts an account atomically with its ID reservation.
    *
    * The unique owner/label constraint is the cross-process exclusion
    * mechanism; a concurrent duplicate returns None rather than failing.
    */
  def insertIfAbsent(
      ownerUserId: UserId,
      label: String,
      build: AccountId => LuxmedAccountRow
  ): Option[LuxmedAccountRow] = transact(xa):
    val accountId = AccountId(
      sql"select nextval('luxmed_account_id_seq')".query[Long].run().head
    )
    val row = build(accountId)
    sql"""insert into luxmed_accounts
          (id, owner_user_id, label, encrypted_username,
           encrypted_password, encrypted_device_uuid, encrypted_session,
           status, status_reason, last_successful_login,
           created_at, updated_at)
          values (${row.id}, ${ownerUserId.value}, ${label},
                  ${row.encryptedUsername}, ${row.encryptedPassword},
                  ${row.encryptedDeviceUuid}, ${row.encryptedSession},
                  ${row.status}, ${row.statusReason},
                  ${row.lastSuccessfulLogin},
                  ${row.createdAt}, ${row.updatedAt})
          on conflict (owner_user_id, label) do nothing
          returning *"""
      .query[LuxmedAccountRow]
      .run()
      .headOption

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

  def findOwned(id: AccountId, ownerUserId: UserId): Option[LuxmedAccountRow] =
    connect(xa):
      sql"""select a.* from luxmed_accounts a
            where a.id = ${id.value} and a.owner_user_id = ${ownerUserId.value}"""
        .query[LuxmedAccountRow]
        .run()
        .headOption

  def listOwned(ownerUserId: UserId): Seq[LuxmedAccountRow] = connect(xa):
    sql"""select a.* from luxmed_accounts a
          where a.owner_user_id = ${ownerUserId.value}
          order by a.created_at desc"""
      .query[LuxmedAccountRow]
      .run()

  def deleteOwned(id: AccountId, ownerUserId: UserId): Boolean = transact(xa):
    sql"""delete from luxmed_accounts a
          where a.id = ${id.value} and a.owner_user_id = ${ownerUserId.value}""".update
      .run() > 0
