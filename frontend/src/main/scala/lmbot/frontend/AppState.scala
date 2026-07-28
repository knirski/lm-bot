package lmbot.frontend

import lmbot.shared.domain.UserView

enum Screen:
  case Login, Dashboard

case class LoginForm(
    username: String = "",
    password: String = "",
    submitting: Boolean = false,
    error: Option[String] = None
)

/** `booting` is true until the app has asked the server whether the browser
  * already holds a valid session, so the login form is not flashed at a user
  * who is in fact already signed in.
  */
case class AppState(
    screen: Screen,
    login: LoginForm,
    user: Option[UserView],
    booting: Boolean
)

object AppState:
  val initial: AppState =
    AppState(Screen.Login, LoginForm(), None, booting = true)
