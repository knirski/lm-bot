package lmbot.backend.monitor

/** The engine's small failure vocabulary.
  *
  * `LuxmedSlotSearch` classifies every `LuxmedError` into one of these, and the
  * retry policy reasons over them without knowing anything about HTTP. Details
  * are already-safe strings (from `SafeDiagnostic` or fixed text).
  */
enum CheckFailure:
  case AuthRejected
  case Challenge
  case RateLimited
  case VersionRejected
  case Transient(detail: String)
  case Persistent(detail: String)
