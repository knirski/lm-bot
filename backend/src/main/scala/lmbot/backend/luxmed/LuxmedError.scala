package lmbot.backend.luxmed

/** Typed errors that can arise from Luxmed API interactions.
  *
  * Every expected failure is a value. A `LuxmedTransport` call returns
  * `Either[LuxmedError, A]` where the errors below cover every HTTP-level
  * condition from the wire error taxonomy (§5.4). Exceptions represent bugs and
  * crash their owning fiber; they are not caught and converted.
  */
enum LuxmedError:

  /** Credentials rejected by Luxmed. 409 with an "invalid login or password" or
    * "nieprawidłowy login lub hasło" message.
    */
  case AuthFailed

  /** The auth response was success-shaped but contained a challenge payload
    * (two-factor verification). This endpoint does not enforce 2FA, so the
    * response is unexpected and should be investigated.
    */
  case UnexpectedAuthResponse(details: String)

  /** The session has expired. Detected via 302 to /LogOn or /UniversalLink, or
    * via a "session has expired" error message in any status.
    */
  case SessionExpired

  /** The Luxmed API version in use is no longer supported. 409 with the old app
    * version error body. The configured app version must be bumped before
    * further calls will succeed.
    */
  case VersionRejected(details: String)

  /** The request was rejected by Luxmed's API for a reason other than
    * credentials, session expiry, version rejection, or rate limiting.
    */
  case ApiRejected(details: String)

  /** Rate limited by Luxmed (HTTP 429). Back off and retry later.
    */
  case RateLimited

  /** A transient server error (5xx). May be retried.
    */
  case Transient(status: Int)

  /** A network-level failure (connection refused, DNS failure, timeout).
    */
  case NetworkFailure(details: String)

  /** The response body could not be decoded as the expected shape.
    */
  case DecodeFailed(details: String)

  /** A persistence operation on the session store failed.
    */
  case PersistenceFailed(details: String)

  /** A protocol-level violation such as a missing required header or an
    * unexpected response structure.
    */
  case ProtocolViolation(details: String)

  /** The requested slot is no longer available (lock was already taken by
    * another session).
    */
  case SlotGone
