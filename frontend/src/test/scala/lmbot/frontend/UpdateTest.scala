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

  /** A dashboard with two linked accounts and one stored monitor. */
  private val dashboardState: AppState =
    val restored = update(AppState.initial, Msg.SessionRestored(alice)).state
    val withAccounts =
      update(restored, Msg.AccountsLoaded(List(account1, account2))).state
    update(withAccounts, Msg.MonitorsLoaded(List(monitor1))).state

  private def form(state: AppState): MonitorForm =
    state.monitorForm.getOrElse(fail("no monitor form is open"))

  private def monitors(state: AppState): List[MonitorView] =
    state.monitors match
      case LoadState.Loaded(value) => value
      case other                   => fail(s"monitors are not loaded: $other")

  private def onCityStep: AppState =
    val started = update(dashboardState, Msg.MonitorCreateStarted).state
    val named = update(started, Msg.MonitorNameChanged("Knee")).state
    val owned =
      update(named, Msg.MonitorAccountSelected(account1.id)).state
    val advanced = update(owned, Msg.MonitorStepAdvanced).state
    update(advanced, Msg.CitiesLoaded(account1.id, cityChoices)).state

  private def onServiceStep: AppState =
    val chosen = update(onCityStep, Msg.MonitorCitySelected(warsaw)).state
    val advanced = update(chosen, Msg.MonitorStepAdvanced).state
    update(advanced, Msg.ServicesLoaded(account1.id, serviceChoices)).state

  private def onProvidersStep: AppState =
    val chosen =
      update(onServiceStep, Msg.MonitorServiceSelected(orthopaedist)).state
    val advanced = update(chosen, Msg.MonitorStepAdvanced).state
    update(
      advanced,
      Msg.ProvidersLoaded(
        account1.id,
        warsaw.id,
        orthopaedist.id,
        providerChoices
      )
    ).state

  private def onScheduleStep: AppState =
    update(onProvidersStep, Msg.MonitorStepAdvanced).state

  private def withSchedule(state: AppState): AppState =
    val steps = List(
      Msg.MonitorDateFromChanged("2026-08-01"),
      Msg.MonitorDateToChanged("2026-09-01"),
      Msg.MonitorTimeFromChanged("08:00"),
      Msg.MonitorTimeToChanged("18:00"),
      Msg.MonitorDayToggled(DayOfWeek.WEDNESDAY),
      Msg.MonitorDayToggled(DayOfWeek.MONDAY)
    )
    steps.foldLeft(state)((s, msg) => update(s, msg).state)

  private def onReviewStep: AppState =
    update(withSchedule(onScheduleStep), Msg.MonitorStepAdvanced).state

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

  test("deleting an account drops the monitors the server deletes with it"):
    val loaded =
      update(dashboardState, Msg.MonitorsLoaded(List(monitor1, monitor2))).state

    val s = update(loaded, Msg.AccountDeleted(account1.id)).state

    assertEquals(
      monitors(s).map(_.id),
      List(monitor2.id),
      "the cascade is server-side, so the list must not keep showing the monitor"
    )

  // --- Monitors: list ---

  test("loading monitors successfully stores the list"):
    val loading = update(AppState.initial, Msg.MonitorsRequested)
    assertEquals(loading.state.monitors, LoadState.Loading)
    assertEquals(loading.effects.size, 1)

    val s = update(loading.state, Msg.MonitorsLoaded(List(monitor1))).state
    assertEquals(s.monitors, LoadState.Loaded(List(monitor1)))

  test("a failed monitors load shows the server's message"):
    val s = update(
      AppState.initial,
      Msg.MonitorsLoadFailed(ApiError.Unexpected("db unavailable"))
    ).state
    assertEquals(
      s.monitors,
      LoadState.Failed("Something went wrong: db unavailable")
    )

  // --- Monitors: wizard ---

  test("starting a monitor with no linked account opens no wizard"):
    val noAccounts = update(dashboardState, Msg.AccountsLoaded(Nil)).state

    val t = update(noAccounts, Msg.MonitorCreateStarted)

    assertEquals(
      t.state.monitorForm,
      None,
      "there is nothing to monitor without an account; the view offers linking one"
    )
    assertEquals(t.effects, Nil)

  test("starting a monitor opens the wizard on the account step"):
    val t = update(dashboardState, Msg.MonitorCreateStarted)
    assertEquals(t.effects, Nil, "nothing is known yet, so nothing is fetched")
    assertEquals(form(t.state).step, WizardStep.Account)
    assertEquals(form(t.state).editingMonitorId, None)
    assertEquals(form(t.state).intervalMinutes, 10)

  test("cancelling the wizard discards the form"):
    val started = update(dashboardState, Msg.MonitorCreateStarted).state
    val t = update(started, Msg.MonitorFormCancelled)
    assertEquals(t.state.monitorForm, None)
    assertEquals(t.effects, Nil)

  test("advancing without an account or a name says so and fetches nothing"):
    val started = update(dashboardState, Msg.MonitorCreateStarted).state

    val t = update(started, Msg.MonitorStepAdvanced)

    assertEquals(
      t.effects,
      Nil,
      "no dictionary may be asked about an account that has not been chosen"
    )
    assertEquals(form(t.state).step, WizardStep.Account)
    assert(form(t.state).errors.contains("Choose a Luxmed account."))
    assert(form(t.state).errors.contains("Give the monitor a name."))

  test("advancing from the account step loads that account's cities"):
    val started = update(dashboardState, Msg.MonitorCreateStarted).state
    val named = update(started, Msg.MonitorNameChanged("Knee")).state
    val owned = update(named, Msg.MonitorAccountSelected(account1.id)).state

    val t = update(owned, Msg.MonitorStepAdvanced)

    assertEquals(form(t.state).step, WizardStep.City)
    assertEquals(form(t.state).cities, LoadState.Loading)
    assertEquals(t.effects.size, 1)

  test("advancing without a city says so and fetches nothing"):
    val t = update(onCityStep, Msg.MonitorStepAdvanced)
    assertEquals(t.effects, Nil)
    assertEquals(form(t.state).step, WizardStep.City)
    assert(form(t.state).errors.contains("Choose a city."))

  test("advancing from the city step loads the account's services"):
    val chosen = update(onCityStep, Msg.MonitorCitySelected(warsaw)).state

    val t = update(chosen, Msg.MonitorStepAdvanced)

    assertEquals(form(t.state).step, WizardStep.Service)
    assertEquals(form(t.state).services, LoadState.Loading)
    assertEquals(t.effects.size, 1)

  test("advancing without a service says so and fetches nothing"):
    val t = update(onServiceStep, Msg.MonitorStepAdvanced)
    assertEquals(t.effects, Nil)
    assertEquals(form(t.state).step, WizardStep.Service)
    assert(form(t.state).errors.contains("Choose a service."))

  test(
    "advancing from the service step loads facilities and doctors for the city and service"
  ):
    val chosen =
      update(onServiceStep, Msg.MonitorServiceSelected(orthopaedist)).state

    val t = update(chosen, Msg.MonitorStepAdvanced)

    assertEquals(form(t.state).step, WizardStep.Providers)
    assertEquals(form(t.state).providers, LoadState.Loading)
    assertEquals(t.effects.size, 1)

  test("going back does not re-ask the questions already answered"):
    val t = update(onProvidersStep, Msg.MonitorStepReturned)
    assertEquals(form(t.state).step, WizardStep.Service)
    assertEquals(t.effects, Nil, "the services are already loaded")
    assertEquals(form(t.state).service, Some(orthopaedist))

  test("facilities and doctors may be left empty"):
    val t = update(onProvidersStep, Msg.MonitorStepAdvanced)
    assertEquals(form(t.state).facilities, Nil)
    assertEquals(form(t.state).doctors, Nil)
    assertEquals(
      form(t.state).step,
      WizardStep.Schedule,
      "any facility and any doctor is a valid monitor"
    )
    assertEquals(form(t.state).errors, Nil)

  test("facilities and doctors toggle on and off"):
    val ticked =
      update(onProvidersStep, Msg.MonitorFacilityToggled(clinic)).state
    val withDoctor = update(ticked, Msg.MonitorDoctorToggled(doctor)).state
    assertEquals(form(withDoctor).facilities, List(clinic))
    assertEquals(form(withDoctor).doctors, List(doctor))

    val unticked =
      update(withDoctor, Msg.MonitorFacilityToggled(clinic)).state
    assertEquals(form(unticked).facilities, Nil)
    assertEquals(form(unticked).doctors, List(doctor))

  test("changing the city clears the service, facility and doctor choices"):
    val chosen =
      update(onProvidersStep, Msg.MonitorFacilityToggled(clinic)).state
    val backToCity = List
      .fill(2)(Msg.MonitorStepReturned)
      .foldLeft(chosen)((s, msg) => update(s, msg).state)
    assertEquals(form(backToCity).step, WizardStep.City)

    val s = update(backToCity, Msg.MonitorCitySelected(krakow)).state

    assertEquals(form(s).city, Some(krakow))
    assertEquals(form(s).service, None)
    assertEquals(form(s).facilities, Nil)
    assertEquals(form(s).doctors, Nil)
    assertEquals(
      form(s).providers,
      LoadState.NotAsked,
      "facilities and doctors are looked up per city and service"
    )

  test("changing the service clears the facility and doctor choices"):
    val chosen =
      update(onProvidersStep, Msg.MonitorFacilityToggled(clinic)).state
    val withDoctor = update(chosen, Msg.MonitorDoctorToggled(doctor)).state
    val backToService = update(withDoctor, Msg.MonitorStepReturned).state

    val s =
      update(backToService, Msg.MonitorServiceSelected(dermatologist)).state

    assertEquals(form(s).service, Some(dermatologist))
    assertEquals(form(s).facilities, Nil)
    assertEquals(form(s).doctors, Nil)
    assertEquals(form(s).providers, LoadState.NotAsked)

  test("changing the account clears every choice made under the old one"):
    val backToStart = List
      .fill(3)(Msg.MonitorStepReturned)
      .foldLeft(onProvidersStep)((s, msg) => update(s, msg).state)
    assertEquals(form(backToStart).step, WizardStep.Account)
    assertEquals(
      update(backToStart, Msg.MonitorStepReturned).state,
      backToStart,
      "the first step has nowhere to go back to"
    )

    val s = update(backToStart, Msg.MonitorAccountSelected(account2.id)).state

    assertEquals(form(s).accountId, Some(account2.id))
    assertEquals(form(s).city, None)
    assertEquals(form(s).service, None)
    assertEquals(form(s).cities, LoadState.NotAsked)
    assertEquals(form(s).services, LoadState.NotAsked)
    assertEquals(form(s).providers, LoadState.NotAsked)

  // --- Monitors: stale dictionary responses ---

  test("a cities response for a previously selected account is ignored"):
    val onCity = onCityStep
    val backToAccount = update(onCity, Msg.MonitorStepReturned).state
    val switched =
      update(backToAccount, Msg.MonitorAccountSelected(account2.id)).state

    val s = update(switched, Msg.CitiesLoaded(account1.id, cityChoices)).state

    assertEquals(
      form(s).cities,
      LoadState.NotAsked,
      "a slow answer must not offer the previous account's cities"
    )

  test("a services response for a previously selected account is ignored"):
    val onService = onServiceStep
    val backToAccount = List
      .fill(2)(Msg.MonitorStepReturned)
      .foldLeft(onService)((s, msg) => update(s, msg).state)
    val switched =
      update(backToAccount, Msg.MonitorAccountSelected(account2.id)).state

    val s =
      update(switched, Msg.ServicesLoaded(account1.id, serviceChoices)).state

    assertEquals(form(s).services, LoadState.NotAsked)

  test("a facilities/doctors response for a previous city is ignored"):
    val backToCity = List
      .fill(2)(Msg.MonitorStepReturned)
      .foldLeft(onProvidersStep)((s, msg) => update(s, msg).state)
    val switched = update(backToCity, Msg.MonitorCitySelected(krakow)).state

    val s = update(
      switched,
      Msg.ProvidersLoaded(
        account1.id,
        warsaw.id,
        orthopaedist.id,
        providerChoices
      )
    ).state

    assertEquals(form(s).providers, LoadState.NotAsked)
    assertEquals(form(s).facilities, Nil)

  test("a facilities/doctors response for a previous service is ignored"):
    val backToService = update(onProvidersStep, Msg.MonitorStepReturned).state
    val switched =
      update(backToService, Msg.MonitorServiceSelected(dermatologist)).state

    val s = update(
      switched,
      Msg.ProvidersLoaded(
        account1.id,
        warsaw.id,
        orthopaedist.id,
        providerChoices
      )
    ).state

    assertEquals(form(s).providers, LoadState.NotAsked)

  test("a dictionary response with no wizard open is ignored"):
    val s =
      update(dashboardState, Msg.CitiesLoaded(account1.id, cityChoices)).state
    assertEquals(s.monitorForm, None)

  test("a failed dictionary load is shown and can be retried"):
    val onCity = onCityStep
    val failed = update(
      onCity,
      Msg.CitiesLoadFailed(account1.id, ApiError.Unexpected("luxmed down"))
    )
    assertEquals(
      form(failed.state).cities,
      LoadState.Failed("Something went wrong: luxmed down")
    )

    val retried = update(failed.state, Msg.DictionaryRetryRequested)
    assertEquals(retried.effects.size, 1)
    assertEquals(form(retried.state).cities, LoadState.Loading)
    assertEquals(
      form(retried.state).step,
      WizardStep.City,
      "a retry asks again for what this step needs, and stays put"
    )

  // --- Monitors: schedule validation ---

  test("an empty schedule blocks submission and lists every problem"):
    val t = update(onScheduleStep, Msg.MonitorStepAdvanced)

    assertEquals(t.effects, Nil)
    assertEquals(form(t.state).step, WizardStep.Schedule)
    assert(form(t.state).errors.contains("Choose a date range."))
    assert(form(t.state).errors.contains("Choose a time window."))
    assert(
      form(t.state).errors.contains("Select at least one day of the week.")
    )

  test("a date range that ends before it starts blocks submission"):
    val filled = withSchedule(onScheduleStep)
    val reversed =
      update(filled, Msg.MonitorDateToChanged("2026-07-01")).state

    val t = update(reversed, Msg.MonitorStepAdvanced)

    assertEquals(t.effects, Nil)
    assert(
      form(t.state).errors
        .contains("The first date must not be after the last date.")
    )

  test("a time window that ends before it starts blocks submission"):
    val filled = withSchedule(onScheduleStep)
    val reversed = update(filled, Msg.MonitorTimeToChanged("07:00")).state

    val t = update(reversed, Msg.MonitorStepAdvanced)

    assertEquals(t.effects, Nil)
    assert(
      form(t.state).errors
        .contains("The earliest time must be before the latest time.")
    )

  test("unparsable date and time input is treated as no answer at all"):
    val filled = withSchedule(onScheduleStep)
    val cleared = update(filled, Msg.MonitorDateFromChanged("")).state

    assertEquals(form(cleared).dateFrom, None)
    assert(form(update(cleared, Msg.MonitorStepAdvanced).state).errors.nonEmpty)

  test("the interval starts at ten minutes and rejects four"):
    val filled = withSchedule(onScheduleStep)
    assertEquals(form(filled).intervalMinutes, 10)

    val tooOften = update(filled, Msg.MonitorIntervalChanged("4")).state
    val t = update(tooOften, Msg.MonitorStepAdvanced)

    assertEquals(form(t.state).intervalMinutes, 4)
    assertEquals(t.effects, Nil)
    assert(form(t.state).errors.exists(_.contains("5 minutes")))

    val fixed = update(tooOften, Msg.MonitorIntervalChanged("5")).state
    assertEquals(
      form(update(fixed, Msg.MonitorStepAdvanced).state).step,
      WizardStep.Review
    )

  test("a complete schedule reaches the review step"):
    val s = onReviewStep
    assertEquals(form(s).step, WizardStep.Review)
    assertEquals(form(s).errors, Nil)
    assertEquals(
      form(s).daysOfWeek,
      List(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
    )
    assertEquals(form(s).dateFrom, Some(august))
    assertEquals(form(s).timeTo, Some(evening))

  // --- Monitors: create ---

  test("submitting a complete form fires one request and marks it busy"):
    val t = update(onReviewStep, Msg.MonitorSubmitted)
    assertEquals(t.effects.size, 1)
    assertEquals(form(t.state).submitting, true)
    assertEquals(form(t.state).errors, Nil)

  test("a double submit does not fire a second request"):
    val busy = update(onReviewStep, Msg.MonitorSubmitted).state
    assertEquals(update(busy, Msg.MonitorSubmitted).effects, Nil)

  test("advancing past the review step is what saving means"):
    val t = update(onReviewStep, Msg.MonitorStepAdvanced)
    assertEquals(t.effects.size, 1)
    assertEquals(form(t.state).submitting, true)

  test("submitting an invalid form is refused without a request"):
    val t = update(onScheduleStep, Msg.MonitorSubmitted)
    assertEquals(t.effects, Nil)
    assertEquals(form(t.state).submitting, false)
    assert(form(t.state).errors.nonEmpty)

  test("a created monitor appears in the list and closes the wizard"):
    val busy = update(onReviewStep, Msg.MonitorSubmitted).state
    val created = monitor1.copy(id = MonitorId(9L), name = "Knee")

    val s = update(busy, Msg.MonitorSaved(created)).state

    assertEquals(monitors(s).map(_.id), List(monitor1.id, created.id))
    assertEquals(s.monitorForm, None)

  test("a rejected save keeps the form open with the server's own reason"):
    val busy = update(onReviewStep, Msg.MonitorSubmitted).state

    val s = update(
      busy,
      Msg.MonitorSaveFailed(
        ApiError.Validation("Interval must be at least 5 minutes.")
      )
    ).state

    assertEquals(form(s).submitting, false)
    assertEquals(form(s).step, WizardStep.Review)
    assertEquals(
      form(s).errors,
      List("Interval must be at least 5 minutes."),
      "the server is authoritative, so its wording is what is shown"
    )

  // --- Monitors: edit ---

  test("editing starts from the monitor's persisted ids and names"):
    val t = update(dashboardState, Msg.MonitorEditStarted(monitor1))

    assertEquals(t.effects, Nil, "nothing needs re-fetching to fill the form")
    assertEquals(form(t.state).editingMonitorId, Some(monitor1.id))
    assertEquals(form(t.state).accountId, Some(monitor1.accountId))
    assertEquals(form(t.state).name, monitor1.name)
    assertEquals(form(t.state).city, Some(warsaw))
    assertEquals(form(t.state).service, Some(orthopaedist))
    assertEquals(form(t.state).dateFrom, Some(monitor1.dateFrom))
    assertEquals(form(t.state).timeFrom, Some(monitor1.timeFrom))
    assertEquals(form(t.state).daysOfWeek, monitor1.daysOfWeek)
    assertEquals(form(t.state).intervalMinutes, monitor1.intervalMinutes)

  test("an edit replaces the monitor in place and preserves its state"):
    val editing = update(dashboardState, Msg.MonitorEditStarted(monitor1)).state
    val relocated = update(editing, Msg.MonitorCitySelected(krakow)).state
    assertEquals(
      form(relocated).service,
      None,
      "a new city invalidates the service the old one was chosen with"
    )
    val respecified =
      update(relocated, Msg.MonitorServiceSelected(dermatologist)).state
    val t = update(respecified, Msg.MonitorSubmitted)
    assertEquals(t.effects.size, 1)

    val saved =
      monitor1.copy(
        city = krakow,
        service = dermatologist,
        state = MonitorState.Active
      )
    val s = update(t.state, Msg.MonitorSaved(saved)).state

    assertEquals(monitors(s).size, 1, "an edit adds no row")
    assertEquals(monitors(s).head.city, krakow)
    assertEquals(monitors(s).head.service, dermatologist)
    assertEquals(monitors(s).head.state, MonitorState.Active)
    assertEquals(s.monitorForm, None)

  test("editing an unsaved monitor still needs a valid schedule"):
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
      update(dashboardState, Msg.MonitorsLoaded(List(monitor2))).state
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
      update(dashboardState, Msg.MonitorsLoaded(List(monitor1, monitor2))).state
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
      Msg.MonitorDeleteFailed(ApiError.Unexpected("db unavailable"))
    ).state

    assertEquals(
      s.monitorDeleteConfirmation.map(_.monitorId),
      Some(monitor1.id)
    )
    assertEquals(s.monitorDeleteConfirmation.map(_.submitting), Some(false))
    assert(s.monitorDeleteConfirmation.flatMap(_.error).isDefined)
    assertEquals(monitors(s).size, 1, "a failed delete removes nothing")
