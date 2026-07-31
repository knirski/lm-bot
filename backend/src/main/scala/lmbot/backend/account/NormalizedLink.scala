package lmbot.backend.account

import lmbot.backend.config.Secret
import lmbot.backend.support.result
import lmbot.backend.support.result.?
import lmbot.shared.api.ApiError
import lmbot.shared.domain.LinkAccountRequest

/** A `LinkAccountRequest` that has been trimmed, bounded, and had its password
  * wrapped in `Secret` — narrowed once at the API boundary so nothing
  * downstream can confuse a validated request with the raw one it came from.
  */
final case class NormalizedLink private (
    label: String,
    username: String,
    password: Secret
)

object NormalizedLink:
  private def bounded(
      value: String,
      max: Int,
      required: String,
      tooLong: String
  ): Either[ApiError, String] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left(ApiError.Validation(required))
    else if trimmed.length > max then Left(ApiError.Validation(tooLong))
    else Right(trimmed)

  def from(request: LinkAccountRequest): Either[ApiError, NormalizedLink] =
    result:
      NormalizedLink(
        bounded(
          request.label,
          80,
          "Account label is required.",
          "Account label must be at most 80 characters."
        ).?,
        bounded(
          request.username,
          254,
          "Luxmed username is required.",
          "Luxmed username must be at most 254 characters."
        ).?,
        Secret(request.password)
      )
