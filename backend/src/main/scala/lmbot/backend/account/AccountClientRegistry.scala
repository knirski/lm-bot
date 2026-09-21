package lmbot.backend.account

import scala.collection.mutable

import lmbot.backend.db.{AccountRepo, LuxmedAccountRow}
import lmbot.backend.luxmed.LuxmedClient
import lmbot.shared.api.ApiError
import lmbot.shared.domain.{AccountId, UserId}

/** One long-lived Luxmed client per linked account — and therefore one
  * `AccountGate` per account.
  *
  * Plan 4 built a client per request, so the gate's semaphore and minimum
  * spacing only serialized calls within a single request (issue #35). Every
  * Luxmed caller goes through this registry now: monitors sharing an account
  * and browser dictionary calls queue behind the same gate, and the in-memory
  * session survives between calls while the encrypted store keeps it durable.
  *
  * The map is guarded by one lock and bounded by the number of linked accounts;
  * that is the whole mutable state.
  */
final class AccountClientRegistry private (
    accounts: AccountRepo,
    factory: AccountClientFactory
):
  private val lock = new Object
  private val clients = mutable.Map.empty[AccountId, LuxmedClient]

  /** Engine lookup: the caller already owns the monitor/account relationship.
    */
  def forAccount(accountId: AccountId): Either[ApiError, LuxmedClient] =
    accounts.findById(accountId).toRight(ApiError.NotFound).flatMap(clientFor)

  /** Owner-scoped lookup for request paths; authorization stays here rather
    * than in routes.
    */
  def forOwnedAccount(
      ownerId: UserId,
      accountId: AccountId
  ): Either[ApiError, LuxmedClient] =
    accounts
      .findOwned(accountId, ownerId)
      .toRight(ApiError.NotFound)
      .flatMap(clientFor)

  private def clientFor(row: LuxmedAccountRow): Either[ApiError, LuxmedClient] =
    val accountId = AccountId(row.id)
    lock.synchronized:
      clients.get(accountId) match
        case Some(client) => Right(client)
        case None         =>
          factory
            .forStored(UserId(row.ownerUserId), accountId)
            .map: client =>
              clients.update(accountId, client)
              client

  /** Drops a deleted account's cached client, so its session and gate do not
    * outlive the account.
    */
  def forget(accountId: AccountId): Unit =
    lock.synchronized:
      clients.remove(accountId)
      ()

object AccountClientRegistry:
  def production(
      accounts: AccountRepo,
      factory: AccountClientFactory
  ): AccountClientRegistry =
    new AccountClientRegistry(accounts, factory)
