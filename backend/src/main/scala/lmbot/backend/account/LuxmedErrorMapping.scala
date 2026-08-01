package lmbot.backend.account

import lmbot.backend.luxmed.LuxmedError
import lmbot.shared.api.ApiError

/** Distinguishes the `LuxmedError` cases an operator needs to act on
  * differently (rejected credentials, a challenge, a rate limit, a rejected
  * client version) from everything else, which collapses into `fallback`.
  * Shared by every caller that turns a `LuxmedClient` failure into an
  * `ApiError` — collapsing them all into one generic message would hide
  * "credentials are no longer valid" behind "something went wrong".
  */
private[account] def luxmedErrorMapping(
    error: LuxmedError,
    fallback: String
): ApiError = error match
  case LuxmedError.AuthFailed =>
    ApiError.Validation(AccountStatusReason.AuthFailed.value)
  case _: LuxmedError.UnexpectedAuthResponse =>
    ApiError.Conflict(AccountStatusReason.Challenge.value)
  case LuxmedError.RateLimited =>
    ApiError.Conflict(AccountStatusReason.RateLimited.value)
  case _: LuxmedError.VersionRejected =>
    ApiError.Conflict(AccountStatusReason.VersionRejected.value)
  case _: LuxmedError.NetworkFailure | _: LuxmedError.Transient =>
    ApiError.Unexpected("Luxmed is temporarily unavailable.")
  case _ => ApiError.Unexpected(fallback)
