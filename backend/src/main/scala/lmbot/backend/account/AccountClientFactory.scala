package lmbot.backend.account

import java.time.{Duration, Instant}
import java.util.UUID

import com.augustnagro.magnum.Transactor
import lmbot.backend.config.Secret
import lmbot.backend.crypto.{
  AesGcm,
  EncryptedEnvelope,
  EncryptionContext,
  EncryptionPurpose
}
import lmbot.backend.db.AccountRepo
import lmbot.backend.luxmed.model.Credentials
import lmbot.backend.luxmed.{
  AccountGate,
  InMemorySessionStore,
  LuxmedClient,
  LuxmedConfig,
  LuxmedTransport,
  PostgresSessionStore,
  SessionStore
}
import lmbot.shared.api.ApiError
import lmbot.shared.domain.AccountId

final class AccountClientFactory private (
    xa: Transactor,
    accounts: AccountRepo,
    baseConfig: LuxmedConfig,
    crypto: AesGcm,
    minimumSpacing: Duration,
    now: () => Instant,
    transport: LuxmedConfig => LuxmedTransport
):
  private def client(
      config: LuxmedConfig,
      credentials: Credentials,
      store: SessionStore
  ): LuxmedClient =
    LuxmedClient(
      transport(config),
      credentials,
      AccountGate(minimumSpacing, now),
      store,
      now
    )

  def forLink(
      username: String,
      password: Secret,
      deviceUuid: UUID
  ): LuxmedClient =
    client(
      baseConfig.copy(deviceUuid = deviceUuid),
      Credentials(username, password),
      InMemorySessionStore()
    )

  def forStored(
      ownerId: Long,
      accountId: AccountId
  ): Either[ApiError, LuxmedClient] =
    try
      accounts
        .findOwned(accountId, ownerId)
        .toRight(ApiError.NotFound)
        .flatMap: row =>
          for
            username <- decrypt(
              row.encryptedUsername,
              ownerId,
              accountId,
              EncryptionPurpose.Username
            )
            password <- decrypt(
              row.encryptedPassword,
              ownerId,
              accountId,
              EncryptionPurpose.Password
            )
            device <- decrypt(
              row.encryptedDeviceUuid,
              ownerId,
              accountId,
              EncryptionPurpose.DeviceId
            )
            uuid <- parseUuid(device.value)
          yield client(
            baseConfig.copy(deviceUuid = uuid),
            Credentials(username.value, password),
            PostgresSessionStore(xa, ownerId, accountId, crypto)
          )
    catch
      case _: Exception =>
        Left(ApiError.Unexpected("The Luxmed account could not be loaded."))

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

  private def parseUuid(value: String): Either[ApiError, UUID] =
    try Right(UUID.fromString(value))
    catch
      case _: IllegalArgumentException =>
        Left(ApiError.Unexpected("The Luxmed account could not be loaded."))

object AccountClientFactory:
  def production(
      xa: Transactor,
      accounts: AccountRepo,
      baseConfig: LuxmedConfig,
      crypto: AesGcm,
      minimumSpacing: Duration = Duration.ofSeconds(1),
      now: () => Instant = () => Instant.now()
  ): AccountClientFactory =
    new AccountClientFactory(
      xa,
      accounts,
      baseConfig,
      crypto,
      minimumSpacing,
      now,
      config => LuxmedTransport.production(config)
    )
