package lmbot.backend.crypto

import java.util.Base64

/** A versioned encrypted envelope.
  *
  * Format: `v1.<base64url-12-byte-nonce>.<base64url-ciphertext-and-tag>`
  *
  * The ciphertext-and-tag field is the combined output of AES/GCM/NoPadding
  * encryption (ciphertext || 128-bit GCM authentication tag), both Base64URL-
  * encoded without padding.
  */
final case class EncryptedEnvelope(
    nonce: Array[Byte],
    ciphertextAndTag: Array[Byte]
):

  /** Render the envelope to its textual representation. */
  def render: String =
    val enc = Base64.getUrlEncoder.withoutPadding()
    s"v1.${enc.encodeToString(nonce)}.${enc.encodeToString(ciphertextAndTag)}"

  override def toString: String = s"EncryptedEnvelope(${render.take(50)}...)"

object EncryptedEnvelope:

  /** Parse a textual envelope. Returns a typed error for unsupported versions
    * or malformed input.
    */
  def parse(text: String): Either[CryptoError, EncryptedEnvelope] =
    text.split("\\.", 3) match
      case Array(version, nonceB64, dataB64) =>
        version match
          case "v1" =>
            for
              nonce <- decodePart(nonceB64)
              _ <-
                if nonce.length == 12 then Right(())
                else
                  Left(
                    CryptoError
                      .DecodeFailed("nonce must be exactly 12 bytes")
                  )
              data <- decodePart(dataB64)
            yield EncryptedEnvelope(nonce, data)
          case other =>
            Left(CryptoError.UnsupportedVersion(other))
      case _ =>
        Left(CryptoError.DecodeFailed("expected format: v1.<nonce>.<data>"))

  private def decodePart(
      part: String
  ): Either[CryptoError.DecodeFailed, Array[Byte]] =
    try Right(Base64.getUrlDecoder.decode(part))
    catch
      case e: IllegalArgumentException =>
        Left(CryptoError.DecodeFailed(s"invalid base64url: ${e.getMessage}"))
