package lmbot.backend.db

import com.augustnagro.magnum.{Transactor, connect, sql, transact}
import lmbot.shared.domain.Role

class UserRepo(xa: Transactor):

  def count(): Long = connect(xa):
    sql"select count(*) from users".query[Long].run().head

  def findByUsername(username: String): Option[UserRow] = connect(xa):
    sql"select * from users where username = $username"
      .query[UserRow]
      .run()
      .headOption

  def findById(id: Long): Option[UserRow] = connect(xa):
    sql"select * from users where id = $id".query[UserRow].run().headOption

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

  def deleteById(id: Long): Unit = transact(xa):
    sql"delete from users where id = $id".update.run()
    ()
