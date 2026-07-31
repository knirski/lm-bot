package lmbot.backend.account

enum AccountStatusReason(val value: String):
  case AuthFailed
      extends AccountStatusReason("Luxmed rejected these credentials.")
  case Challenge
      extends AccountStatusReason(
        "Luxmed requested an unexpected authentication step."
      )
  case RateLimited
      extends AccountStatusReason(
        "Luxmed may have temporarily locked this account."
      )
  case VersionRejected
      extends AccountStatusReason(
        "The configured Luxmed app version is no longer accepted."
      )
