package lmbot.frontend

import lmbot.shared.api.ApiError
import lmbot.shared.domain.{AccountId, AccountView, UserView}

enum Msg:
  case UsernameChanged(value: String)
  case PasswordChanged(value: String)
  case LoginSubmitted
  case LoginSucceeded(user: UserView)
  case LoginFailed(error: ApiError)
  case SessionRestored(user: UserView)
  case SessionAbsent
  case LogoutRequested
  case LoggedOut

  case AccountsRequested
  case AccountsLoaded(accounts: List[AccountView])
  case AccountsLoadFailed(error: ApiError)

  case LinkLabelChanged(value: String)
  case LinkUsernameChanged(value: String)
  case LinkPasswordChanged(value: String)
  case LinkAccountSubmitted
  case AccountLinked(account: AccountView)
  case AccountLinkFailed(error: ApiError)

  case DeleteAccountRequested(accountId: AccountId)
  case DeleteConfirmed
  case DeleteCancelled
  case AccountDeleted(accountId: AccountId)
  case AccountDeleteFailed(error: ApiError)
