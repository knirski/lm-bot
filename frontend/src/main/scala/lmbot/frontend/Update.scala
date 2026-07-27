package lmbot.frontend

import lmbot.frontend.api.ApiClient
import lmbot.frontend.elm.{AsyncEffect, Transition}
import lmbot.shared.api.{ApiError, LoginRequest}

import scala.concurrent.{ExecutionContext, Future}

/** Every decision the frontend makes lives here, and this function is pure:
  * it returns the next state plus a description of what to do, never doing it.
  */
class Update(api: ApiClient):

  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

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
        val effect = new AsyncEffect[Msg]:
          def run(): Future[Option[Msg]] =
            api.login(request).map:
              case Right(user) => Some(Msg.LoginSucceeded(user))
              case Left(err)   => Some(Msg.LoginFailed(err))
        Transition(state.copy(login = form.copy(submitting = true, error = None)), Nil, List(effect))

    case Msg.LoginSucceeded(user) =>
      // Drop the password as soon as it has served its purpose.
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
      val effect = new AsyncEffect[Msg]:
        def run(): Future[Option[Msg]] =
          api.logout().map(_ => Some(Msg.LoggedOut))
      Transition(state, Nil, List(effect))

    case Msg.LoggedOut =>
      Transition(AppState(Screen.Login, LoginForm(), None, booting = false), Nil)

  private def explain(err: ApiError): String = err match
    case ApiError.Unauthorized  => "Wrong username or password."
    case ApiError.Forbidden     => "That account is disabled. Ask the administrator."
    case ApiError.Unexpected(d) => s"Something went wrong: $d"
    case other                  => other.message
