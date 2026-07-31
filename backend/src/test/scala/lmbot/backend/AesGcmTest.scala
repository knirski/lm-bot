package lmbot.backend

import java.security.SecureRandom

import lmbot.backend.config.MasterKey
import lmbot.backend.crypto.{
  AesGcm,
  CryptoError,
  EncryptedEnvelope,
  EncryptionContext,
  EncryptionPurpose
}
import lmbot.shared.domain.AccountId

/** Crypto tests.
  *
  * Some tests use a deterministic SecureRandom to pin the textual envelope
  * format; production tests use real SecureRandom to prove nonce variation.
  */
class AesGcmTest extends munit.FunSuite:

  /** 32 random bytes, standard Base64. Dev-only value; never used in prod. */
  private val testKeyB64 = "zipI+cHXewVqZsFi8jDDrAglsYK9B3fXZMswhyxr2hk="
  private val testKey = MasterKey.fromBase64(testKeyB64).toOption.get

  test("encrypt/decrypt round-trips with the same context"):
    val aes = AesGcm(testKey)
    val ctx = EncryptionContext(1L, AccountId(2L), EncryptionPurpose.Password)
    val envelope = aes.encrypt("my-secret-password", ctx)
    val result = aes.decrypt(envelope, ctx)
    assertEquals(result.map(_.value), Right("my-secret-password"))

  test("two encryptions of one plaintext have different nonces and ciphertext"):
    val aes = AesGcm(testKey)
    val ctx = EncryptionContext(1L, AccountId(2L), EncryptionPurpose.Password)
    val e1 = aes.encrypt("same-plaintext", ctx)
    val e2 = aes.encrypt("same-plaintext", ctx)
    assert(
      !java.util.Arrays.equals(e1.nonce, e2.nonce),
      "nonces should differ"
    )
    assert(
      !java.util.Arrays.equals(
        e1.ciphertextAndTag,
        e2.ciphertextAndTag
      ),
      "ciphertexts should differ"
    )

  test("changing owner, account, or purpose rejects authentication"):
    val aes = AesGcm(testKey)
    val ctx = EncryptionContext(1L, AccountId(2L), EncryptionPurpose.Password)
    val envelope = aes.encrypt("my-password", ctx)

    val wrongOwner =
      EncryptionContext(99L, AccountId(2L), EncryptionPurpose.Password)
    assert(aes.decrypt(envelope, wrongOwner).isLeft)

    val wrongAccount =
      EncryptionContext(1L, AccountId(99L), EncryptionPurpose.Password)
    assert(aes.decrypt(envelope, wrongAccount).isLeft)

    val wrongPurpose =
      EncryptionContext(1L, AccountId(2L), EncryptionPurpose.DeviceId)
    assert(aes.decrypt(envelope, wrongPurpose).isLeft)

  test("tampered ciphertext rejects authentication"):
    val aes = AesGcm(testKey)
    val ctx = EncryptionContext(1L, AccountId(2L), EncryptionPurpose.Password)
    val envelope = aes.encrypt("my-password", ctx)
    val tamperedCiphertext = envelope.ciphertextAndTag.clone()
    tamperedCiphertext(0) = (tamperedCiphertext(0) ^ 0x01).toByte
    val tampered = EncryptedEnvelope(envelope.nonce, tamperedCiphertext)
    val result = aes.decrypt(tampered, ctx)
    assertEquals(result, Left(CryptoError.AuthenticationFailed))

  test("unsupported envelope version is a typed error"):
    val result = EncryptedEnvelope.parse("v2.abc.def")
    assertEquals(result, Left(CryptoError.UnsupportedVersion("v2")))

  test("envelope and errors never render plaintext or the master key"):
    val aes = AesGcm(testKey)
    val ctx = EncryptionContext(1L, AccountId(2L), EncryptionPurpose.Password)
    val envelope = aes.encrypt("my-password", ctx)
    val rendered = envelope.render
    assert(!rendered.contains("my-password"))
    assert(!rendered.contains("zipI+cHXewVqZsFi8jDDrAglsYK9B3fXZMswhyxr2hk="))

  test("envelope round-trips through render and parse"):
    val nonce = (0 to 11).map(_.toByte).toArray
    val data = Array[Byte](0x10, 0x20, 0x30)
    val envelope = EncryptedEnvelope(nonce, data)
    val parsed = EncryptedEnvelope.parse(envelope.render)
    assertEquals(parsed.map(_.render), Right(envelope.render))

  test("malformed envelope parts produce a decode error"):
    val result = EncryptedEnvelope.parse("v1.not-valid-base64!.cXdlcnR5")
    assert(result.isLeft)

  test("wrong number of envelope parts is a decode error"):
    val result = EncryptedEnvelope.parse("v1.abc")
    assert(result.isLeft)

  test("deterministic nonce produces the expected envelope structure"):
    val fixedRandom = new SecureRandom():
      override def nextBytes(bytes: Array[Byte]): Unit =
        (0 until bytes.length).foreach(i => bytes(i) = (0x42 + i).toByte)
    val aes = AesGcm(testKey, fixedRandom)
    val ctx =
      EncryptionContext(1L, AccountId(2L), EncryptionPurpose.Password)
    val envelope = aes.encrypt("hello", ctx)
    // Nonce bytes are 0x42, 0x43, …, 0x4D
    val rendered = envelope.render
    assert(rendered.startsWith("v1."), s"unexpected prefix: $rendered")
    val parts = rendered.split("\\.", 3)
    assertEquals(parts(0), "v1")
    // Base64URL of 12 known nonce bytes
    assertEquals(parts(1), "QkNERUZHSElKS0xN")
    assertEquals(parts(2), "2V8I8VfEDCui2Yu7Dc29hJisg0sg")
    // The plaintext never appears in the rendered envelope
    assert(!rendered.contains("hello"))

    // Round-trip the deterministic envelope
    val parsed = EncryptedEnvelope.parse(rendered)
    assert(parsed.isRight, s"parse failed: $parsed")
