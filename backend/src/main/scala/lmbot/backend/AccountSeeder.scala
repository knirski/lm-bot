package lmbot.backend

import lmbot.backend.crypto.AesGcm
import lmbot.backend.db.AccountRepo
import lmbot.shared.domain.UserId

trait AccountSeeder:
  def ensure(owner: UserId, accounts: AccountRepo, crypto: AesGcm): Unit

object AccountSeeder:
  val noop: AccountSeeder = new AccountSeeder:
    override def ensure(
        owner: UserId,
        accounts: AccountRepo,
        crypto: AesGcm
    ): Unit = ()
