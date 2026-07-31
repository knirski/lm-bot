package lmbot.frontend

import lmbot.shared.domain.{AccountId, AccountView, UserView}

enum Screen:
  case Login, Dashboard

/** Not-yet-requested / in-flight / resolved data. A closed enum rather than a
  * pair of booleans, so "loading" and "loaded but empty" can never be confused
  * with each other.
  */
enum LoadState[+A]:
  case NotAsked, Loading
  case Loaded(value: A)
  case Failed(message: String)

case class LoginForm(
    username: String = "",
    password: String = "",
    submitting: Boolean = false,
    error: Option[String] = None
)

/** Mirrors `LoginForm`'s shape. The Luxmed password never survives past the
  * request that consumes it: both the success and failure paths in `Update`
  * clear `password`, and success resets the whole form.
  */
case class LinkAccountForm(
    label: String = "",
    username: String = "",
    password: String = "",
    submitting: Boolean = false,
    error: Option[String] = None
)

/** Which account a "Delete" click is asking to confirm, and the in-flight /
  * error state of that confirmation, kept separate from the account list so the
  * dialog can be busy or fail without touching it.
  */
case class DeleteConfirmation(
    accountId: AccountId,
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
    booting: Boolean,
    accounts: LoadState[List[AccountView]] = LoadState.NotAsked,
    linkForm: LinkAccountForm = LinkAccountForm(),
    deleteConfirmation: Option[DeleteConfirmation] = None
)

object AppState:
  val initial: AppState =
    AppState(Screen.Login, LoginForm(), None, booting = true)
