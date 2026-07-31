package lmbot.frontend.view

import com.raquo.laminar.api.L.*
import lmbot.frontend.elm.Runtime
import lmbot.frontend.{AppState, DeleteConfirmation, LoadState, Msg}
import lmbot.shared.domain.{AccountId, AccountStatus, AccountView}

/** Rendering only. Handlers do exactly one thing: send a message (spec §5.6).
  *
  * Named `AccountsView` rather than `AccountView` — the file the plan names —
  * because this object necessarily imports `lmbot.shared.domain.AccountView` to
  * render one, and a same-named object would collide with that import in this
  * very file.
  */
object AccountsView:

  def apply(rt: Runtime[AppState, Msg]): HtmlElement =
    div(
      cls := "accounts",
      h2("Luxmed accounts"),
      linkForm(rt),
      child <-- rt.store.signal.map(_.accounts).distinct.map {
        case LoadState.NotAsked | LoadState.Loading =>
          p(cls := "loading", "Loading accounts…")
        case LoadState.Failed(message) =>
          p(cls := "error", role := "alert", message)
        case LoadState.Loaded(accounts) =>
          accountsList(rt, accounts)
      }
    )

  private def linkForm(rt: Runtime[AppState, Msg]): HtmlElement =
    val formSignal = rt.store.signal.map(_.linkForm).distinct
    form(
      cls := "link-account",
      onSubmit.preventDefault.mapTo(Msg.LinkAccountSubmitted) --> (m =>
        rt.dispatch(m)
      ),
      label(
        "Label",
        input(
          tpe := "text",
          value <-- formSignal.map(_.label).distinct,
          onInput.mapToValue --> (v => rt.dispatch(Msg.LinkLabelChanged(v)))
        )
      ),
      label(
        "Username",
        input(
          tpe := "text",
          autoComplete := "username",
          value <-- formSignal.map(_.username).distinct,
          onInput.mapToValue --> (v => rt.dispatch(Msg.LinkUsernameChanged(v)))
        )
      ),
      label(
        "Password",
        input(
          tpe := "password",
          autoComplete := "new-password",
          value <-- formSignal.map(_.password).distinct,
          onInput.mapToValue --> (v => rt.dispatch(Msg.LinkPasswordChanged(v)))
        )
      ),
      button(
        tpe := "submit",
        disabled <-- formSignal.map(_.submitting).distinct,
        child.text <-- formSignal
          .map(f => if f.submitting then "Linking…" else "Link account")
          .distinct
      ),
      child.maybe <-- formSignal
        .map(_.error)
        .distinct
        .map(_.map(msg => p(cls := "error", role := "alert", msg)))
    )

  private def accountsList(
      rt: Runtime[AppState, Msg],
      accounts: List[AccountView]
  ): HtmlElement =
    if accounts.isEmpty then p(cls := "placeholder", "No accounts linked yet.")
    else
      ul(
        cls := "account-list",
        accounts.map(account => accountItem(rt, account))
      )

  private def accountItem(
      rt: Runtime[AppState, Msg],
      account: AccountView
  ): HtmlElement =
    val confirmSignal = rt.store.signal
      .map(_.deleteConfirmation.filter(_.accountId == account.id))
      .distinct
    li(
      cls := "account",
      span(cls := "label", account.label),
      span(cls := "username", account.username),
      span(cls := "status", statusText(account.status)),
      account.statusReason
        .map(reason => span(cls := "status-reason", reason))
        .toList,
      span(cls := "last-login", lastLoginText(account.lastSuccessfulLogin)),
      child <-- confirmSignal.map {
        case None               => deleteButton(rt, account.id)
        case Some(confirmation) => confirmDeletion(rt, confirmation)
      }
    )

  private def deleteButton(
      rt: Runtime[AppState, Msg],
      id: AccountId
  ): HtmlElement =
    button(
      "Delete",
      onClick.mapTo(Msg.DeleteAccountRequested(id)) --> (m => rt.dispatch(m))
    )

  private def confirmDeletion(
      rt: Runtime[AppState, Msg],
      confirmation: DeleteConfirmation
  ): HtmlElement =
    div(
      cls := "confirm-delete",
      p("Deleting this account will also delete its monitors."),
      button(
        "Cancel",
        disabled := confirmation.submitting,
        onClick.mapTo(Msg.DeleteCancelled) --> (m => rt.dispatch(m))
      ),
      button(
        "Confirm delete",
        disabled := confirmation.submitting,
        onClick.mapTo(Msg.DeleteConfirmed) --> (m => rt.dispatch(m))
      ),
      confirmation.error
        .map(msg => p(cls := "error", role := "alert", msg))
        .toList
    )

  private def statusText(status: AccountStatus): String = status match
    case AccountStatus.Active     => "Active"
    case AccountStatus.AuthFailed => "Needs attention"
    case AccountStatus.Disabled   => "Disabled"

  private def lastLoginText(instant: Option[java.time.Instant]): String =
    instant.map(_.toString).getOrElse("Never")
