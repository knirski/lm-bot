package lmbot.backend.account

import java.sql.SQLException
import java.time.{Instant, ZoneOffset}
import java.util.UUID

import gears.async.Async
import lmbot.backend.crypto.{AesGcm, EncryptionContext, EncryptionPurpose}
import lmbot.backend.db.{AccountRepo, LuxmedAccountRow}
import lmbot.backend.luxmed.{LuxmedError, SessionCodec}
import lmbot.backend.support.attempt
import lmbot.backend.support.result
import lmbot.backend.support.result.?
import lmbot.shared.api.ApiError
import lmbot.shared.domain.{
  AccountId,
  AccountStatus,
  AccountView,
  LinkAccountRequest
}

final class AccountService(
    accounts: AccountRepo,
    clients: AccountClientFactory,
    crypto: AesGcm,
    uuidGenerator: () => UUID = () => UUID.randomUUID(),
    now: () => Instant = () => Instant.now()
):
  private val duplicateLabel = "An account with this label already exists."
  private val linkFailed = "The Luxmed account could not be linked."
  private val accountsLoadFailed = "The Luxmed accounts could not be loaded."
  private val accountLoadFailed = "The Luxmed account could not be loaded."
  private val accountDeleteFailed = "The Luxmed account could not be deleted."

  private val dbFailure: Throwable => ApiError =
    _ => ApiError.Unexpected(linkFailed)

  private def persistFailure(error: Throwable): ApiError =
    if hasSqlState(error, "23505") then ApiError.Conflict(duplicateLabel)
    else ApiError.Unexpected(linkFailed)

  /** Performs at most one Luxmed password grant per call: the duplicate-label
    * check runs before authentication, so a request that cannot succeed never
    * spends a real login (Luxmed can lock an account after repeated attempts).
    */
  def link(ownerId: Long, request: LinkAccountRequest)(using
      Async
  ): Either[ApiError, AccountView] =
    result:
      val normalized = NormalizedLink.from(request).?
      val existing = attempt(dbFailure)(accounts.listOwned(ownerId)).?
      Either
        .cond(
          !existing.exists(_.label == normalized.label),
          (),
          ApiError.Conflict(duplicateLabel)
        )
        .?
      val id = attempt(dbFailure)(accounts.reserveId()).?
      val deviceUuid = uuidGenerator()
      val session = clients
        .forLink(normalized.username, normalized.password, deviceUuid)
        .authenticate()
        .left
        .map(linkError)
        .?
      val timestamp = now().atOffset(ZoneOffset.UTC)
      val row = LuxmedAccountRow(
        id.value,
        ownerId,
        normalized.label,
        encrypt(normalized.username, ownerId, id, EncryptionPurpose.Username),
        encrypt(
          normalized.password.value,
          ownerId,
          id,
          EncryptionPurpose.Password
        ),
        encrypt(deviceUuid.toString, ownerId, id, EncryptionPurpose.DeviceId),
        Some(
          encrypt(
            SessionCodec.encode(session),
            ownerId,
            id,
            EncryptionPurpose.Session
          )
        ),
        AccountStatus.Active.wireName,
        None,
        Some(timestamp),
        timestamp,
        timestamp
      )
      val stored = attempt(persistFailure)(accounts.insert(row)).?
      toView(stored).?

  private def hasSqlState(error: Throwable, state: String): Boolean =
    error match
      case sql: SQLException if sql.getSQLState == state => true
      case _ => Option(error.getCause).exists(hasSqlState(_, state))

  def list(ownerId: Long): Either[ApiError, List[AccountView]] =
    result:
      val rows =
        attempt(_ => ApiError.Unexpected(accountsLoadFailed))(
          accounts.listOwned(ownerId)
        ).?
      rows.toList.map(row => toView(row).?)

  def delete(ownerId: Long, accountId: AccountId): Either[ApiError, Unit] =
    attempt.either(_ => ApiError.Unexpected(accountDeleteFailed)):
      if accounts.deleteOwned(accountId, ownerId) then Right(())
      else Left(ApiError.NotFound)

  private def encrypt(
      value: String,
      ownerId: Long,
      accountId: AccountId,
      purpose: EncryptionPurpose
  ): String =
    crypto.encrypt(value, EncryptionContext(ownerId, accountId, purpose)).render

  private def toView(row: LuxmedAccountRow): Either[ApiError, AccountView] =
    result:
      val status = AccountStatus
        .fromWire(row.status)
        .left
        .map(_ => ApiError.Unexpected(accountLoadFailed))
        .?
      val username = StoredSecret
        .decrypt(
          crypto,
          row.encryptedUsername,
          row.ownerUserId,
          AccountId(row.id),
          EncryptionPurpose.Username
        )
        .?
      AccountView(
        AccountId(row.id),
        row.label,
        username.value,
        status,
        row.statusReason,
        row.lastSuccessfulLogin.map(_.toInstant)
      )

  private def linkError(error: LuxmedError): ApiError = error match
    case LuxmedError.AuthFailed =>
      ApiError.Validation(AccountStatusReason.AuthFailed.value)
    case _: LuxmedError.UnexpectedAuthResponse =>
      ApiError.Conflict(AccountStatusReason.Challenge.value)
    case LuxmedError.RateLimited =>
      ApiError.Conflict(AccountStatusReason.RateLimited.value)
    case _: LuxmedError.VersionRejected =>
      ApiError.Conflict(AccountStatusReason.VersionRejected.value)
    case _: LuxmedError.NetworkFailure | _: LuxmedError.Transient =>
      ApiError.Unexpected("Luxmed is temporarily unavailable.")
    case _ => ApiError.Unexpected(linkFailed)
