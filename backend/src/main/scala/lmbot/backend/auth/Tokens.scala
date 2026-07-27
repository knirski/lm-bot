package lmbot.backend.auth

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

object Tokens:
  private val rng = new SecureRandom()
  private val encoder = Base64.getUrlEncoder.withoutPadding

  /** 256 bits of entropy, URL-safe so it can live in a cookie unescaped. The
    * token is opaque: it carries no user identity and cannot be forged.
    */
  def generate(): String =
    val bytes = new Array[Byte](32)
    rng.nextBytes(bytes)
    encoder.encodeToString(bytes)

  /** Only the hash is persisted (see V1__init.sql). SHA-256 is right here
    * rather than Argon2: the input is already high-entropy random, so there is
    * nothing to slow down a guesser about, and lookups stay cheap.
    */
  def hash(token: String): String =
    val digest =
      MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8))
    encoder.encodeToString(digest)
