package lmbot.frontend

import java.time.{DayOfWeek, Instant, LocalDate, LocalTime}

import lmbot.frontend.api.ApiClient
import lmbot.shared.api.ApiError
import lmbot.shared.domain.{
  AccountId,
  AccountStatus,
  AccountView,
  DictionaryCity,
  DictionaryDoctor,
  DictionaryFacility,
  DictionaryService,
  FacilitiesDoctorsResponse,
  MonitorId,
  MonitorState,
  MonitorView,
  NamedId,
  Role,
  UserId,
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
    UserView(UserId(1L), "alice", "Alice", Role.User, telegramLinked = false)

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

  // --- Monitor fixtures ---

  private val warsaw = NamedId(1L, "Warszawa")
  private val krakow = NamedId(2L, "Kraków")
  private val cityChoices =
    List(
      DictionaryCity(warsaw.id, warsaw.name),
      DictionaryCity(krakow.id, krakow.name)
    )

  private val orthopaedist = NamedId(10L, "Ortopeda")
  private val dermatologist = NamedId(11L, "Dermatolog")
  private val serviceChoices = List(
    DictionaryService(orthopaedist.id, orthopaedist.name),
    DictionaryService(dermatologist.id, dermatologist.name)
  )

  private val clinic = NamedId(100L, "Puławska")
  private val doctor = NamedId(200L, "Dr Kowalski")
  private val providerChoices = FacilitiesDoctorsResponse(
    facilities = List(DictionaryFacility(clinic.id, clinic.name)),
    doctors = List(DictionaryDoctor(doctor.id, doctor.name))
  )

  private val august = LocalDate.of(2026, 8, 1)
  private val september = LocalDate.of(2026, 9, 1)
  private val morning = LocalTime.of(8, 0)
  private val evening = LocalTime.of(18, 0)

  private val monitor1 = MonitorView(
    id = MonitorId(1L),
    accountId = account1.id,
    name = "Knee",
    state = MonitorState.Active,
    city = warsaw,
    service = orthopaedist,
    facilities = Nil,
    doctors = Nil,
    dateFrom = august,
    dateTo = september,
    timeFrom = morning,
    timeTo = evening,
    daysOfWeek = List(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
    autoBook = false,
    intervalMinutes = 10,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH
  )

  private val monitor2 = monitor1.copy(
    id = MonitorId(2L),
    accountId = account2.id,
    name = "Skin",
    state = MonitorState.Paused,
    service = dermatologist
  )

  /** Builds the `AccountsLoaded` that `state`'s own most recent
    * `AccountsRequested` would receive — i.e. one the reducer must accept.
    * Constructing a message that answers a *different* generation is exactly
    * how the staleness tests below prove a superseded response is dropped.
    */
  private def accountsLoaded(
      state: AppState,
      accounts: List[AccountView]
  ): Msg =
    Msg.AccountsLoaded(
      accounts,
      state.dashboardGeneration,
      state.accountsGeneration
    )

  private def accountsLoadFailed(state: AppState, error: ApiError): Msg =
    Msg.AccountsLoadFailed(
      error,
      state.dashboardGeneration,
      state.accountsGeneration
    )

  private def monitorsLoaded(
      state: AppState,
      monitors: List[MonitorView]
  ): Msg =
    Msg.MonitorsLoaded(
      monitors,
      state.dashboardGeneration,
      state.monitorsGeneration
    )

  private def monitorsLoadFailed(state: AppState, error: ApiError): Msg =
    Msg.MonitorsLoadFailed(
      error,
      state.dashboardGeneration,
      state.monitorsGeneration
    )

  /** A dashboard with two linked accounts and one stored monitor. */
  private val dashboardState: AppState =
    val restored = update(AppState.initial, Msg.SessionRestored(alice)).state
    val withAccounts =
      update(restored, accountsLoaded(restored, List(account1, account2))).state
    update(withAccounts, monitorsLoaded(withAccounts, List(monitor1))).state

  private def form(state: AppState): MonitorForm =
    state.monitorForm.getOrElse(fail("no monitor form is open"))

  private def monitors(state: AppState): List[MonitorView] =
    state.monitors match
      case LoadState.Loaded(value) => value
      case other                   => fail(s"monitors are not loaded: $other")

  private def opened: AppState =
    update(dashboardState, Msg.MonitorCreateStarted).state

  /** Named, with an account chosen and both account-scoped dictionaries in. */
  private def withAccount: AppState =
    val named = update(opened, Msg.MonitorNameChanged("Knee")).state
    val owned = update(named, Msg.MonitorAccountSelected(account1.id)).state
    val cities = update(owned, Msg.CitiesLoaded(account1.id, cityChoices)).state
    update(cities, Msg.ServicesLoaded(account1.id, serviceChoices)).state

  /** …plus a city and a service, so the providers are in as well. */
  private def withCriteria: AppState =
    val city = update(withAccount, Msg.MonitorCitySelected(warsaw)).state
    val service = update(city, Msg.MonitorServiceSelected(orthopaedist)).state
    update(
      service,
      Msg.ProvidersLoaded(
        account1.id,
        warsaw.id,
        orthopaedist.id,
        providerChoices
      )
    ).state

  private def withSchedule(state: AppState): AppState =
    val answers = List(
      Msg.MonitorDateFromChanged("2026-08-01"),
      Msg.MonitorDateToChanged("2026-09-01"),
      Msg.MonitorTimeFromChanged("08:00"),
      Msg.MonitorTimeToChanged("18:00"),
      Msg.MonitorDayToggled(DayOfWeek.WEDNESDAY),
      Msg.MonitorDayToggled(DayOfWeek.MONDAY)
    )
    answers.foldLeft(state)((s, msg) => update(s, msg).state)

  /** Everything the server needs; `toDraft` is `Some` here. */
  private def ready: AppState = withSchedule(withCriteria)

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

  test("logging out leaves nothing of the previous user behind"):
    // A shared browser is the whole point of this test: everything one user
    // loaded or half-typed has to be gone, not just the three fields the login
    // screen happens to read.
    val afterRestore =
      update(AppState.initial, Msg.SessionRestored(alice)).state
    val busy = List(
      accountsLoaded(afterRestore, List(account1, account2)),
      monitorsLoaded(afterRestore, List(monitor1, monitor2)),
      Msg.LinkLabelChanged("Main"),
      Msg.LinkUsernameChanged("user1"),
      Msg.LinkPasswordChanged("luxmed-secret"),
      Msg.DeleteAccountRequested(account2.id),
      Msg.MonitorCreateStarted,
      Msg.MonitorNameChanged("Knee"),
      Msg.MonitorPauseRequested(monitor1.id),
      Msg.MonitorDeleteRequested(monitor2.id)
    ).foldLeft(afterRestore): (s, msg) =>
      update(s, msg).state
    assert(busy.monitorForm.isDefined, "the fixture must actually be dirty")
    assert(busy.monitorAction.isDefined)
    assert(busy.monitorDeleteConfirmation.isDefined)

    val s = update(busy, Msg.LoggedOut).state

    assertEquals(
      s,
      AppState.initial.copy(booting = false, dashboardGeneration = 1),
      "every field must be back to its initial value, the new ones " +
        "included, except dashboardGeneration — that one must keep " +
        "advancing so a stale response from this session can never land " +
        "on the next one"
    )

  test("navigating to accounts starts loading and emits one effect"):
    val t = update(AppState.initial, Msg.AccountsRequested)
    assertEquals(t.state.accounts, LoadState.Loading)
    assertEquals(t.effects.size, 1)

  test("a successful login also starts loading accounts and monitors"):
    val t = update(AppState.initial, Msg.LoginSucceeded(alice))
    assertEquals(t.state.screen, Screen.Dashboard)
    assertEquals(t.state.accounts, LoadState.Loading)
    assertEquals(t.state.monitors, LoadState.Loading)
    assertEquals(
      t.effects.size,
      2,
      "the dashboard needs both lists, so neither request may be dropped"
    )

  test("a restored session also starts loading accounts and monitors"):
    val t = update(AppState.initial, Msg.SessionRestored(alice))
    assertEquals(t.state.accounts, LoadState.Loading)
    assertEquals(t.state.monitors, LoadState.Loading)
    assertEquals(t.effects.size, 2)

  test("loading accounts successfully stores the list, status reason included"):
    val loading = update(AppState.initial, Msg.AccountsRequested).state
    val s =
      update(loading, accountsLoaded(loading, List(account1, account2))).state
    assertEquals(s.accounts, LoadState.Loaded(List(account1, account2)))

  test("a failed accounts load shows the server's message"):
    val loading = update(AppState.initial, Msg.AccountsRequested).state
    val s = update(
      loading,
      accountsLoadFailed(loading, ApiError.Validation("luxmed unreachable"))
    ).state
    assertEquals(s.accounts, LoadState.Failed("luxmed unreachable"))

  test("a superseded accounts response is dropped, not applied"):
    val loading = update(AppState.initial, Msg.AccountsRequested).state
    // A second request supersedes the first: the response the first request
    // is still waiting on must now land on nothing.
    val superseded = update(loading, Msg.AccountsRequested).state
    val s = update(superseded, accountsLoaded(loading, List(account1))).state
    assertEquals(s.accounts, LoadState.Loading)

  test(
    "an accounts response from a session that has since logged out is dropped"
  ):
    val loading = update(AppState.initial, Msg.AccountsRequested).state
    val loggedOut = update(loading, Msg.LoggedOut).state
    val restored = update(loggedOut, Msg.SessionRestored(alice)).state
    // The new session's own request coincidentally reuses the same
    // accountsGeneration (1) the old one had — only dashboardGeneration
    // tells them apart.
    assertEquals(restored.accountsGeneration, loading.accountsGeneration)
    val s = update(restored, accountsLoaded(loading, List(account1))).state
    assertEquals(s.accounts, LoadState.Loading)

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
      update(
        AppState.initial,
        accountsLoaded(AppState.initial, List(account1))
      ).state
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
      update(
        AppState.initial,
        accountsLoaded(AppState.initial, List(account1))
      ).state
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
        accountsLoaded(AppState.initial, List(account1, account2))
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

  test("deleting an account drops the monitors the server deletes with it"):
    val loaded =
      update(
        dashboardState,
        monitorsLoaded(dashboardState, List(monitor1, monitor2))
      ).state

    val s = update(loaded, Msg.AccountDeleted(account1.id)).state

    assertEquals(
      monitors(s).map(_.id),
      List(monitor2.id),
      "the cascade is server-side, so the list must not keep showing the monitor"
    )

  test("deleting an account closes a form that was creating a monitor for it"):
    val creating = List(
      Msg.MonitorCreateStarted,
      Msg.MonitorNameChanged("Knee"),
      Msg.MonitorAccountSelected(account1.id)
    ).foldLeft(dashboardState)((s, msg) => update(s, msg).state)

    val t = update(creating, Msg.AccountDeleted(account1.id))

    assertEquals(
      t.state.monitorForm,
      None,
      "a form for a deleted account looks unanswered but would still submit"
    )
    assertEquals(t.effects, Nil)

  test("deleting an account closes an edit of one of its monitors"):
    val editing =
      update(dashboardState, Msg.MonitorEditStarted(monitor1)).state
    assertEquals(form(editing).accountId, Some(account1.id))

    val s = update(editing, Msg.AccountDeleted(account1.id)).state

    assertEquals(
      s.monitorForm,
      None,
      "the monitor being edited was cascade-deleted with its account"
    )

  test(
    "deleting an account closes an edit of its monitor even if the form now targets another account"
  ):
    val editing =
      update(dashboardState, Msg.MonitorEditStarted(monitor1)).state
    assertEquals(form(editing).accountId, Some(account1.id))

    val reassigned =
      update(editing, Msg.MonitorAccountSelected(account2.id)).state
    assertEquals(form(reassigned).accountId, Some(account2.id))

    val s = update(reassigned, Msg.AccountDeleted(account1.id)).state

    assertEquals(
      s.monitorForm,
      None,
      "monitor1 was cascade-deleted with account1, even though the form's " +
        "account selector had since been changed to account2"
    )

  test("deleting an account leaves a form for another account alone"):
    val creating = List(
      Msg.MonitorCreateStarted,
      Msg.MonitorNameChanged("Skin"),
      Msg.MonitorAccountSelected(account2.id)
    ).foldLeft(dashboardState)((s, msg) => update(s, msg).state)

    val s = update(creating, Msg.AccountDeleted(account1.id)).state

    assertEquals(s.monitorForm.flatMap(_.accountId), Some(account2.id))
    assertEquals(s.monitorForm.map(_.name), Some("Skin"))

  test("deleting an account clears a pause in flight for its monitor"):
    val pausing =
      update(dashboardState, Msg.MonitorPauseRequested(monitor1.id)).state
    assert(pausing.monitorAction.isDefined)

    val s = update(pausing, Msg.AccountDeleted(account1.id)).state

    assertEquals(
      s.monitorAction,
      None,
      "the monitor it names went with the account"
    )

  test("deleting an account clears a delete confirmation for its monitor"):
    val confirming =
      update(dashboardState, Msg.MonitorDeleteRequested(monitor1.id)).state

    val s = update(confirming, Msg.AccountDeleted(account1.id)).state

    assertEquals(s.monitorDeleteConfirmation, None)

  test("deleting an account keeps state pointing at another account's monitor"):
    val loaded =
      update(
        dashboardState,
        monitorsLoaded(dashboardState, List(monitor1, monitor2))
      ).state
    val confirming =
      update(loaded, Msg.MonitorDeleteRequested(monitor2.id)).state
    val pausing =
      update(confirming, Msg.MonitorPauseRequested(monitor2.id)).state

    val s = update(pausing, Msg.AccountDeleted(account1.id)).state

    assertEquals(
      s.monitorDeleteConfirmation.map(_.monitorId),
      Some(monitor2.id),
      "monitor2 belongs to account2 and survives the cascade"
    )
    assertEquals(s.monitorAction.map(_.monitorId), Some(monitor2.id))

  // --- Monitors: list ---

  test("loading monitors successfully stores the list"):
    val loading = update(AppState.initial, Msg.MonitorsRequested)
    assertEquals(loading.state.monitors, LoadState.Loading)
    assertEquals(loading.effects.size, 1)

    val s =
      update(loading.state, monitorsLoaded(loading.state, List(monitor1))).state
    assertEquals(s.monitors, LoadState.Loaded(List(monitor1)))

  test("a failed monitors load shows the server's message"):
    val s = update(
      AppState.initial,
      monitorsLoadFailed(
        AppState.initial,
        ApiError.Unexpected("db unavailable")
      )
    ).state
    assertEquals(
      s.monitors,
      LoadState.Failed("Something went wrong: db unavailable")
    )

  test("a superseded monitors response is dropped, not applied"):
    val loading = update(AppState.initial, Msg.MonitorsRequested).state
    val superseded = update(loading, Msg.MonitorsRequested).state
    val s = update(superseded, monitorsLoaded(loading, List(monitor1))).state
    assertEquals(s.monitors, LoadState.Loading)

  // --- Monitors: the form ---

  test("starting a monitor with no linked account opens no form"):
    val noAccounts =
      update(dashboardState, accountsLoaded(dashboardState, Nil)).state

    val t = update(noAccounts, Msg.MonitorCreateStarted)

    assertEquals(
      t.state.monitorForm,
      None,
      "there is nothing to monitor without an account; the view offers linking one"
    )
    assertEquals(t.effects, Nil)

  test("starting a monitor opens an empty form and asks for nothing yet"):
    val t = update(dashboardState, Msg.MonitorCreateStarted)
    assertEquals(
      t.effects,
      Nil,
      "no account is chosen, so no dictionary can be asked for"
    )
    assertEquals(form(t.state).editingMonitorId, None)
    assertEquals(form(t.state).accountId, None)
    assertEquals(form(t.state).intervalMinutes, Some(10))
    assertEquals(form(t.state).cities, LoadState.NotAsked)
    assertEquals(form(t.state).services, LoadState.NotAsked)
    assertEquals(form(t.state).providers, LoadState.NotAsked)

  test("cancelling the form discards it"):
    val t = update(opened, Msg.MonitorFormCancelled)
    assertEquals(t.state.monitorForm, None)
    assertEquals(t.effects, Nil)

  test("choosing an account loads its cities and services together"):
    val t = update(opened, Msg.MonitorAccountSelected(account1.id))

    assertEquals(
      t.effects.size,
      2,
      "both dictionaries depend on the account alone, so both can be asked at once"
    )
    assertEquals(form(t.state).cities, LoadState.Loading)
    assertEquals(form(t.state).services, LoadState.Loading)
    assertEquals(
      form(t.state).providers,
      LoadState.NotAsked,
      "facilities and doctors still need a city and a service"
    )

  test("choosing only a city asks nothing about facilities or doctors"):
    val t = update(withAccount, Msg.MonitorCitySelected(warsaw))
    assertEquals(t.effects, Nil)
    assertEquals(form(t.state).providers, LoadState.NotAsked)

  test("a city and a service together load the facilities and doctors"):
    val city = update(withAccount, Msg.MonitorCitySelected(warsaw)).state

    val t = update(city, Msg.MonitorServiceSelected(orthopaedist))

    assertEquals(t.effects.size, 1)
    assertEquals(form(t.state).providers, LoadState.Loading)

  test("choosing a service before a city also waits for the city"):
    val service =
      update(withAccount, Msg.MonitorServiceSelected(orthopaedist))
    assertEquals(
      service.effects,
      Nil,
      "the form asks its questions in any order; the dictionary still needs both ids"
    )

    val t = update(service.state, Msg.MonitorCitySelected(warsaw))
    assertEquals(t.effects.size, 1)
    assertEquals(form(t.state).providers, LoadState.Loading)

  test("facilities and doctors may be left empty"):
    val t = update(ready, Msg.MonitorSubmitted)
    assertEquals(form(t.state).facilities, Nil)
    assertEquals(form(t.state).doctors, Nil)
    assertEquals(
      t.effects.size,
      1,
      "any facility and any doctor is a valid monitor"
    )

  test("facilities and doctors toggle on and off"):
    val ticked = update(withCriteria, Msg.MonitorFacilityToggled(clinic)).state
    val withDoctor = update(ticked, Msg.MonitorDoctorToggled(doctor)).state
    assertEquals(form(withDoctor).facilities, List(clinic))
    assertEquals(form(withDoctor).doctors, List(doctor))

    val unticked = update(withDoctor, Msg.MonitorFacilityToggled(clinic)).state
    assertEquals(form(unticked).facilities, Nil)
    assertEquals(form(unticked).doctors, List(doctor))

  test("changing the city keeps the service but re-asks for its providers"):
    val chosen = update(withCriteria, Msg.MonitorFacilityToggled(clinic)).state

    val t = update(chosen, Msg.MonitorCitySelected(krakow))

    assertEquals(form(t.state).city, Some(krakow))
    assertEquals(
      form(t.state).service,
      Some(orthopaedist),
      "services are account-scoped, so a new city does not invalidate the service"
    )
    assertEquals(
      form(t.state).facilities,
      Nil,
      "clinics belong to the city they were offered for"
    )
    assertEquals(form(t.state).doctors, Nil)
    assertEquals(form(t.state).providers, LoadState.Loading)
    assertEquals(t.effects.size, 1)

  test("changing the service clears the facility and doctor choices"):
    val chosen = update(withCriteria, Msg.MonitorFacilityToggled(clinic)).state
    val withDoctor = update(chosen, Msg.MonitorDoctorToggled(doctor)).state

    val t = update(withDoctor, Msg.MonitorServiceSelected(dermatologist))

    assertEquals(form(t.state).service, Some(dermatologist))
    assertEquals(form(t.state).facilities, Nil)
    assertEquals(form(t.state).doctors, Nil)
    assertEquals(form(t.state).providers, LoadState.Loading)
    assertEquals(t.effects.size, 1)

  test("changing the account clears every choice made under the old one"):
    val t = update(withCriteria, Msg.MonitorAccountSelected(account2.id))

    assertEquals(form(t.state).accountId, Some(account2.id))
    assertEquals(form(t.state).city, None)
    assertEquals(form(t.state).service, None)
    assertEquals(form(t.state).facilities, Nil)
    assertEquals(form(t.state).providers, LoadState.NotAsked)
    assertEquals(form(t.state).cities, LoadState.Loading)
    assertEquals(form(t.state).services, LoadState.Loading)
    assertEquals(
      t.effects.size,
      2,
      "the new account's own cities and services are what may be chosen now"
    )

  test("re-picking the same choice changes nothing and asks nothing"):
    val t = update(withCriteria, Msg.MonitorCitySelected(warsaw))
    assertEquals(t.effects, Nil)
    assertEquals(t.state, withCriteria)

  // --- Monitors: stale dictionary responses ---

  test("a cities response for a previously selected account is ignored"):
    val switched =
      update(withAccount, Msg.MonitorAccountSelected(account2.id)).state

    val s = update(switched, Msg.CitiesLoaded(account1.id, cityChoices)).state

    assertEquals(
      form(s).cities,
      LoadState.Loading,
      "a slow answer must not offer the previous account's cities"
    )

  test("a services response for a previously selected account is ignored"):
    val switched =
      update(withAccount, Msg.MonitorAccountSelected(account2.id)).state

    val s =
      update(switched, Msg.ServicesLoaded(account1.id, serviceChoices)).state

    assertEquals(form(s).services, LoadState.Loading)

  test("a facilities/doctors response for a previous city is ignored"):
    val switched = update(withCriteria, Msg.MonitorCitySelected(krakow)).state

    val s = update(
      switched,
      Msg.ProvidersLoaded(
        account1.id,
        warsaw.id,
        orthopaedist.id,
        providerChoices
      )
    ).state

    assertEquals(form(s).providers, LoadState.Loading)
    assertEquals(form(s).facilities, Nil)

  test("a facilities/doctors response for a previous service is ignored"):
    val switched =
      update(withCriteria, Msg.MonitorServiceSelected(dermatologist)).state

    val s = update(
      switched,
      Msg.ProvidersLoaded(
        account1.id,
        warsaw.id,
        orthopaedist.id,
        providerChoices
      )
    ).state

    assertEquals(form(s).providers, LoadState.Loading)

  test("a dictionary response with no form open is ignored"):
    val s =
      update(dashboardState, Msg.CitiesLoaded(account1.id, cityChoices)).state
    assertEquals(s.monitorForm, None)

  test("a failed dictionary load is shown and can be retried"):
    val failed = update(
      withAccount,
      Msg.CitiesLoadFailed(account1.id, ApiError.Unexpected("luxmed down"))
    )
    assertEquals(
      form(failed.state).cities,
      LoadState.Failed("Something went wrong: luxmed down")
    )

    val retried = update(failed.state, Msg.DictionaryRetryRequested)
    assertEquals(
      retried.effects.size,
      1,
      "only the dictionary that failed is asked again"
    )
    assertEquals(form(retried.state).cities, LoadState.Loading)
    assertEquals(form(retried.state).services, LoadState.Loaded(serviceChoices))

  // --- Monitors: validation ---

  test("an empty form cannot be submitted and lists every problem"):
    val t = update(opened, Msg.MonitorSubmitted)

    assertEquals(t.effects, Nil)
    assertEquals(form(t.state).submitting, false)
    val errors = form(t.state).errors
    assert(errors.contains("Choose a Luxmed account."))
    assert(errors.contains("Give the monitor a name."))
    assert(errors.contains("Choose a city."))
    assert(errors.contains("Choose a service."))
    assert(errors.contains("Choose a date range."))
    assert(errors.contains("Choose a time window."))
    assert(errors.contains("Select at least one day of the week."))

  test("a date range that ends before it starts blocks submission"):
    val reversed = update(ready, Msg.MonitorDateToChanged("2026-07-01")).state

    val t = update(reversed, Msg.MonitorSubmitted)

    assertEquals(t.effects, Nil)
    assert(
      form(t.state).errors
        .contains("The first date must not be after the last date.")
    )

  test("a time window that ends before it starts blocks submission"):
    val reversed = update(ready, Msg.MonitorTimeToChanged("07:00")).state

    val t = update(reversed, Msg.MonitorSubmitted)

    assertEquals(t.effects, Nil)
    assert(
      form(t.state).errors
        .contains("The earliest time must be before the latest time.")
    )

  test("unparsable date and time input is treated as no answer at all"):
    val cleared = update(ready, Msg.MonitorDateFromChanged("")).state

    assertEquals(form(cleared).dateFrom, None)
    val t = update(cleared, Msg.MonitorSubmitted)
    assertEquals(t.effects, Nil)
    assert(t.state.monitorForm.exists(_.errors.nonEmpty))

  test("clearing the days of the week blocks submission"):
    val noDays = List(
      Msg.MonitorDayToggled(DayOfWeek.MONDAY),
      Msg.MonitorDayToggled(DayOfWeek.WEDNESDAY)
    ).foldLeft(ready)((s, msg) => update(s, msg).state)
    assertEquals(form(noDays).daysOfWeek, Nil)

    val t = update(noDays, Msg.MonitorSubmitted)

    assertEquals(t.effects, Nil)
    assert(
      form(t.state).errors.contains("Select at least one day of the week.")
    )

  test("the interval starts at ten minutes and rejects four"):
    assertEquals(form(ready).intervalMinutes, Some(10))

    val tooOften = update(ready, Msg.MonitorIntervalChanged("4")).state
    val t = update(tooOften, Msg.MonitorSubmitted)

    assertEquals(form(t.state).intervalMinutes, Some(4))
    assertEquals(t.effects, Nil)
    assert(form(t.state).errors.exists(_.contains("5 minutes")))

    val fixed = update(tooOften, Msg.MonitorIntervalChanged("5")).state
    assertEquals(update(fixed, Msg.MonitorSubmitted).effects.size, 1)

  test("non-numeric interval text is unanswered, not a sentinel number"):
    val cleared = update(ready, Msg.MonitorIntervalChanged("abc")).state
    assertEquals(
      form(cleared).intervalMinutes,
      None,
      "unreadable text must not silently become 0 and get written back " +
        "into the input, fighting whatever the user is typing"
    )

    val t = update(cleared, Msg.MonitorSubmitted)
    assertEquals(t.effects, Nil)
    assert(form(t.state).errors.contains("Enter a number of minutes."))

  test("a nameless monitor cannot be submitted"):
    val nameless = update(ready, Msg.MonitorNameChanged("   ")).state

    val t = update(nameless, Msg.MonitorSubmitted)

    assertEquals(t.effects, Nil)
    assert(form(t.state).errors.contains("Give the monitor a name."))

  // --- Monitors: create ---

  test("submitting a complete form fires one request and marks it busy"):
    val t = update(ready, Msg.MonitorSubmitted)
    assertEquals(t.effects.size, 1)
    assertEquals(form(t.state).submitting, true)
    assertEquals(form(t.state).errors, Nil)

  test("a double submit does not fire a second monitor save request"):
    val busy = update(ready, Msg.MonitorSubmitted).state
    assertEquals(update(busy, Msg.MonitorSubmitted).effects, Nil)

  test("a created monitor appears in the list and closes the form"):
    val busy = update(ready, Msg.MonitorSubmitted).state
    val created = monitor1.copy(id = MonitorId(9L), name = "Knee")

    val s = update(busy, Msg.MonitorSaved(created)).state

    assertEquals(monitors(s).map(_.id), List(monitor1.id, created.id))
    assertEquals(s.monitorForm, None)

  test("a save while the list is unavailable asks for the list again"):
    val listFailed = update(
      ready,
      monitorsLoadFailed(ready, ApiError.Unexpected("db unavailable"))
    ).state
    val busy = update(listFailed, Msg.MonitorSubmitted).state

    val t = update(busy, Msg.MonitorSaved(monitor2))

    assertEquals(
      t.state.monitors,
      LoadState.Loading,
      "one saved monitor is not the whole list, and the failure must not be hidden"
    )
    assertEquals(t.effects.size, 1)
    assertEquals(t.state.monitorForm, None)

  test("a rejected save keeps the form open with the server's own reason"):
    val busy = update(ready, Msg.MonitorSubmitted).state

    val s = update(
      busy,
      Msg.MonitorSaveFailed(
        ApiError.Validation("Interval must be at least 5 minutes.")
      )
    ).state

    assertEquals(form(s).submitting, false)
    assertEquals(
      form(s).errors,
      List("Interval must be at least 5 minutes."),
      "the server is authoritative, so its wording is what is shown"
    )

  // --- Monitors: edit ---

  test("editing starts from the monitor's persisted ids and names"):
    val t = update(dashboardState, Msg.MonitorEditStarted(monitor1))

    assertEquals(form(t.state).editingMonitorId, Some(monitor1.id))
    assertEquals(form(t.state).accountId, Some(monitor1.accountId))
    assertEquals(form(t.state).name, monitor1.name)
    assertEquals(form(t.state).city, Some(warsaw))
    assertEquals(form(t.state).service, Some(orthopaedist))
    assertEquals(form(t.state).dateFrom, Some(monitor1.dateFrom))
    assertEquals(form(t.state).timeFrom, Some(monitor1.timeFrom))
    assertEquals(form(t.state).daysOfWeek, monitor1.daysOfWeek)
    assertEquals(form(t.state).intervalMinutes, Some(monitor1.intervalMinutes))
    assert(
      form(t.state).toDraft.isDefined,
      "a stored monitor is already valid, so it could be saved again unchanged"
    )

  test("editing loads the dictionaries needed to change any criterion"):
    val t = update(dashboardState, Msg.MonitorEditStarted(monitor1))
    assertEquals(
      t.effects.size,
      3,
      "the names are already in the form; these fetch the alternatives"
    )
    assertEquals(form(t.state).cities, LoadState.Loading)
    assertEquals(form(t.state).services, LoadState.Loading)
    assertEquals(form(t.state).providers, LoadState.Loading)

  test("an edit replaces the monitor in place and preserves its state"):
    val editing = update(dashboardState, Msg.MonitorEditStarted(monitor1)).state
    val respecified =
      update(editing, Msg.MonitorServiceSelected(dermatologist)).state
    val t = update(respecified, Msg.MonitorSubmitted)
    assertEquals(t.effects.size, 1)

    val saved = monitor1.copy(
      service = dermatologist,
      state = MonitorState.Active
    )
    val s = update(t.state, Msg.MonitorSaved(saved)).state

    assertEquals(monitors(s).size, 1, "an edit adds no row")
    assertEquals(monitors(s).head.service, dermatologist)
    assertEquals(monitors(s).head.state, MonitorState.Active)
    assertEquals(s.monitorForm, None)

  test("an edit still has to be valid"):
    val editing = update(dashboardState, Msg.MonitorEditStarted(monitor1)).state
    val broken = update(editing, Msg.MonitorTimeToChanged("07:00")).state

    val t = update(broken, Msg.MonitorSubmitted)

    assertEquals(t.effects, Nil)
    assert(form(t.state).errors.nonEmpty)

  // --- Monitors: pause and resume ---

  test("pausing a monitor marks it busy and then updates its state"):
    val t = update(dashboardState, Msg.MonitorPauseRequested(monitor1.id))
    assertEquals(t.effects.size, 1)
    assertEquals(
      t.state.monitorAction,
      Some(MonitorAction(monitor1.id, MonitorState.Paused))
    )

    val s = update(
      t.state,
      Msg.MonitorStateChanged(monitor1.id, MonitorState.Paused)
    ).state

    assertEquals(monitors(s).head.state, MonitorState.Paused)
    assertEquals(s.monitorAction, None)

  test("resuming a paused monitor makes it active again"):
    val paused =
      update(
        dashboardState,
        monitorsLoaded(dashboardState, List(monitor2))
      ).state
    val t = update(paused, Msg.MonitorResumeRequested(monitor2.id))
    assertEquals(t.effects.size, 1)
    assertEquals(
      t.state.monitorAction.map(_.target),
      Some(MonitorState.Active)
    )

    val s = update(
      t.state,
      Msg.MonitorStateChanged(monitor2.id, MonitorState.Active)
    ).state

    assertEquals(monitors(s).head.state, MonitorState.Active)

  test("a second pause while one is in flight fires no second request"):
    val busy =
      update(dashboardState, Msg.MonitorPauseRequested(monitor1.id)).state
    assertEquals(
      update(busy, Msg.MonitorPauseRequested(monitor1.id)).effects,
      Nil
    )

  test("a failed pause leaves an inline error and the state untouched"):
    val busy =
      update(dashboardState, Msg.MonitorPauseRequested(monitor1.id)).state

    val s = update(
      busy,
      Msg.MonitorStateChangeFailed(
        monitor1.id,
        ApiError.Conflict(
          "The monitor cannot be paused from its current state."
        )
      )
    ).state

    assertEquals(s.monitorAction.map(_.monitorId), Some(monitor1.id))
    assertEquals(s.monitorAction.map(_.submitting), Some(false))
    assertEquals(
      s.monitorAction.flatMap(_.error),
      Some("The monitor cannot be paused from its current state.")
    )
    assertEquals(monitors(s).head.state, MonitorState.Active)

  // --- Monitors: delete ---

  test("requesting a monitor delete opens a confirmation naming it"):
    val t = update(dashboardState, Msg.MonitorDeleteRequested(monitor1.id))
    assertEquals(t.effects, Nil)
    assertEquals(
      t.state.monitorDeleteConfirmation,
      Some(MonitorDeleteConfirmation(monitor1.id))
    )

  test("cancelling a monitor delete clears it without a request"):
    val requested =
      update(dashboardState, Msg.MonitorDeleteRequested(monitor1.id)).state
    val t = update(requested, Msg.MonitorDeleteCancelled)
    assertEquals(t.effects, Nil)
    assertEquals(t.state.monitorDeleteConfirmation, None)

  test("confirming a monitor delete fires one request and marks it busy"):
    val requested =
      update(dashboardState, Msg.MonitorDeleteRequested(monitor1.id)).state
    val t = update(requested, Msg.MonitorDeleteConfirmed)
    assertEquals(t.effects.size, 1)
    assertEquals(
      t.state.monitorDeleteConfirmation.map(_.submitting),
      Some(true)
    )

    assertEquals(update(t.state, Msg.MonitorDeleteConfirmed).effects, Nil)

  test("confirming a monitor delete with nothing open does nothing"):
    val t = update(dashboardState, Msg.MonitorDeleteConfirmed)
    assertEquals(t.effects, Nil)
    assertEquals(t.state.monitorDeleteConfirmation, None)

  test("a successful monitor delete removes it and closes the confirmation"):
    val loaded =
      update(
        dashboardState,
        monitorsLoaded(dashboardState, List(monitor1, monitor2))
      ).state
    val requested =
      update(loaded, Msg.MonitorDeleteRequested(monitor1.id)).state
    val busy = update(requested, Msg.MonitorDeleteConfirmed).state

    val s = update(busy, Msg.MonitorDeleted(monitor1.id)).state

    assertEquals(monitors(s).map(_.id), List(monitor2.id))
    assertEquals(s.monitorDeleteConfirmation, None)

  test("a failed monitor delete keeps the confirmation with the reason"):
    val requested =
      update(dashboardState, Msg.MonitorDeleteRequested(monitor1.id)).state
    val busy = update(requested, Msg.MonitorDeleteConfirmed).state

    val s = update(
      busy,
      Msg.MonitorDeleteFailed(
        monitor1.id,
        ApiError.Unexpected("db unavailable")
      )
    ).state

    assertEquals(
      s.monitorDeleteConfirmation.map(_.monitorId),
      Some(monitor1.id)
    )
    assertEquals(s.monitorDeleteConfirmation.map(_.submitting), Some(false))
    assert(s.monitorDeleteConfirmation.flatMap(_.error).isDefined)
    assertEquals(monitors(s).size, 1, "a failed delete removes nothing")

  test(
    "requesting a delete for a different monitor while one is submitting does nothing"
  ):
    val loaded = update(
      dashboardState,
      monitorsLoaded(dashboardState, List(monitor1, monitor2))
    ).state
    val requested =
      update(loaded, Msg.MonitorDeleteRequested(monitor1.id)).state
    val busy = update(requested, Msg.MonitorDeleteConfirmed).state

    val t = update(busy, Msg.MonitorDeleteRequested(monitor2.id))

    assertEquals(t.effects, Nil)
    assertEquals(
      t.state.monitorDeleteConfirmation.map(_.monitorId),
      Some(monitor1.id),
      "a second row's delete click must not steal the in-flight confirmation"
    )

  test(
    "a delete result for one monitor does not touch a confirmation naming another"
  ):
    val loaded = update(
      dashboardState,
      monitorsLoaded(dashboardState, List(monitor1, monitor2))
    ).state
    val confirmingOther =
      update(loaded, Msg.MonitorDeleteRequested(monitor2.id)).state

    val deleted = update(confirmingOther, Msg.MonitorDeleted(monitor1.id)).state
    assertEquals(
      deleted.monitorDeleteConfirmation.map(_.monitorId),
      Some(monitor2.id),
      "a stale completion for a different monitor must not clear this one's " +
        "confirmation"
    )

    val failed = update(
      confirmingOther,
      Msg.MonitorDeleteFailed(
        monitor1.id,
        ApiError.Unexpected("db unavailable")
      )
    ).state
    assertEquals(
      failed.monitorDeleteConfirmation,
      confirmingOther.monitorDeleteConfirmation,
      "a stale failure for a different monitor must not attach its error here"
    )

  test(
    "a successful monitor delete also closes its own open edit form and pending action"
  ):
    val loaded = update(
      dashboardState,
      monitorsLoaded(dashboardState, List(monitor1, monitor2))
    ).state
    val editing = update(loaded, Msg.MonitorEditStarted(monitor1)).state
    val pausing = update(editing, Msg.MonitorPauseRequested(monitor1.id)).state
    val requested =
      update(pausing, Msg.MonitorDeleteRequested(monitor1.id)).state
    val busy = update(requested, Msg.MonitorDeleteConfirmed).state

    val s = update(busy, Msg.MonitorDeleted(monitor1.id)).state

    assertEquals(
      s.monitorForm,
      None,
      "the deleted monitor's own open edit form must close"
    )
    assertEquals(
      s.monitorAction,
      None,
      "a pause/resume in flight for the deleted monitor must clear too"
    )
