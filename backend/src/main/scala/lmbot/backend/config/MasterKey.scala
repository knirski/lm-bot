package lmbot.backend.config

import java.util.Base64

/** A validated 32-byte AES key, parsed from standard Base64.
  *
  * Construction is fallible: `fromBase64` validates length and encoding, and
  * returns a diagnostic message on failure. The diagnostic never includes the
  * input value.
  */
final class MasterKey private[config] (private val raw: Array[Byte]):
  def bytes: Array[Byte] = raw.clone()
  override def toString: String = "***"

object MasterKey:

  /** Parse a standard-Base64-encoded key. Succeeds only when the decoded bytes
    * are exactly 32 bytes (256 bits). Error messages name the variable and
    * required length but never include the value.
    */
  def fromBase64(encoded: String): Either[String, MasterKey] =
    try
      val decoded = Base64.getDecoder.decode(encoded)
      if decoded.length == 32 then Right(new MasterKey(decoded))
      else
        Left(
          s"LMBOT_MASTER_KEY must decode to exactly 32 bytes, got ${decoded.length}"
        )
    catch
      case _: IllegalArgumentException =>
        Left("LMBOT_MASTER_KEY is not valid Base64")
