package lmbot.shared.api

/** Every failure the API can express, with the HTTP status baked in.
  *
  * Keeping the status on the error lets one Tapir error output serve every
  * endpoint (see AuthEndpoints.errorOut) instead of a `oneOf` variant list
  * that has to be extended in lockstep with this enum.
  */
enum ApiError(val status: Int, val code: String, val message: String):
  case Unauthorized               extends ApiError(401, "unauthorized", "Not authenticated")
  case Forbidden                  extends ApiError(403, "forbidden", "Not allowed")
  case NotFound                   extends ApiError(404, "not_found", "Not found")
  case Conflict(detail: String)   extends ApiError(409, "conflict", detail)
  case Validation(detail: String) extends ApiError(422, "validation", detail)
  case Unexpected(detail: String) extends ApiError(500, "unexpected", detail)

object ApiError:
  /** Rebuild an error from the wire. Unknown codes become `Unexpected` rather
    * than an exception: a server that grows a new error must not crash an old
    * client.
    */
  def fromWire(status: Int, code: String, message: String): ApiError = code match
    case "unauthorized" => Unauthorized
    case "forbidden"    => Forbidden
    case "not_found"    => NotFound
    case "conflict"     => Conflict(message)
    case "validation"   => Validation(message)
    case "unexpected"   => Unexpected(message)
    case other          => Unexpected(s"unrecognised error [$other/$status]: $message")
