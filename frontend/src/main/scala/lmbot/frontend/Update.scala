package lmbot.frontend

import gears.async.Async
import lmbot.frontend.api.ApiClient
import lmbot.frontend.elm.{Effect, Transition}
import lmbot.shared.api.{ApiError, LoginRequest}
import lmbot.shared.domain.LinkAccountRequest

/** Every decision the frontend makes lives here, and this function is pure: it
  * returns the next state plus a description of what to do, never doing it.
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
          state.copy(login =
            form.copy(error = Some("Enter both a username and a password."))
          ),
          Nil
        )
      else
        val request = LoginRequest(form.username, form.password)
        val effect = new Effect[Msg]:
          def run(using Async): Option[Msg] = Some:
            api.login(request) match
              case Right(user) => Msg.LoginSucceeded(user)
              case Left(err)   => Msg.LoginFailed(err)
        Transition(
          state.copy(login = form.copy(submitting = true, error = None)),
          List(effect)
        )

    case Msg.LoginSucceeded(user) =>
      // Reaching the dashboard is the one place a user needs their accounts,
      // so folding "start loading accounts" into this transition (rather than
      // requiring a separate manual navigation) is what makes them appear
      // without extra clicks. Delegating to AccountsRequested keeps that
      // effect defined in exactly one place.
      apply(
        state.copy(
          screen = Screen.Dashboard,
          user = Some(user),
          login = LoginForm(),
          booting = false
        ),
        Msg.AccountsRequested
      )

    case Msg.LoginFailed(err) =>
      Transition(
        state.copy(
          login = state.login.copy(
            submitting = false,
            password = "",
            error = Some(explain(err))
          ),
          booting = false
        ),
        Nil
      )

    case Msg.SessionRestored(user) =>
      apply(
        state
          .copy(screen = Screen.Dashboard, user = Some(user), booting = false),
        Msg.AccountsRequested
      )

    case Msg.SessionAbsent =>
      Transition(
        state.copy(screen = Screen.Login, user = None, booting = false),
        Nil
      )

    case Msg.LogoutRequested =>
      val effect = new Effect[Msg]:
        def run(using Async): Option[Msg] =
          api.logout()
          Some(Msg.LoggedOut)
      Transition(state, List(effect))

    case Msg.LoggedOut =>
      Transition(
        AppState(Screen.Login, LoginForm(), None, booting = false),
        Nil
      )

    case Msg.AccountsRequested =>
      val effect = new Effect[Msg]:
        def run(using Async): Option[Msg] = Some:
          api.listAccounts() match
            case Right(accounts) => Msg.AccountsLoaded(accounts)
            case Left(err)       => Msg.AccountsLoadFailed(err)
      Transition(state.copy(accounts = LoadState.Loading), List(effect))

    case Msg.AccountsLoaded(accounts) =>
      Transition(state.copy(accounts = LoadState.Loaded(accounts)), Nil)

    case Msg.AccountsLoadFailed(err) =>
      Transition(state.copy(accounts = LoadState.Failed(explain(err))), Nil)

    case Msg.LinkLabelChanged(v) =>
      Transition(state.copy(linkForm = state.linkForm.copy(label = v)), Nil)

    case Msg.LinkUsernameChanged(v) =>
      Transition(
        state.copy(linkForm = state.linkForm.copy(username = v)),
        Nil
      )

    case Msg.LinkPasswordChanged(v) =>
      Transition(
        state.copy(linkForm = state.linkForm.copy(password = v)),
        Nil
      )

    case Msg.LinkAccountSubmitted =>
      val form = state.linkForm
      if form.submitting then Transition(state, Nil)
      else if form.label.isEmpty || form.username.isEmpty || form.password.isEmpty
      then
        Transition(
          state.copy(linkForm =
            form.copy(error = Some("Enter a label, username, and password."))
          ),
          Nil
        )
      else
        val request =
          LinkAccountRequest(form.label, form.username, form.password)
        val effect = new Effect[Msg]:
          def run(using Async): Option[Msg] = Some:
            api.createAccount(request) match
              case Right(account) => Msg.AccountLinked(account)
              case Left(err)      => Msg.AccountLinkFailed(err)
        Transition(
          state.copy(linkForm = form.copy(submitting = true, error = None)),
          List(effect)
        )

    case Msg.AccountLinked(account) =>
      // The whole form resets to empty once the new account shows up in the
      // list — unlike a login failure, we stay on this screen, so there is no
      // "leaving the form behind" moment to rely on for forgetting it.
      val updated = state.accounts match
        case LoadState.Loaded(existing) => LoadState.Loaded(existing :+ account)
        case _                          => LoadState.Loaded(List(account))
      Transition(
        state.copy(accounts = updated, linkForm = LinkAccountForm()),
        Nil
      )

    case Msg.AccountLinkFailed(err) =>
      Transition(
        state.copy(linkForm =
          state.linkForm
            .copy(submitting = false, password = "", error = Some(explain(err)))
        ),
        Nil
      )

    case Msg.DeleteAccountRequested(accountId) =>
      Transition(
        state.copy(deleteConfirmation = Some(DeleteConfirmation(accountId))),
        Nil
      )

    case Msg.DeleteCancelled =>
      Transition(state.copy(deleteConfirmation = None), Nil)

    case Msg.DeleteConfirmed =>
      state.deleteConfirmation match
        case None => Transition(state, Nil)
        case Some(confirmation) if confirmation.submitting =>
          Transition(state, Nil)
        case Some(confirmation) =>
          val effect = new Effect[Msg]:
            def run(using Async): Option[Msg] = Some:
              api.deleteAccount(confirmation.accountId) match
                case Right(()) => Msg.AccountDeleted(confirmation.accountId)
                case Left(err) => Msg.AccountDeleteFailed(err)
          Transition(
            state.copy(deleteConfirmation =
              Some(confirmation.copy(submitting = true, error = None))
            ),
            List(effect)
          )

    case Msg.AccountDeleted(accountId) =>
      val updated = state.accounts match
        case LoadState.Loaded(existing) =>
          LoadState.Loaded(existing.filterNot(_.id == accountId))
        case other => other
      Transition(
        state.copy(accounts = updated, deleteConfirmation = None),
        Nil
      )

    case Msg.AccountDeleteFailed(err) =>
      val updated = state.deleteConfirmation.map(
        _.copy(submitting = false, error = Some(explain(err)))
      )
      Transition(state.copy(deleteConfirmation = updated), Nil)

  private def explain(err: ApiError): String = err match
    case ApiError.Unauthorized => "Wrong username or password."
    case ApiError.Forbidden    =>
      "That account is disabled. Ask the administrator."
    case ApiError.Unexpected(d) => s"Something went wrong: $d"
    case other                  => other.message
