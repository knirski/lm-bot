package lmbot.backend.crypto

/** A closed error type for cryptographic operations at the AES-GCM boundary.
  *
  * Every expected failure is a value. Exception messages are never exposed —
  * they may contain plaintext, keys, or other secrets.
  */
enum CryptoError:
  case AuthenticationFailed
  case UnsupportedVersion(version: String)
  case DecodeFailed(message: String)
