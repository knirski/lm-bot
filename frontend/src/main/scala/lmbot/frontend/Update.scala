package lmbot.frontend

import java.time.{LocalDate, LocalTime}

import scala.util.Try

import gears.async.Async
import lmbot.frontend.api.ApiClient
import lmbot.frontend.elm.{Effect, Transition}
import lmbot.shared.api.{ApiError, LoginRequest}
import lmbot.shared.domain.{
  AccountId,
  LinkAccountRequest,
  MonitorState,
  MonitorView
}

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
      // Reaching the dashboard is the one place a user needs their accounts and
      // monitors, so folding "start loading" into this transition (rather than
      // requiring a separate manual navigation) is what makes them appear
      // without extra clicks. Delegating keeps each effect defined in exactly
      // one place.
      loadDashboard(
        state.copy(
          screen = Screen.Dashboard,
          user = Some(user),
          login = LoginForm(),
          booting = false
        )
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
      loadDashboard(
        state
          .copy(screen = Screen.Dashboard, user = Some(user), booting = false)
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
      // Deleting an account cascades to its monitors server-side (spec §5.3),
      // which the confirmation warned about — so the list must not go on
      // showing rows that no longer exist.
      Transition(
        state.copy(
          accounts = updated,
          deleteConfirmation = None,
          monitors = mapMonitors(state)(_.filterNot(_.accountId == accountId))
        ),
        Nil
      )

    case Msg.AccountDeleteFailed(err) =>
      val updated = state.deleteConfirmation.map(
        _.copy(submitting = false, error = Some(explain(err)))
      )
      Transition(state.copy(deleteConfirmation = updated), Nil)

    case Msg.MonitorsRequested =>
      val effect = new Effect[Msg]:
        def run(using Async): Option[Msg] = Some:
          api.listMonitors() match
            case Right(monitors) => Msg.MonitorsLoaded(monitors)
            case Left(err)       => Msg.MonitorsLoadFailed(err)
      Transition(state.copy(monitors = LoadState.Loading), List(effect))

    case Msg.MonitorsLoaded(monitors) =>
      Transition(state.copy(monitors = LoadState.Loaded(monitors)), Nil)

    case Msg.MonitorsLoadFailed(err) =>
      Transition(state.copy(monitors = LoadState.Failed(explain(err))), Nil)

    case Msg.MonitorCreateStarted =>
      // A monitor watches one linked account's calendar, so with no account
      // there is nothing to open a form about; the view offers linking one
      // instead of a form with nothing to choose.
      state.accounts match
        case LoadState.Loaded(accounts) if accounts.nonEmpty =>
          openForm(state, MonitorForm())
        case _ => Transition(state, Nil)

    case Msg.MonitorEditStarted(monitor) =>
      // The form is already fully populated from the stored monitor, names
      // included; these requests fetch the *alternatives*, so that every
      // criterion can be changed rather than only read.
      loadDictionaries(openForm(state, MonitorForm.edit(monitor)).state)

    case Msg.MonitorFormCancelled =>
      Transition(state.copy(monitorForm = None), Nil)

    case Msg.MonitorAccountSelected(accountId) =>
      // Choosing whose calendar to watch is what makes the account-scoped
      // dictionaries answerable, so they are asked for here.
      changeSelection(state)(_.withAccount(accountId))

    case Msg.MonitorNameChanged(value) =>
      editForm(state)(_.copy(name = value))

    case Msg.MonitorCitySelected(city) =>
      changeSelection(state)(_.withCity(city))

    case Msg.MonitorServiceSelected(service) =>
      changeSelection(state)(_.withService(service))

    case Msg.MonitorFacilityToggled(facility) =>
      editForm(state)(_.toggleFacility(facility))

    case Msg.MonitorDoctorToggled(doctor) =>
      editForm(state)(_.toggleDoctor(doctor))

    case Msg.MonitorDateFromChanged(value) =>
      editForm(state)(_.copy(dateFrom = parseDate(value)))

    case Msg.MonitorDateToChanged(value) =>
      editForm(state)(_.copy(dateTo = parseDate(value)))

    case Msg.MonitorTimeFromChanged(value) =>
      editForm(state)(_.copy(timeFrom = parseTime(value)))

    case Msg.MonitorTimeToChanged(value) =>
      editForm(state)(_.copy(timeTo = parseTime(value)))

    case Msg.MonitorIntervalChanged(value) =>
      editForm(state)(_.copy(intervalMinutes = parseInterval(value)))

    case Msg.MonitorDayToggled(day) =>
      editForm(state)(_.toggleDay(day))

    case Msg.MonitorAutoBookChanged(value) =>
      editForm(state)(_.copy(autoBook = value))

    case Msg.MonitorSubmitted =>
      withForm(state): form =>
        if form.submitting then Transition(state, Nil)
        else
          form.toDraft match
            case None =>
              Transition(
                state.copy(monitorForm =
                  Some(form.copy(errors = form.validationErrors))
                ),
                Nil
              )
            case Some(draft) =>
              // The one place create and edit differ: which call carries the
              // very same draft.
              val effect = new Effect[Msg]:
                def run(using Async): Option[Msg] = Some:
                  val saved = form.editingMonitorId match
                    case Some(id) => api.updateMonitor(id, draft)
                    case None     => api.createMonitor(draft)
                  saved match
                    case Right(monitor) => Msg.MonitorSaved(monitor)
                    case Left(err)      => Msg.MonitorSaveFailed(err)
              Transition(
                state.copy(monitorForm =
                  Some(form.copy(submitting = true, errors = Nil))
                ),
                List(effect)
              )

    case Msg.MonitorSaved(monitor) =>
      // An edit returns the same id, so replacing in place keeps the row where
      // the user left it — and keeps the server's own view of its state, which
      // editing never changes.
      val updated = mapMonitors(state): existing =>
        if existing.exists(_.id == monitor.id) then
          existing.map(m => if m.id == monitor.id then monitor else m)
        else existing :+ monitor
      val saved = state.copy(monitors = updated, monitorForm = None)
      state.monitors match
        case LoadState.Loaded(_) => Transition(saved, Nil)
        // The list never loaded (or failed to), so this one monitor is not the
        // whole of it. Asking again is honest; pretending the list is now
        // exactly this row would hide the earlier failure.
        case _ => apply(saved, Msg.MonitorsRequested)

    case Msg.MonitorSaveFailed(err) =>
      editForm(state)(_.copy(submitting = false, errors = List(explain(err))))

    case Msg.CitiesRequested(accountId) =>
      withForm(state): form =>
        val effect = new Effect[Msg]:
          def run(using Async): Option[Msg] = Some:
            api.cities(accountId) match
              case Right(cities) => Msg.CitiesLoaded(accountId, cities)
              case Left(err)     => Msg.CitiesLoadFailed(accountId, err)
        Transition(
          state.copy(monitorForm = Some(form.copy(cities = LoadState.Loading))),
          List(effect)
        )

    case Msg.CitiesLoaded(accountId, cities) =>
      ifStillCurrent(state)(_.accountId.contains(accountId)):
        _.copy(cities = LoadState.Loaded(cities))

    case Msg.CitiesLoadFailed(accountId, err) =>
      ifStillCurrent(state)(_.accountId.contains(accountId)):
        _.copy(cities = LoadState.Failed(explain(err)))

    case Msg.ServicesRequested(accountId) =>
      withForm(state): form =>
        val effect = new Effect[Msg]:
          def run(using Async): Option[Msg] = Some:
            api.services(accountId) match
              case Right(services) => Msg.ServicesLoaded(accountId, services)
              case Left(err)       => Msg.ServicesLoadFailed(accountId, err)
        Transition(
          state
            .copy(monitorForm = Some(form.copy(services = LoadState.Loading))),
          List(effect)
        )

    case Msg.ServicesLoaded(accountId, services) =>
      ifStillCurrent(state)(_.accountId.contains(accountId)):
        _.copy(services = LoadState.Loaded(services))

    case Msg.ServicesLoadFailed(accountId, err) =>
      ifStillCurrent(state)(_.accountId.contains(accountId)):
        _.copy(services = LoadState.Failed(explain(err)))

    case Msg.DictionaryRetryRequested =>
      // A failed load counts as unanswered, so asking for everything the form
      // can currently ask about re-issues exactly the failed requests.
      loadDictionaries(state)

    case Msg.ProvidersRequested(accountId, cityId, serviceId) =>
      withForm(state): form =>
        val effect = new Effect[Msg]:
          def run(using Async): Option[Msg] = Some:
            api.facilitiesAndDoctors(accountId, cityId, serviceId) match
              case Right(response) =>
                Msg.ProvidersLoaded(accountId, cityId, serviceId, response)
              case Left(err) =>
                Msg.ProvidersLoadFailed(accountId, cityId, serviceId, err)
        Transition(
          state
            .copy(monitorForm = Some(form.copy(providers = LoadState.Loading))),
          List(effect)
        )

    case Msg.ProvidersLoaded(accountId, cityId, serviceId, response) =>
      ifStillCurrent(state)(keyedTo(_, accountId, cityId, serviceId)):
        _.copy(providers = LoadState.Loaded(response))

    case Msg.ProvidersLoadFailed(accountId, cityId, serviceId, err) =>
      ifStillCurrent(state)(keyedTo(_, accountId, cityId, serviceId)):
        _.copy(providers = LoadState.Failed(explain(err)))

    case Msg.MonitorPauseRequested(monitorId) =>
      if actionInFlight(state) then Transition(state, Nil)
      else
        val effect = new Effect[Msg]:
          def run(using Async): Option[Msg] = Some:
            api.pauseMonitor(monitorId) match
              case Right(()) =>
                Msg.MonitorStateChanged(monitorId, MonitorState.Paused)
              case Left(err) => Msg.MonitorStateChangeFailed(monitorId, err)
        Transition(
          state.copy(monitorAction =
            Some(MonitorAction(monitorId, MonitorState.Paused))
          ),
          List(effect)
        )

    case Msg.MonitorResumeRequested(monitorId) =>
      if actionInFlight(state) then Transition(state, Nil)
      else
        val effect = new Effect[Msg]:
          def run(using Async): Option[Msg] = Some:
            api.resumeMonitor(monitorId) match
              case Right(()) =>
                Msg.MonitorStateChanged(monitorId, MonitorState.Active)
              case Left(err) => Msg.MonitorStateChangeFailed(monitorId, err)
        Transition(
          state.copy(monitorAction =
            Some(MonitorAction(monitorId, MonitorState.Active))
          ),
          List(effect)
        )

    case Msg.MonitorStateChanged(monitorId, monitorState) =>
      // Only the state changes here. Pause and resume answer with no body, and
      // inventing a fresh `updatedAt` or a last-check time would be fiction —
      // monitors do not run until Plan 5.
      val updated = mapMonitors(state):
        _.map(m =>
          if m.id == monitorId then m.copy(state = monitorState) else m
        )
      Transition(state.copy(monitors = updated, monitorAction = None), Nil)

    case Msg.MonitorStateChangeFailed(monitorId, err) =>
      val updated = state.monitorAction match
        case Some(action) if action.monitorId == monitorId =>
          Some(action.copy(submitting = false, error = Some(explain(err))))
        case other => other
      Transition(state.copy(monitorAction = updated), Nil)

    case Msg.MonitorDeleteRequested(monitorId) =>
      Transition(
        state.copy(monitorDeleteConfirmation =
          Some(MonitorDeleteConfirmation(monitorId))
        ),
        Nil
      )

    case Msg.MonitorDeleteCancelled =>
      Transition(state.copy(monitorDeleteConfirmation = None), Nil)

    case Msg.MonitorDeleteConfirmed =>
      state.monitorDeleteConfirmation match
        case None => Transition(state, Nil)
        case Some(confirmation) if confirmation.submitting =>
          Transition(state, Nil)
        case Some(confirmation) =>
          val effect = new Effect[Msg]:
            def run(using Async): Option[Msg] = Some:
              api.deleteMonitor(confirmation.monitorId) match
                case Right(()) => Msg.MonitorDeleted(confirmation.monitorId)
                case Left(err) => Msg.MonitorDeleteFailed(err)
          Transition(
            state.copy(monitorDeleteConfirmation =
              Some(confirmation.copy(submitting = true, error = None))
            ),
            List(effect)
          )

    case Msg.MonitorDeleted(monitorId) =>
      Transition(
        state.copy(
          monitors = mapMonitors(state)(_.filterNot(_.id == monitorId)),
          monitorDeleteConfirmation = None
        ),
        Nil
      )

    case Msg.MonitorDeleteFailed(err) =>
      val updated = state.monitorDeleteConfirmation.map(
        _.copy(submitting = false, error = Some(explain(err)))
      )
      Transition(state.copy(monitorDeleteConfirmation = updated), Nil)

  /** Both ways onto the dashboard need both lists. `apply` delegates to one
    * message at a time, so the two sub-transitions are combined rather than one
    * of them silently winning.
    */
  private def loadDashboard(arrived: AppState): Transition[AppState, Msg] =
    val withAccounts = apply(arrived, Msg.AccountsRequested)
    val withMonitors = apply(withAccounts.state, Msg.MonitorsRequested)
    Transition(
      withMonitors.state,
      withAccounts.effects ++ withMonitors.effects
    )

  private def openForm(
      state: AppState,
      form: MonitorForm
  ): Transition[AppState, Msg] =
    // The form replaces the list, so a confirmation or a failed pause left
    // open there would reappear behind it on cancel.
    Transition(
      state.copy(
        monitorForm = Some(form),
        monitorAction = None,
        monitorDeleteConfirmation = None
      ),
      Nil
    )

  /** A form message is meaningful only while a form is open. If it was
    * cancelled meanwhile — or the message is a late answer to a question nobody
    * is asking any more — it is dropped rather than reopening the form.
    */
  private def withForm(
      state: AppState
  )(f: MonitorForm => Transition[AppState, Msg]): Transition[AppState, Msg] =
    state.monitorForm.fold(Transition(state, Nil))(f)

  private def editForm(
      state: AppState
  )(f: MonitorForm => MonitorForm): Transition[AppState, Msg] =
    withForm(state)(form =>
      Transition(state.copy(monitorForm = Some(f(form))), Nil)
    )

  /** A dictionary answer is applied only while the selection it was keyed to is
    * still the current one. Without this, a slow response for the account,
    * city, or service the user has since moved off would repopulate the choices
    * for the one they are looking at now.
    */
  private def ifStillCurrent(
      state: AppState
  )(matches: MonitorForm => Boolean)(
      f: MonitorForm => MonitorForm
  ): Transition[AppState, Msg] =
    state.monitorForm match
      case Some(form) if matches(form) =>
        Transition(state.copy(monitorForm = Some(f(form))), Nil)
      case _ => Transition(state, Nil)

  private def keyedTo(
      form: MonitorForm,
      accountId: AccountId,
      cityId: Long,
      serviceId: Long
  ): Boolean =
    form.accountId.contains(accountId) &&
      form.city.exists(_.id == cityId) &&
      form.service.exists(_.id == serviceId)

  /** A selection that other choices depend on: apply it, then ask for whatever
    * it just made answerable.
    */
  private def changeSelection(
      state: AppState
  )(f: MonitorForm => MonitorForm): Transition[AppState, Msg] =
    loadDictionaries(editForm(state)(f).state)

  /** Asks for every dictionary the form has the ids for and does not already
    * have — and for no others, so nothing is ever requested before the ids it
    * needs exist. Cities and services need only the account; facilities and
    * doctors need the city and the service too. A load already in flight is not
    * repeated; a previous failure counts as missing and is retried.
    */
  private def loadDictionaries(state: AppState): Transition[AppState, Msg] =
    withForm(state): form =>
      val cities = form.accountId
        .filter(_ => unloaded(form.cities))
        .map(Msg.CitiesRequested(_))
      val services = form.accountId
        .filter(_ => unloaded(form.services))
        .map(Msg.ServicesRequested(_))
      val providers = (form.accountId, form.city, form.service) match
        case (Some(accountId), Some(city), Some(service))
            if unloaded(form.providers) =>
          Some(Msg.ProvidersRequested(accountId, city.id, service.id))
        case _ => None
      List(cities, services, providers).flatten
        .foldLeft(Transition(state, List.empty[Effect[Msg]])): (acc, msg) =>
          val next = apply(acc.state, msg)
          Transition(next.state, acc.effects ++ next.effects)

  private def unloaded(load: LoadState[?]): Boolean = load match
    case LoadState.NotAsked | LoadState.Failed(_) => true
    case _                                        => false

  private def actionInFlight(state: AppState): Boolean =
    state.monitorAction.exists(_.submitting)

  private def mapMonitors(
      state: AppState
  )(f: List[MonitorView] => List[MonitorView]): LoadState[List[MonitorView]] =
    state.monitors match
      case LoadState.Loaded(existing) => LoadState.Loaded(f(existing))
      case other                      => other

  /** `input type="date"`, `type="time"` and `type="number"` hand back text —
    * ISO text when the field holds a value, and `""` once it is cleared.
    * `java.time` signals a bad value by throwing, so this boundary turns
    * "unusable" into "unanswered" and lets validation speak about it.
    */
  private def parseDate(value: String): Option[LocalDate] =
    Try(LocalDate.parse(value)).toOption

  private def parseTime(value: String): Option[LocalTime] =
    Try(LocalTime.parse(value)).toOption

  /** Non-numeric text becomes zero rather than keeping the previous number: the
    * field shows what the user typed, and validation then says it is too often.
    */
  private def parseInterval(value: String): Int =
    value.trim.toIntOption.getOrElse(0)

  private def explain(err: ApiError): String = err match
    case ApiError.Unauthorized => "Wrong username or password."
    case ApiError.Forbidden    =>
      "That account is disabled. Ask the administrator."
    case ApiError.Unexpected(d) => s"Something went wrong: $d"
    case other                  => other.message
