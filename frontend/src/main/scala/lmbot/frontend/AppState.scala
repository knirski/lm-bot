package lmbot.frontend

import java.time.{DayOfWeek, LocalDate, LocalTime}

import lmbot.shared.domain.{
  AccountId,
  AccountView,
  DictionaryCity,
  DictionaryService,
  FacilitiesDoctorsResponse,
  MonitorDraft,
  MonitorId,
  MonitorState,
  MonitorView,
  NamedId,
  UserView
}

enum Screen:
  case Login, Dashboard

/** Not-yet-requested / in-flight / resolved data. A closed enum rather than a
  * pair of booleans, so "loading" and "loaded but empty" can never be confused
  * with each other.
  */
enum LoadState[+A]:
  case NotAsked, Loading
  case Loaded(value: A)
  case Failed(message: String)

case class LoginForm(
    username: String = "",
    password: String = "",
    submitting: Boolean = false,
    error: Option[String] = None
)

/** Mirrors `LoginForm`'s shape. The Luxmed password never survives past the
  * request that consumes it: both the success and failure paths in `Update`
  * clear `password`, and success resets the whole form.
  */
case class LinkAccountForm(
    label: String = "",
    username: String = "",
    password: String = "",
    submitting: Boolean = false,
    error: Option[String] = None
)

/** Which account a "Delete" click is asking to confirm, and the in-flight /
  * error state of that confirmation, kept separate from the account list so the
  * dialog can be busy or fail without touching it.
  */
case class DeleteConfirmation(
    accountId: AccountId,
    submitting: Boolean = false,
    error: Option[String] = None
)

/** The guided monitor flow, in the order the Luxmed dictionaries can answer:
  * nothing can be asked about a city before an account is chosen, and nothing
  * about facilities or doctors before both a city and a service are.
  */
enum WizardStep:
  case Account, City, Service, Providers, Schedule, Review

object WizardStep:
  extension (step: WizardStep)
    def next: Option[WizardStep] =
      Option.when(step.ordinal < values.length - 1)(
        fromOrdinal(step.ordinal + 1)
      )

    def previous: Option[WizardStep] =
      Option.when(step.ordinal > 0)(fromOrdinal(step.ordinal - 1))

    /** 1-based, for "Step 3 of 6" — `ordinal` is not a user-facing number. */
    def number: Int = step.ordinal + 1

/** The single form model behind both creating and editing a monitor.
  *
  * `editingMonitorId` is the only thing that differs between the two: `None`
  * means "create", `Some(id)` means "replace that monitor's criteria". Every
  * other decision — which dictionaries to fetch, what counts as valid, what the
  * review step shows — is identical, which is why there is one type and not
  * two.
  *
  * Editing starts fully populated from the persisted denormalized `NamedId`s,
  * so a saved monitor stays readable and editable even if a later dictionary
  * response no longer offers the same choices.
  *
  * The three `LoadState` fields hold *choices offered*, never choices made; the
  * selections above them are what gets saved.
  */
case class MonitorForm(
    editingMonitorId: Option[MonitorId] = None,
    step: WizardStep = WizardStep.Account,
    accountId: Option[AccountId] = None,
    name: String = "",
    city: Option[NamedId] = None,
    service: Option[NamedId] = None,
    facilities: List[NamedId] = Nil,
    doctors: List[NamedId] = Nil,
    dateFrom: Option[LocalDate] = None,
    dateTo: Option[LocalDate] = None,
    timeFrom: Option[LocalTime] = None,
    timeTo: Option[LocalTime] = None,
    daysOfWeek: List[DayOfWeek] = Nil,
    autoBook: Boolean = false,
    intervalMinutes: Int = MonitorForm.defaultIntervalMinutes,
    cities: LoadState[List[DictionaryCity]] = LoadState.NotAsked,
    services: LoadState[List[DictionaryService]] = LoadState.NotAsked,
    providers: LoadState[FacilitiesDoctorsResponse] = LoadState.NotAsked,
    submitting: Boolean = false,
    errors: List[String] = Nil
):

  /** Picking a different account invalidates everything downstream: the offered
    * cities and services belong to that account's Luxmed dictionaries, and the
    * selections were made from the previous account's.
    */
  def withAccount(id: AccountId): MonitorForm =
    if accountId.contains(id) then this
    else
      copy(
        accountId = Some(id),
        cities = LoadState.NotAsked,
        services = LoadState.NotAsked
      ).clearCity

  /** Services are account-scoped, not city-scoped, so the offered *list*
    * survives a city change — but the chosen service does not, because
    * facilities and doctors are looked up per (city, service) pair and a
    * service kept across a city change would silently keep providers from the
    * old city.
    */
  def withCity(chosen: NamedId): MonitorForm =
    if city.contains(chosen) then this
    else copy(city = Some(chosen)).clearService

  def withService(chosen: NamedId): MonitorForm =
    if service.contains(chosen) then this
    else copy(service = Some(chosen)).clearProviders

  private def clearCity: MonitorForm = copy(city = None).clearService

  private def clearService: MonitorForm = copy(service = None).clearProviders

  private def clearProviders: MonitorForm =
    copy(facilities = Nil, doctors = Nil, providers = LoadState.NotAsked)

  def toggleFacility(facility: NamedId): MonitorForm =
    copy(facilities = MonitorForm.toggled(facilities, facility))

  def toggleDoctor(doctor: NamedId): MonitorForm =
    copy(doctors = MonitorForm.toggled(doctors, doctor))

  /** Kept in calendar order regardless of the order they were ticked in, so the
    * review step and the saved monitor read Monday-first.
    */
  def toggleDay(day: DayOfWeek): MonitorForm =
    val next =
      if daysOfWeek.contains(day) then daysOfWeek.filterNot(_ == day)
      else day :: daysOfWeek
    copy(daysOfWeek = next.sortBy(_.getValue))

  /** What this step still needs before "Next" can move on. The wizard exists to
    * ask one question at a time, so each step withholds only its own
    * prerequisite rather than the whole form's.
    */
  def stepErrors: List[String] = step match
    case WizardStep.Account   => accountErrors ++ nameErrors
    case WizardStep.City      => cityErrors
    case WizardStep.Service   => serviceErrors
    case WizardStep.Providers => Nil
    case WizardStep.Schedule  => scheduleErrors
    case WizardStep.Review    => Nil

  /** Mirrors the server's own checks (`MonitorService.validate`) so a mistake
    * is caught before a round trip — the server stays authoritative, and its
    * `ApiError.Validation` text is displayed when it disagrees.
    */
  def validationErrors: List[String] =
    accountErrors ++ nameErrors ++ cityErrors ++ serviceErrors ++ scheduleErrors

  /** `Some` exactly when `validationErrors` is empty. */
  def toDraft: Option[MonitorDraft] =
    if validationErrors.nonEmpty then None
    else
      for
        account <- accountId
        chosenCity <- city
        chosenService <- service
        from <- dateFrom
        to <- dateTo
        opensAt <- timeFrom
        closesAt <- timeTo
      yield MonitorDraft(
        accountId = account,
        name = name.trim,
        city = chosenCity,
        service = chosenService,
        facilities = facilities,
        doctors = doctors,
        dateFrom = from,
        dateTo = to,
        timeFrom = opensAt,
        timeTo = closesAt,
        daysOfWeek = daysOfWeek,
        autoBook = autoBook,
        intervalMinutes = intervalMinutes
      )

  private def accountErrors: List[String] =
    if accountId.isEmpty then List("Choose a Luxmed account.") else Nil

  private def nameErrors: List[String] =
    if name.trim.isEmpty then List("Give the monitor a name.") else Nil

  private def cityErrors: List[String] =
    if city.isEmpty then List("Choose a city.") else Nil

  private def serviceErrors: List[String] =
    if service.isEmpty then List("Choose a service.") else Nil

  private def scheduleErrors: List[String] =
    val dates = (dateFrom, dateTo) match
      case (Some(from), Some(to)) if from.isAfter(to) =>
        List("The first date must not be after the last date.")
      case (Some(_), Some(_)) => Nil
      case _                  => List("Choose a date range.")
    val times = (timeFrom, timeTo) match
      case (Some(opensAt), Some(closesAt)) if !opensAt.isBefore(closesAt) =>
        List("The earliest time must be before the latest time.")
      case (Some(_), Some(_)) => Nil
      case _                  => List("Choose a time window.")
    val days =
      if daysOfWeek.isEmpty then List("Select at least one day of the week.")
      else Nil
    val interval =
      if intervalMinutes < MonitorForm.minimumIntervalMinutes then
        List(
          s"Check at most every ${MonitorForm.minimumIntervalMinutes} minutes."
        )
      else Nil
    dates ++ times ++ days ++ interval

object MonitorForm:
  /** Spec §5.3: omitted intervals become ten minutes, with a hard five-minute
    * floor.
    */
  val defaultIntervalMinutes: Int = 10
  val minimumIntervalMinutes: Int = 5

  def edit(monitor: MonitorView): MonitorForm =
    MonitorForm(
      editingMonitorId = Some(monitor.id),
      accountId = Some(monitor.accountId),
      name = monitor.name,
      city = Some(monitor.city),
      service = Some(monitor.service),
      facilities = monitor.facilities,
      doctors = monitor.doctors,
      dateFrom = Some(monitor.dateFrom),
      dateTo = Some(monitor.dateTo),
      timeFrom = Some(monitor.timeFrom),
      timeTo = Some(monitor.timeTo),
      daysOfWeek = monitor.daysOfWeek,
      autoBook = monitor.autoBook,
      intervalMinutes = monitor.intervalMinutes
    )

  /** Compared by id, not by whole value: the same facility arriving from a
    * later dictionary response with a reworded name is still the same facility.
    */
  private def toggled(selected: List[NamedId], item: NamedId): List[NamedId] =
    if selected.exists(_.id == item.id) then selected.filterNot(_.id == item.id)
    else selected :+ item

/** Mirrors `DeleteConfirmation`: a monitor delete is confirmed the same way an
  * account delete is, and nothing about a monitor justifies a different shape.
  */
case class MonitorDeleteConfirmation(
    monitorId: MonitorId,
    submitting: Boolean = false,
    error: Option[String] = None
)

/** A pause or resume in flight, and where its failure belongs. `target` is the
  * state the request is asking for, which is what the row shows while it waits.
  */
case class MonitorAction(
    monitorId: MonitorId,
    target: MonitorState,
    submitting: Boolean = true,
    error: Option[String] = None
)

/** `booting` is true until the app has asked the server whether the browser
  * already holds a valid session, so the login form is not flashed at a user
  * who is in fact already signed in.
  */
case class AppState(
    screen: Screen,
    login: LoginForm,
    user: Option[UserView],
    booting: Boolean,
    accounts: LoadState[List[AccountView]] = LoadState.NotAsked,
    linkForm: LinkAccountForm = LinkAccountForm(),
    deleteConfirmation: Option[DeleteConfirmation] = None,
    monitors: LoadState[List[MonitorView]] = LoadState.NotAsked,
    monitorForm: Option[MonitorForm] = None,
    monitorAction: Option[MonitorAction] = None,
    monitorDeleteConfirmation: Option[MonitorDeleteConfirmation] = None
)

object AppState:
  val initial: AppState =
    AppState(Screen.Login, LoginForm(), None, booting = true)
