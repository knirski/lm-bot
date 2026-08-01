package lmbot.backend.account

import lmbot.backend.config.Secret
import lmbot.backend.crypto.{
  AesGcm,
  EncryptedEnvelope,
  EncryptionContext,
  EncryptionPurpose
}
import lmbot.shared.api.ApiError
import lmbot.shared.domain.{AccountId, UserId}

/** Decrypts one encrypted column of a stored Luxmed account. Shared by
  * [[AccountService]] (owner-facing reads) and [[AccountClientFactory]]
  * (building a client from a stored account) so the envelope-parse and decrypt
  * failure mapping exists in exactly one place.
  */
object StoredSecret:
  private val loadFailed = "The Luxmed account could not be loaded."

  def decrypt(
      crypto: AesGcm,
      value: String,
      ownerId: UserId,
      accountId: AccountId,
      purpose: EncryptionPurpose
  ): Either[ApiError, Secret] =
    for
      envelope <- EncryptedEnvelope
        .parse(value)
        .left
        .map(_ => ApiError.Unexpected(loadFailed))
      secret <- crypto
        .decrypt(envelope, EncryptionContext(ownerId, accountId, purpose))
        .left
        .map(_ => ApiError.Unexpected(loadFailed))
    yield secret
