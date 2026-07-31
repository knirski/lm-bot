package lmbot.frontend

import lmbot.frontend.api.ApiClient
import lmbot.shared.api.ApiError
import lmbot.shared.domain.{
  AccountId,
  AccountStatus,
  AccountView,
  Role,
  UserView
}
import sttp.model.Uri

/** `update` is pure, so this whole suite runs with no DOM and no runtime. */
class UpdateTest extends munit.FunSuite:

  // ApiClient's backend is lazy, so this never touches fetch: these tests
  // exercise `update` alone and no effect is ever run.
  private val api = ApiClient(Uri.unsafeParse("http://localhost"))
  private val update = Update(api)

  private val alice =
    UserView(1L, "alice", "Alice", Role.User, telegramLinked = false)

  private val account1 = AccountView(
    AccountId(1L),
    "Main",
    "user1",
    AccountStatus.Active,
    None,
    None
  )
  private val account2 = AccountView(
    AccountId(2L),
    "Backup",
    "user2",
    AccountStatus.AuthFailed,
    Some("locked: too many attempts"),
    None
  )

  private def filledLinkForm(state: AppState): AppState =
    val withLabel = update(state, Msg.LinkLabelChanged("Main")).state
    val withUsername =
      update(withLabel, Msg.LinkUsernameChanged("user1")).state
    update(withUsername, Msg.LinkPasswordChanged("pw")).state

  test("the app starts on the login screen, booting, with an empty form"):
    val s = AppState.initial
    assertEquals(s.screen, Screen.Login)
    assertEquals(s.booting, true)
    assertEquals(s.user, None)
    assertEquals(s.login.username, "")
    assertEquals(s.login.password, "")
    assertEquals(s.login.error, None)

  test("typing updates only the field typed into"):
    val afterUser = update(AppState.initial, Msg.UsernameChanged("bob")).state
    assertEquals(afterUser.login.username, "bob")
    assertEquals(afterUser.login.password, "")

    val afterPass = update(afterUser, Msg.PasswordChanged("pw")).state
    assertEquals(afterPass.login.username, "bob")
    assertEquals(afterPass.login.password, "pw")

  test(
    "submitting marks the form busy, clears any old error, and emits one effect"
  ):
    val filled = update(
      update(AppState.initial, Msg.UsernameChanged("bob")).state,
      Msg.PasswordChanged("pw")
    ).state
    val withError =
      filled.copy(login = filled.login.copy(error = Some("previously wrong")))

    val t = update(withError, Msg.LoginSubmitted)

    assertEquals(t.state.login.submitting, true)
    assertEquals(t.state.login.error, None)
    assertEquals(t.effects.size, 1)

  test("submitting an incomplete form is rejected without a request"):
    val t = update(AppState.initial, Msg.LoginSubmitted)

    assertEquals(t.effects, Nil)
    assertEquals(t.state.login.submitting, false)
    assert(t.state.login.error.isDefined)

  test("a double submit does not fire a second request"):
    val filled = update(
      update(AppState.initial, Msg.UsernameChanged("bob")).state,
      Msg.PasswordChanged("pw")
    ).state
    val busy = update(filled, Msg.LoginSubmitted).state

    val t = update(busy, Msg.LoginSubmitted)
    assertEquals(t.effects, Nil)

  test("a successful login moves to the dashboard and forgets the password"):
    val filled = update(
      update(AppState.initial, Msg.UsernameChanged("bob")).state,
      Msg.PasswordChanged("pw")
    ).state
    val busy = update(filled, Msg.LoginSubmitted).state

    val s = update(busy, Msg.LoginSucceeded(alice)).state

    assertEquals(s.screen, Screen.Dashboard)
    assertEquals(s.user, Some(alice))
    assertEquals(s.login.submitting, false)
    assertEquals(
      s.login.password,
      "",
      "the password must not linger in memory after login"
    )

  test("a failed login shows the message and stays put, keeping the username"):
    val filled = update(
      update(AppState.initial, Msg.UsernameChanged("bob")).state,
      Msg.PasswordChanged("pw")
    ).state
    val busy = update(filled, Msg.LoginSubmitted).state

    val s = update(busy, Msg.LoginFailed(ApiError.Unauthorized)).state

    assertEquals(s.screen, Screen.Login)
    assertEquals(s.user, None)
    assertEquals(s.login.submitting, false)
    assertEquals(
      s.login.username,
      "bob",
      "retyping the username after a typo in the password is rude"
    )
    assertEquals(s.login.password, "")
    assert(s.login.error.isDefined)

  test("a restored session skips the login screen"):
    val s = update(AppState.initial, Msg.SessionRestored(alice)).state
    assertEquals(s.screen, Screen.Dashboard)
    assertEquals(s.user, Some(alice))
    assertEquals(s.booting, false)

  test("no session leaves the user on the login screen, no longer booting"):
    val s = update(AppState.initial, Msg.SessionAbsent).state
    assertEquals(s.screen, Screen.Login)
    assertEquals(s.booting, false)
    assertEquals(
      s.login.error,
      None,
      "arriving unauthenticated is not an error to show"
    )

  test("logging out returns to a clean login screen"):
    val dashboard = update(AppState.initial, Msg.SessionRestored(alice)).state

    val requested = update(dashboard, Msg.LogoutRequested)
    assertEquals(requested.effects.size, 1)

    val s = update(requested.state, Msg.LoggedOut).state
    assertEquals(s.screen, Screen.Login)
    assertEquals(s.user, None)
    assertEquals(s.login.username, "")
    assertEquals(s.login.password, "")

  test("navigating to accounts starts loading and emits one effect"):
    val t = update(AppState.initial, Msg.AccountsRequested)
    assertEquals(t.state.accounts, LoadState.Loading)
    assertEquals(t.effects.size, 1)

  test("a successful login also starts loading accounts"):
    val t = update(AppState.initial, Msg.LoginSucceeded(alice))
    assertEquals(t.state.screen, Screen.Dashboard)
    assertEquals(t.state.accounts, LoadState.Loading)
    assertEquals(t.effects.size, 1)

  test("a restored session also starts loading accounts"):
    val t = update(AppState.initial, Msg.SessionRestored(alice))
    assertEquals(t.state.accounts, LoadState.Loading)
    assertEquals(t.effects.size, 1)

  test("loading accounts successfully stores the list, status reason included"):
    val loading = update(AppState.initial, Msg.AccountsRequested).state
    val s =
      update(loading, Msg.AccountsLoaded(List(account1, account2))).state
    assertEquals(s.accounts, LoadState.Loaded(List(account1, account2)))
    val loaded = s.accounts.asInstanceOf[LoadState.Loaded[List[AccountView]]]
    assertEquals(loaded.value(1).statusReason, account2.statusReason)

  test("a failed accounts load shows the server's message"):
    val loading = update(AppState.initial, Msg.AccountsRequested).state
    val s = update(
      loading,
      Msg.AccountsLoadFailed(ApiError.Validation("luxmed unreachable"))
    ).state
    assertEquals(s.accounts, LoadState.Failed("luxmed unreachable"))

  test("submitting an incomplete link form is rejected without a request"):
    val t = update(AppState.initial, Msg.LinkAccountSubmitted)
    assertEquals(t.effects, Nil)
    assertEquals(t.state.linkForm.submitting, false)
    assert(t.state.linkForm.error.isDefined)

  test(
    "submitting a filled link form marks it busy, clears any old error, and emits one effect"
  ):
    val filled = filledLinkForm(AppState.initial)
    val withError = filled.copy(linkForm =
      filled.linkForm.copy(error = Some("previously wrong"))
    )

    val t = update(withError, Msg.LinkAccountSubmitted)

    assertEquals(t.state.linkForm.submitting, true)
    assertEquals(t.state.linkForm.error, None)
    assertEquals(t.effects.size, 1)

  test("a double submit of the link form does not fire a second request"):
    val filled = filledLinkForm(AppState.initial)
    val busy = update(filled, Msg.LinkAccountSubmitted).state

    val t = update(busy, Msg.LinkAccountSubmitted)
    assertEquals(t.effects, Nil)

  test("linking succeeds: the new account is appended and the form resets"):
    val loaded =
      update(AppState.initial, Msg.AccountsLoaded(List(account1))).state
    val filled = filledLinkForm(loaded)
    val busy = update(filled, Msg.LinkAccountSubmitted).state
    val newAccount =
      AccountView(
        AccountId(3L),
        "Second",
        "user2",
        AccountStatus.Active,
        None,
        None
      )

    val s = update(busy, Msg.AccountLinked(newAccount)).state

    assertEquals(s.accounts, LoadState.Loaded(List(account1, newAccount)))
    assertEquals(s.linkForm, LinkAccountForm())
    assertEquals(
      s.linkForm.password,
      "",
      "the Luxmed password must not linger in memory after a successful link"
    )

  test("linking fails: the password is cleared but label/username are kept"):
    val filled = filledLinkForm(AppState.initial)
    val busy = update(filled, Msg.LinkAccountSubmitted).state

    val s = update(
      busy,
      Msg.AccountLinkFailed(
        ApiError.Conflict("Luxmed requires a security challenge")
      )
    ).state

    assertEquals(s.linkForm.submitting, false)
    assertEquals(s.linkForm.label, "Main")
    assertEquals(s.linkForm.username, "user1")
    assertEquals(
      s.linkForm.password,
      "",
      "a failed link attempt must not leave the Luxmed password in memory either"
    )
    assertEquals(
      s.linkForm.error,
      Some("Luxmed requires a security challenge"),
      "the server's own explanation must be shown, not a guessed one"
    )

  test("requesting a delete opens a confirmation naming the account"):
    val loaded =
      update(AppState.initial, Msg.AccountsLoaded(List(account1))).state
    val t = update(loaded, Msg.DeleteAccountRequested(account1.id))
    assertEquals(t.effects, Nil)
    assertEquals(
      t.state.deleteConfirmation,
      Some(DeleteConfirmation(account1.id))
    )

  test("cancelling a delete confirmation clears it without a request"):
    val requested =
      update(AppState.initial, Msg.DeleteAccountRequested(account1.id)).state
    val t = update(requested, Msg.DeleteCancelled)
    assertEquals(t.effects, Nil)
    assertEquals(t.state.deleteConfirmation, None)

  test(
    "confirming a delete fires exactly one request and marks the confirmation busy"
  ):
    val requested =
      update(AppState.initial, Msg.DeleteAccountRequested(account1.id)).state
    val t = update(requested, Msg.DeleteConfirmed)
    assertEquals(t.effects.size, 1)
    assertEquals(t.state.deleteConfirmation.map(_.submitting), Some(true))

  test("confirming a delete twice does not fire a second request"):
    val requested =
      update(AppState.initial, Msg.DeleteAccountRequested(account1.id)).state
    val busy = update(requested, Msg.DeleteConfirmed).state

    val t = update(busy, Msg.DeleteConfirmed)
    assertEquals(t.effects, Nil)

  test("confirming a delete without an open confirmation does nothing"):
    val t = update(AppState.initial, Msg.DeleteConfirmed)
    assertEquals(t.effects, Nil)
    assertEquals(t.state.deleteConfirmation, None)

  test("a successful delete removes the account and closes the confirmation"):
    val loaded =
      update(
        AppState.initial,
        Msg.AccountsLoaded(List(account1, account2))
      ).state
    val requested =
      update(loaded, Msg.DeleteAccountRequested(account1.id)).state
    val busy = update(requested, Msg.DeleteConfirmed).state

    val s = update(busy, Msg.AccountDeleted(account1.id)).state

    assertEquals(s.accounts, LoadState.Loaded(List(account2)))
    assertEquals(s.deleteConfirmation, None)

  test("a failed delete keeps the confirmation open with the server's message"):
    val requested =
      update(AppState.initial, Msg.DeleteAccountRequested(account1.id)).state
    val busy = update(requested, Msg.DeleteConfirmed).state

    val s = update(
      busy,
      Msg.AccountDeleteFailed(ApiError.Unexpected("db unavailable"))
    ).state

    assertEquals(s.deleteConfirmation.map(_.accountId), Some(account1.id))
    assertEquals(s.deleteConfirmation.map(_.submitting), Some(false))
    assert(s.deleteConfirmation.flatMap(_.error).isDefined)
