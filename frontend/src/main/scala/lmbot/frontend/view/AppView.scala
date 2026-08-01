package lmbot.frontend.view

import com.raquo.laminar.api.L.*
import lmbot.frontend.elm.Runtime
import lmbot.frontend.{AppState, Msg, Screen}

/** Rendering only. Handlers do exactly one thing: send a message (spec §5.6).
  * Projections use `Signal.map(...).distinct` so the DOM updates narrowly.
  */
object AppView:

  def apply(rt: Runtime[AppState, Msg]): HtmlElement =
    val state = rt.store.signal
    div(
      cls := "app",
      child <-- state.map(s => (s.booting, s.screen)).distinct.map {
        case (true, _)                 => booting
        case (false, Screen.Login)     => loginPage(rt)
        case (false, Screen.Dashboard) => dashboard(rt)
      }
    )

  private def booting: HtmlElement =
    div(cls := "booting", p("Loading…"))

  private def loginPage(rt: Runtime[AppState, Msg]): HtmlElement =
    val formSignal = rt.store.signal.map(_.login).distinct
    div(
      cls := "login",
      h1("lm-bot"),
      form(
        onSubmit.preventDefault.mapTo(Msg.LoginSubmitted) --> (m =>
          rt.dispatch(m)
        ),
        label(
          "Username",
          input(
            tpe := "text",
            autoComplete := "username",
            value <-- formSignal.map(_.username).distinct,
            onInput.mapToValue --> (v => rt.dispatch(Msg.UsernameChanged(v)))
          )
        ),
        label(
          "Password",
          input(
            tpe := "password",
            autoComplete := "current-password",
            value <-- formSignal.map(_.password).distinct,
            onInput.mapToValue --> (v => rt.dispatch(Msg.PasswordChanged(v)))
          )
        ),
        button(
          tpe := "submit",
          disabled <-- formSignal.map(_.submitting).distinct,
          child.text <-- formSignal
            .map(f => if f.submitting then "Signing in…" else "Sign in")
            .distinct
        ),
        child.maybe <-- formSignal
          .map(_.error)
          .distinct
          .map(_.map(msg => p(cls := "error", role := "alert", msg)))
      )
    )

  private def dashboard(rt: Runtime[AppState, Msg]): HtmlElement =
    val user = rt.store.signal.map(_.user).distinct
    div(
      cls := "dashboard",
      h1("lm-bot"),
      child.maybe <-- user.map(_.map(u => p(s"Signed in as ${u.displayName}"))),
      button(
        "Sign out",
        onClick.mapTo(Msg.LogoutRequested) --> (m => rt.dispatch(m))
      ),
      AccountsView(rt),
      MonitorsView(rt)
    )
