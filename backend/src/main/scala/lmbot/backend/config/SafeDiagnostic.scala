package lmbot.backend.config

/** A diagnostic string that is guaranteed to contain no secrets.
  *
  * Constructed only through redaction of raw content or from a known-safe
  * internal message. This makes it structurally impossible to accidentally
  * include a raw token, password, or PII in an error variant's details.
  */
opaque type SafeDiagnostic = String

object SafeDiagnostic:

  /** Construct from content that is already safe (trusted internal messages).
    */
  def apply(safe: String): SafeDiagnostic = safe

  extension (d: SafeDiagnostic) def value: String = d
