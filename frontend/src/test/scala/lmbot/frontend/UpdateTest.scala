package lmbot.frontend

import lmbot.shared.api.ApiError
import lmbot.shared.domain.{Role, UserView}

/** `update` is pure, so this whole suite runs with no DOM and no runtime. */
class UpdateTest extends munit.FunSuite:

  // ApiClient's backend is lazy, so this never touches fetch: these tests
  // exercise `update` alone and no effect is ever run.
  private val api    = lmbot.frontend.api.ApiClient(sttp.model.Uri.unsafeParse("http://localhost"))
  private val update = Update(api)

  private val alice = UserView(1L, "alice", "Alice", Role.User, telegramLinked = false)

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

  test("submitting marks the form busy, clears any old error, and emits one effect"):
    val filled = update(update(AppState.initial, Msg.UsernameChanged("bob")).state, Msg.PasswordChanged("pw")).state
    val withError = filled.copy(login = filled.login.copy(error = Some("previously wrong")))

    val t = update(withError, Msg.LoginSubmitted)

    assertEquals(t.state.login.submitting, true)
    assertEquals(t.state.login.error, None)
    assertEquals(t.asyncEffects.size, 1)

  test("submitting an incomplete form is rejected without a request"):
    val t = update(AppState.initial, Msg.LoginSubmitted)

    assertEquals(t.effects, Nil)
    assertEquals(t.asyncEffects, Nil)
    assertEquals(t.state.login.submitting, false)
    assert(t.state.login.error.isDefined)

  test("a double submit does not fire a second request"):
    val filled = update(update(AppState.initial, Msg.UsernameChanged("bob")).state, Msg.PasswordChanged("pw")).state
    val busy   = update(filled, Msg.LoginSubmitted).state

    val t = update(busy, Msg.LoginSubmitted)
    assertEquals(t.effects, Nil)
    assertEquals(t.asyncEffects, Nil)

  test("a successful login moves to the dashboard and forgets the password"):
    val filled = update(update(AppState.initial, Msg.UsernameChanged("bob")).state, Msg.PasswordChanged("pw")).state
    val busy   = update(filled, Msg.LoginSubmitted).state

    val s = update(busy, Msg.LoginSucceeded(alice)).state

    assertEquals(s.screen, Screen.Dashboard)
    assertEquals(s.user, Some(alice))
    assertEquals(s.login.submitting, false)
    assertEquals(s.login.password, "", "the password must not linger in memory after login")

  test("a failed login shows the message and stays put, keeping the username"):
    val filled = update(update(AppState.initial, Msg.UsernameChanged("bob")).state, Msg.PasswordChanged("pw")).state
    val busy   = update(filled, Msg.LoginSubmitted).state

    val s = update(busy, Msg.LoginFailed(ApiError.Unauthorized)).state

    assertEquals(s.screen, Screen.Login)
    assertEquals(s.user, None)
    assertEquals(s.login.submitting, false)
    assertEquals(s.login.username, "bob", "retyping the username after a typo in the password is rude")
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
    assertEquals(s.login.error, None, "arriving unauthenticated is not an error to show")

  test("logging out returns to a clean login screen"):
    val dashboard = update(AppState.initial, Msg.SessionRestored(alice)).state

    val requested = update(dashboard, Msg.LogoutRequested)
    assertEquals(requested.asyncEffects.size, 1)

    val s = update(requested.state, Msg.LoggedOut).state
    assertEquals(s.screen, Screen.Login)
    assertEquals(s.user, None)
    assertEquals(s.login.username, "")
    assertEquals(s.login.password, "")
