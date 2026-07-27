package lmbot.frontend

import gears.async.Async
import lmbot.frontend.api.ApiClient
import lmbot.frontend.elm.{Effect, Transition}
import lmbot.shared.api.{ApiError, LoginRequest}

/** Every decision the frontend makes lives here, and this function is pure:
  * it returns the next state plus a description of what to do, never doing it.
  */
class Update(api: ApiClient):

  def apply(state: AppState, msg: Msg): Transition[AppState, Msg] = msg match

    case Msg.UsernameChanged(v) =>
      Transition(state.copy(login = state.login.copy(username = v)), Nil)

    case Msg.PasswordChanged(v) =>
      Transition(state.copy(login = state.login.copy(password = v)), Nil)

    case Msg.LoginSubmitted =>
      val form = state.login
      if form.submitting then Transition(state, Nil)
      else if form.username.isEmpty || form.password.isEmpty then
        Transition(
          state.copy(login = form.copy(error = Some("Enter both a username and a password."))),
          Nil
        )
      else
        val request = LoginRequest(form.username, form.password)
        val effect = new Effect[Msg]:
          def run(using Async): Option[Msg] = Some:
            api.login(request) match
              case Right(user) => Msg.LoginSucceeded(user)
              case Left(err)   => Msg.LoginFailed(err)
        Transition(state.copy(login = form.copy(submitting = true, error = None)), List(effect))

    case Msg.LoginSucceeded(user) =>
      Transition(
        state.copy(screen = Screen.Dashboard, user = Some(user), login = LoginForm(), booting = false),
        Nil
      )

    case Msg.LoginFailed(err) =>
      Transition(
        state.copy(
          login = state.login.copy(submitting = false, password = "", error = Some(explain(err))),
          booting = false
        ),
        Nil
      )

    case Msg.SessionRestored(user) =>
      Transition(state.copy(screen = Screen.Dashboard, user = Some(user), booting = false), Nil)

    case Msg.SessionAbsent =>
      Transition(state.copy(screen = Screen.Login, user = None, booting = false), Nil)

    case Msg.LogoutRequested =>
      val effect = new Effect[Msg]:
        def run(using Async): Option[Msg] =
          api.logout()
          Some(Msg.LoggedOut)
      Transition(state, List(effect))

    case Msg.LoggedOut =>
      Transition(AppState(Screen.Login, LoginForm(), None, booting = false), Nil)

  private def explain(err: ApiError): String = err match
    case ApiError.Unauthorized  => "Wrong username or password."
    case ApiError.Forbidden     => "That account is disabled. Ask the administrator."
    case ApiError.Unexpected(d) => s"Something went wrong: $d"
    case other                  => other.message
