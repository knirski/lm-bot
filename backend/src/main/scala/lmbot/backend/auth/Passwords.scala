package lmbot.backend.auth

import scala.util.Try

import de.mkammerer.argon2.Argon2Factory.Argon2Types
import de.mkammerer.argon2.{Argon2, Argon2Factory}

object Passwords:
  private val argon2: Argon2 = Argon2Factory.create(Argon2Types.ARGON2id)

  // OWASP-style baseline: 3 passes over 64 MiB, one lane. Family scale means
  // logins are rare, so favour cost over throughput.
  private val Iterations = 3
  private val MemoryKiB = 65536
  private val Parallelism = 1

  def hash(plain: String): String =
    val chars = plain.toCharArray
    try argon2.hash(Iterations, MemoryKiB, Parallelism, chars)
    finally argon2.wipeArray(chars)

  /** Returns false — never throws — for a malformed stored hash, so a corrupt
    * row denies access rather than crashing the request.
    */
  def verify(hash: String, plain: String): Boolean =
    val chars = plain.toCharArray
    try Try(argon2.verify(hash, chars)).getOrElse(false)
    finally argon2.wipeArray(chars)
