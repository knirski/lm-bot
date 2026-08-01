package lmbot.backend.crypto

import java.nio.charset.StandardCharsets
import java.security.SecureRandom

import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import lmbot.backend.config.{MasterKey, Secret}
import lmbot.shared.domain.{AccountId, UserId}

/** The purpose for which a secret is being encrypted.
  *
  * Each value produces a distinct AAD binding, preventing a ciphertext
  * encrypted for one purpose from being used in another.
  */
enum EncryptionPurpose(val wireName: String):
  case Username extends EncryptionPurpose("username")
  case Password extends EncryptionPurpose("password")
  case DeviceId extends EncryptionPurpose("device-id")
  case Session extends EncryptionPurpose("session")

/** The context that an encrypted secret is bound to.
  *
  * All three fields are authenticated as AAD. Decryption succeeds only when
  * ownerId, accountId, and purpose all match the values used during encryption.
  */
final case class EncryptionContext(
    ownerId: UserId,
    accountId: AccountId,
    purpose: EncryptionPurpose
):
  def aad: Array[Byte] =
    s"lm-bot:v1:${ownerId.value}:${accountId.value}:${purpose.wireName}"
      .getBytes(StandardCharsets.UTF_8)

/** AES-256/GCM/NoPadding encryption and decryption.
  *
  * Each encryption uses a fresh 12-byte random nonce and a 128-bit GCM
  * authentication tag. The [[EncryptionContext]] is bound to the ciphertext as
  * AAD — decryption fails if any field of the context differs from the value
  * used at encryption time.
  *
  * Cryptographic exceptions from the JCA layer are never exposed; they are
  * mapped to [[CryptoError.AuthenticationFailed]].
  *
  * @param key
  *   the 256-bit master key
  * @param random
  *   the source of nonces (injectable for deterministic testing)
  */
final class AesGcm(key: MasterKey, random: SecureRandom = SecureRandom()):

  private val algorithm = "AES"
  private val mode = "AES/GCM/NoPadding"
  private val tagLengthBits = 128

  /** Encrypt `plaintext` bound to `context`. Returns a versioned envelope. */
  def encrypt(
      plaintext: String,
      context: EncryptionContext
  ): EncryptedEnvelope =
    val nonce = new Array[Byte](12)
    random.nextBytes(nonce)
    val spec = GCMParameterSpec(tagLengthBits, nonce)
    val keySpec = SecretKeySpec(key.bytes, algorithm)
    val cipher = Cipher.getInstance(mode)
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
    cipher.updateAAD(context.aad)
    val ciphertextAndTag = cipher.doFinal(
      plaintext.getBytes(StandardCharsets.UTF_8)
    )
    EncryptedEnvelope(nonce, ciphertextAndTag)

  /** Decrypt an envelope. Fails with [[CryptoError]] when the context does not
    * match, the ciphertext was tampered with, or the wrong key is used.
    */
  def decrypt(
      envelope: EncryptedEnvelope,
      context: EncryptionContext
  ): Either[CryptoError, Secret] =
    try
      val spec = GCMParameterSpec(tagLengthBits, envelope.nonce)
      val keySpec = SecretKeySpec(key.bytes, algorithm)
      val cipher = Cipher.getInstance(mode)
      cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
      cipher.updateAAD(context.aad)
      val plaintext = cipher.doFinal(envelope.ciphertextAndTag)
      Right(Secret(String(plaintext, StandardCharsets.UTF_8)))
    catch
      case _: java.security.GeneralSecurityException =>
        Left(CryptoError.AuthenticationFailed)

object AesGcm:
  def apply(key: MasterKey, random: SecureRandom = SecureRandom()): AesGcm =
    new AesGcm(key, random)
