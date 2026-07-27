package lmbot.frontend

import lmbot.shared.api.ApiError
import lmbot.shared.domain.UserView

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
