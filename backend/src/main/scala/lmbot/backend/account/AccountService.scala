package lmbot.backend.account

import java.time.OffsetDateTime
import java.util.UUID

import gears.async.Async
import lmbot.backend.config.Secret
import lmbot.backend.crypto.{
  AesGcm,
  EncryptedEnvelope,
  EncryptionContext,
  EncryptionPurpose
}
import lmbot.backend.db.{AccountRepo, LuxmedAccountRow}
import lmbot.backend.luxmed.{LuxmedError, SessionCodec}
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
    now: () => OffsetDateTime = () => OffsetDateTime.now()
):
  def link(ownerId: Long, request: LinkAccountRequest)(using
      Async
  ): Either[ApiError, AccountView] =
    validate(request).flatMap: normalized =>
      val id = accounts.reserveId()
      val deviceUuid = uuidGenerator()
      clients
        .forLink(
          normalized.username,
          Secret(normalized.password),
          deviceUuid
        )
        .authenticate() match
        case Left(error)    => Left(linkError(error))
        case Right(session) =>
          if accounts.listOwned(ownerId).exists(_.label == normalized.label)
          then
            Left(
              ApiError.Conflict("An account with this label already exists.")
            )
          else
            val timestamp = now()
            val row = LuxmedAccountRow(
              id.value,
              ownerId,
              normalized.label,
              encrypt(
                normalized.username,
                ownerId,
                id,
                EncryptionPurpose.Username
              ),
              encrypt(
                normalized.password,
                ownerId,
                id,
                EncryptionPurpose.Password
              ),
              encrypt(
                deviceUuid.toString,
                ownerId,
                id,
                EncryptionPurpose.DeviceId
              ),
              Some(
                encrypt(
                  SessionCodec.encode(session),
                  ownerId,
                  id,
                  EncryptionPurpose.Session
                )
              ),
              "active",
              None,
              Some(timestamp),
              timestamp,
              timestamp
            )
            try toView(accounts.insert(row))
            catch
              case _: Exception =>
                Left(
                  ApiError.Unexpected("The Luxmed account could not be linked.")
                )

  def list(ownerId: Long): Either[ApiError, List[AccountView]] =
    try
      accounts
        .listOwned(ownerId)
        .toList
        .foldRight(
          Right(Nil): Either[ApiError, List[AccountView]]
        ) { (account, result) =>
          for
            views <- result
            view <- toView(account)
          yield view :: views
        }
    catch
      case _: Exception =>
        Left(ApiError.Unexpected("The Luxmed accounts could not be loaded."))

  def delete(ownerId: Long, accountId: AccountId): Either[ApiError, Unit] =
    if accounts.deleteOwned(accountId.value, ownerId) then Right(())
    else Left(ApiError.NotFound)

  private def validate(
      request: LinkAccountRequest
  ): Either[ApiError, LinkAccountRequest] =
    val label = request.label.trim
    val username = request.username.trim
    if label.isEmpty then
      Left(ApiError.Validation("Account label is required."))
    else if label.length > 80 then
      Left(ApiError.Validation("Account label must be at most 80 characters."))
    else if username.isEmpty then
      Left(ApiError.Validation("Luxmed username is required."))
    else if username.length > 254 then
      Left(
        ApiError.Validation("Luxmed username must be at most 254 characters.")
      )
    else Right(LinkAccountRequest(label, username, request.password))

  private def encrypt(
      value: String,
      ownerId: Long,
      accountId: AccountId,
      purpose: EncryptionPurpose
  ): String =
    crypto.encrypt(value, EncryptionContext(ownerId, accountId, purpose)).render

  private def toView(row: LuxmedAccountRow): Either[ApiError, AccountView] =
    val status = row.status match
      case "active"      => AccountStatus.Active
      case "auth_failed" => AccountStatus.AuthFailed
      case "disabled"    => AccountStatus.Disabled
      case other         =>
        throw IllegalArgumentException(s"unsupported account status: $other")
    decrypt(
      row.encryptedUsername,
      row.ownerUserId,
      AccountId(row.id),
      EncryptionPurpose.Username
    ).map(username =>
      AccountView(
        AccountId(row.id),
        row.label,
        username.value,
        status,
        row.statusReason,
        row.lastSuccessfulLogin.map(_.toInstant)
      )
    )

  private def decrypt(
      value: String,
      ownerId: Long,
      accountId: AccountId,
      purpose: EncryptionPurpose
  ): Either[ApiError, Secret] =
    for
      envelope <- EncryptedEnvelope
        .parse(value)
        .left
        .map(_ =>
          ApiError.Unexpected("The Luxmed account could not be loaded.")
        )
      secret <- crypto
        .decrypt(envelope, EncryptionContext(ownerId, accountId, purpose))
        .left
        .map(_ =>
          ApiError.Unexpected("The Luxmed account could not be loaded.")
        )
    yield secret

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
    case _ => ApiError.Unexpected("The Luxmed account could not be linked.")
