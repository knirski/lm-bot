package lmbot.frontend.view

import java.time.DayOfWeek

import com.raquo.laminar.api.L.*
import lmbot.frontend.elm.Runtime
import lmbot.frontend.{
  AppState,
  LoadState,
  MonitorAction,
  MonitorDeleteConfirmation,
  MonitorForm,
  Msg
}
import lmbot.shared.domain.{
  FacilitiesDoctorsResponse,
  MonitorId,
  MonitorState,
  MonitorView,
  NamedId
}

/** Rendering only. Handlers do exactly one thing: send a message (spec §5.6).
  * Every decision — which dictionary to fetch, what is still missing, whether a
  * choice invalidates another — lives in `Update`.
  *
  * Named `MonitorsView` rather than `MonitorView` — the file the plan names —
  * because this object necessarily imports `lmbot.shared.domain.MonitorView` to
  * render one, and a same-named object would collide with that import in this
  * very file. `AccountsView` is named for the same reason.
  */
object MonitorsView:

  def apply(rt: Runtime[AppState, Msg]): HtmlElement =
    div(
      cls := "monitors",
      h2("Monitors"),
      // Keyed on "is a form open", not on the form itself, so that typing into
      // the form re-binds fields instead of rebuilding the element under the
      // caret.
      child <-- rt.store.signal.map(_.monitorForm.isDefined).distinct.map {
        case true  => monitorForm(rt)
        case false => monitorList(rt)
      }
    )

  // --- The list ---

  private def monitorList(rt: Runtime[AppState, Msg]): HtmlElement =
    div(
      cls := "monitor-list",
      // No dead controls: "New monitor" appears only once there is an account
      // for it to watch, because that is also the only case `Update` opens the
      // form in.
      child <-- rt.store.signal.map(_.accounts).distinct.map {
        case LoadState.NotAsked | LoadState.Loading =>
          p(cls := "loading", "Loading accounts…")
        case LoadState.Failed(_) =>
          p(
            cls := "placeholder",
            "Monitors can be created once your accounts load."
          )
        case LoadState.Loaded(Nil) => linkAccountFirst
        case LoadState.Loaded(_)   => newMonitorButton(rt)
      },
      child <-- rt.store.signal.map(_.monitors).distinct.map {
        case LoadState.NotAsked | LoadState.Loading =>
          p(cls := "loading", "Loading monitors…")
        case LoadState.Failed(message) =>
          p(cls := "error", role := "alert", message)
        case LoadState.Loaded(Nil) =>
          p(cls := "placeholder", "No monitors yet.")
        case LoadState.Loaded(monitors) =>
          ul(cls := "monitor-rows", monitors.map(monitorItem(rt, _)))
      }
    )

  private def newMonitorButton(rt: Runtime[AppState, Msg]): HtmlElement =
    button(
      cls := "new-monitor",
      "New monitor",
      onClick.mapTo(Msg.MonitorCreateStarted) --> (m => rt.dispatch(m))
    )

  /** A monitor watches one linked Luxmed account, so with none linked the form
    * would open with nothing to choose. The link form above is the next thing
    * to do, and this points straight at it.
    */
  private def linkAccountFirst: HtmlElement =
    div(
      cls := "needs-account",
      p("Link a Luxmed account before creating a monitor."),
      a(href := "#link-account", cls := "cta", "Link a Luxmed account")
    )

  private def monitorItem(
      rt: Runtime[AppState, Msg],
      monitor: MonitorView
  ): HtmlElement =
    val confirmSignal = rt.store.signal
      .map(_.monitorDeleteConfirmation.filter(_.monitorId == monitor.id))
      .distinct
    val actionSignal = rt.store.signal
      .map(_.monitorAction.filter(_.monitorId == monitor.id))
      .distinct
    li(
      cls := "monitor",
      h3(monitor.name),
      span(cls := "state", stateText(monitor.state)),
      p(cls := "criteria", s"${monitor.city.name} — ${monitor.service.name}"),
      p(cls := "providers", chosenText("clinic", monitor.facilities)),
      p(cls := "providers", chosenText("doctor", monitor.doctors)),
      p(
        cls := "window",
        s"${monitor.dateFrom} to ${monitor.dateTo}, " +
          s"${monitor.timeFrom}–${monitor.timeTo} Warsaw time"
      ),
      p(cls := "days", daysText(monitor.daysOfWeek)),
      p(cls := "interval", s"Checks every ${monitor.intervalMinutes} minutes"),
      p(
        cls := "auto-book",
        if monitor.autoBook then "Books a slot automatically"
        else "Notifies only"
      ),
      div(
        cls := "monitor-actions",
        button(
          "Edit",
          onClick.mapTo(Msg.MonitorEditStarted(monitor)) --> (m =>
            rt.dispatch(m)
          )
        ),
        stateToggle(rt, monitor, actionSignal),
        child <-- confirmSignal.map {
          case None               => deleteButton(rt, monitor.id)
          case Some(confirmation) => confirmDeletion(rt, confirmation)
        }
      ),
      child.maybe <-- actionSignal
        .map(_.flatMap(_.error))
        .distinct
        .map(_.map(message => p(cls := "error", role := "alert", message)))
    )

  /** Only `Active` and `Paused` can be toggled: the server refuses to resume a
    * completed or failed monitor, and offering a control that is known to fail
    * is worse than offering none.
    */
  private def stateToggle(
      rt: Runtime[AppState, Msg],
      monitor: MonitorView,
      actionSignal: Signal[Option[MonitorAction]]
  ): List[HtmlElement] = monitor.state match
    case MonitorState.Completed | MonitorState.Failed => Nil
    case MonitorState.Active | MonitorState.Paused    =>
      val paused = monitor.state == MonitorState.Paused
      val request =
        if paused then Msg.MonitorResumeRequested(monitor.id)
        else Msg.MonitorPauseRequested(monitor.id)
      val busySignal = actionSignal.map(_.exists(_.submitting)).distinct
      List(
        button(
          disabled <-- busySignal,
          aria.busy <-- busySignal,
          child.text <-- busySignal.map {
            case true  => if paused then "Resuming…" else "Pausing…"
            case false => if paused then "Resume" else "Pause"
          },
          onClick.mapTo(request) --> (m => rt.dispatch(m))
        )
      )

  private def deleteButton(
      rt: Runtime[AppState, Msg],
      id: MonitorId
  ): HtmlElement =
    button(
      "Delete",
      onClick.mapTo(Msg.MonitorDeleteRequested(id)) --> (m => rt.dispatch(m))
    )

  private def confirmDeletion(
      rt: Runtime[AppState, Msg],
      confirmation: MonitorDeleteConfirmation
  ): HtmlElement =
    div(
      cls := "confirm-delete",
      p("Delete this monitor?"),
      button(
        "Cancel",
        disabled := confirmation.submitting,
        onClick.mapTo(Msg.MonitorDeleteCancelled) --> (m => rt.dispatch(m))
      ),
      button(
        "Confirm delete",
        disabled := confirmation.submitting,
        onClick.mapTo(Msg.MonitorDeleteConfirmed) --> (m => rt.dispatch(m))
      ),
      confirmation.error
        .map(message => p(cls := "error", role := "alert", message))
        .toList
    )

  // --- The form ---

  /** One page holding every field: the account, the criteria, and the schedule
    * are all answerable at once and in any order. `Update` decides what is
    * still missing and which dictionaries can be asked for; this only shows the
    * answers and reports what it was told.
    */
  private def monitorForm(rt: Runtime[AppState, Msg]): HtmlElement =
    // While this element is mounted a form is open. The fallback exists only
    // because closing the form and swapping this element out are two separate
    // notifications, and is never what the user sees.
    val formSignal =
      rt.store.signal.map(_.monitorForm.getOrElse(MonitorForm())).distinct
    val submittingSignal = formSignal.map(_.submitting).distinct
    form(
      cls := "monitor-form",
      // The only validation the user sees is the summary below, built from the
      // same rules `update` submits on. Native constraint validation would
      // otherwise intercept the submit first and argue with it.
      noValidate := true,
      aria.busy <-- submittingSignal,
      onSubmit.preventDefault.mapTo(Msg.MonitorSubmitted) --> (m =>
        rt.dispatch(m)
      ),
      h3(
        child.text <-- formSignal
          .map(f =>
            if f.editingMonitorId.isDefined then "Edit monitor"
            else "New monitor"
          )
          .distinct
      ),
      errorSummary(formSignal),
      accountFields(rt, formSignal),
      cityField(rt, formSignal),
      serviceField(rt, formSignal),
      providerFields(rt, formSignal),
      scheduleFields(rt, formSignal),
      div(
        cls := "form-actions",
        button(
          tpe := "submit",
          disabled <-- submittingSignal,
          child.text <-- formSignal.map(saveLabel).distinct
        ),
        button(
          tpe := "button",
          "Cancel",
          disabled <-- submittingSignal,
          onClick.mapTo(Msg.MonitorFormCancelled) --> (m => rt.dispatch(m))
        )
      )
    )

  /** A live region that is always present, so a problem appended to it later is
    * announced. Its contents are whatever `Update` found missing, or the reason
    * the server gave for refusing the save.
    */
  private def errorSummary(formSignal: Signal[MonitorForm]): HtmlElement =
    div(
      // `role="alert"` already implies an assertive live region, so no
      // `aria-live` beside it: the two would contradict each other.
      cls := "form-errors",
      role := "alert",
      children <-- formSignal
        .map(_.errors)
        .distinct
        .map(_.map(message => p(cls := "error", message)))
    )

  private def saveLabel(form: MonitorForm): String =
    if form.submitting then "Saving…"
    else if form.editingMonitorId.isDefined then "Save changes"
    else "Create monitor"

  private def accountFields(
      rt: Runtime[AppState, Msg],
      formSignal: Signal[MonitorForm]
  ): HtmlElement =
    div(
      cls := "form-group",
      label(
        cls := "field",
        "Monitor name",
        input(
          tpe := "text",
          value <-- formSignal.map(_.name).distinct,
          onInput.mapToValue --> (v => rt.dispatch(Msg.MonitorNameChanged(v)))
        )
      ),
      fieldSet(
        cls := "field",
        legend("Luxmed account"),
        children <-- rt.store.signal.map(_.accounts).distinct.map {
          case LoadState.NotAsked | LoadState.Loading =>
            List(p(cls := "loading", "Loading accounts…"))
          case LoadState.Failed(message) =>
            List(p(cls := "error", role := "alert", message))
          case LoadState.Loaded(accounts) =>
            accounts.map: account =>
              choice(
                groupName = "monitor-account",
                kind = "radio",
                text = s"${account.label} (${account.username})",
                selected = formSignal
                  .map(_.accountId.contains(account.id))
                  .distinct,
                onSelect =
                  () => rt.dispatch(Msg.MonitorAccountSelected(account.id))
              )
        }
      )
    )

  private def cityField(
      rt: Runtime[AppState, Msg],
      formSignal: Signal[MonitorForm]
  ): HtmlElement =
    choiceGroup(
      rt = rt,
      legendText = "City",
      groupName = "monitor-city",
      kind = "radio",
      waitingText = "Choose a Luxmed account first.",
      loadingText = "Loading cities…",
      choices = formSignal
        .map(f => mapLoad(f.cities)(_.map(c => NamedId(c.id, c.name))))
        .distinct,
      isSelected =
        item => formSignal.map(_.city.exists(_.id == item.id)).distinct,
      select = Msg.MonitorCitySelected(_),
      chosen = Some(formSignal.map(f => nameOrNone(f.city)))
    )

  private def serviceField(
      rt: Runtime[AppState, Msg],
      formSignal: Signal[MonitorForm]
  ): HtmlElement =
    choiceGroup(
      rt = rt,
      legendText = "Service",
      groupName = "monitor-service",
      kind = "radio",
      waitingText = "Choose a Luxmed account first.",
      loadingText = "Loading services…",
      choices = formSignal
        .map(f => mapLoad(f.services)(_.map(s => NamedId(s.id, s.name))))
        .distinct,
      isSelected =
        item => formSignal.map(_.service.exists(_.id == item.id)).distinct,
      select = Msg.MonitorServiceSelected(_),
      chosen = Some(formSignal.map(f => nameOrNone(f.service)))
    )

  /** Optional, and the only pair of fields that needs two other answers first —
    * Luxmed offers facilities and doctors per city *and* service.
    */
  private def providerFields(
      rt: Runtime[AppState, Msg],
      formSignal: Signal[MonitorForm]
  ): HtmlElement =
    div(
      cls := "form-group",
      p(
        cls := "hint",
        "Optional. Leave both empty to accept any clinic and any doctor."
      ),
      choiceGroup(
        rt = rt,
        legendText = "Clinics",
        groupName = "monitor-facility",
        kind = "checkbox",
        waitingText = "Choose a city and a service first.",
        loadingText = "Loading clinics and doctors…",
        choices =
          formSignal.map(f => mapLoad(f.providers)(facilityChoices)).distinct,
        isSelected =
          item => formSignal.map(_.facilities.exists(_.id == item.id)).distinct,
        select = Msg.MonitorFacilityToggled(_),
        // Shown whatever the dictionary is doing, so an edited monitor's saved
        // clinics are visible before (and if) the alternatives arrive.
        chosen = Some(formSignal.map(f => chosenText("clinic", f.facilities)))
      ),
      choiceGroup(
        rt = rt,
        legendText = "Doctors",
        groupName = "monitor-doctor",
        kind = "checkbox",
        waitingText = "Choose a city and a service first.",
        loadingText = "Loading clinics and doctors…",
        choices =
          formSignal.map(f => mapLoad(f.providers)(doctorChoices)).distinct,
        isSelected =
          item => formSignal.map(_.doctors.exists(_.id == item.id)).distinct,
        select = Msg.MonitorDoctorToggled(_),
        chosen = Some(formSignal.map(f => chosenText("doctor", f.doctors)))
      )
    )

  private def scheduleFields(
      rt: Runtime[AppState, Msg],
      formSignal: Signal[MonitorForm]
  ): HtmlElement =
    div(
      cls := "form-group",
      p(cls := "hint", "Dates and times are Warsaw time."),
      label(
        cls := "field",
        "First date",
        input(
          tpe := "date",
          value <-- formSignal.map(f => isoText(f.dateFrom)).distinct,
          onInput.mapToValue --> (v =>
            rt.dispatch(Msg.MonitorDateFromChanged(v))
          )
        )
      ),
      label(
        cls := "field",
        "Last date",
        input(
          tpe := "date",
          value <-- formSignal.map(f => isoText(f.dateTo)).distinct,
          onInput.mapToValue --> (v => rt.dispatch(Msg.MonitorDateToChanged(v)))
        )
      ),
      label(
        cls := "field",
        "Earliest time",
        input(
          tpe := "time",
          value <-- formSignal.map(f => isoText(f.timeFrom)).distinct,
          onInput.mapToValue --> (v =>
            rt.dispatch(Msg.MonitorTimeFromChanged(v))
          )
        )
      ),
      label(
        cls := "field",
        "Latest time",
        input(
          tpe := "time",
          value <-- formSignal.map(f => isoText(f.timeTo)).distinct,
          onInput.mapToValue --> (v => rt.dispatch(Msg.MonitorTimeToChanged(v)))
        )
      ),
      fieldSet(
        cls := "field",
        legend("Days of the week"),
        DayOfWeek.values.toList.map: day =>
          choice(
            groupName = "monitor-day",
            kind = "checkbox",
            text = dayName(day),
            selected = formSignal.map(_.daysOfWeek.contains(day)).distinct,
            onSelect = () => rt.dispatch(Msg.MonitorDayToggled(day))
          )
      ),
      label(
        cls := "field",
        s"Check every (minutes, at least ${MonitorForm.minimumIntervalMinutes})",
        input(
          tpe := "number",
          value <-- formSignal.map(_.intervalMinutes.toString).distinct,
          onInput.mapToValue --> (v =>
            rt.dispatch(Msg.MonitorIntervalChanged(v))
          )
        )
      ),
      label(
        cls := "field checkbox",
        input(
          tpe := "checkbox",
          checked <-- formSignal.map(_.autoBook).distinct,
          onInput.mapToChecked --> (v =>
            rt.dispatch(Msg.MonitorAutoBookChanged(v))
          )
        ),
        span("Book a slot automatically when one is found")
      )
    )

  // --- Shared rendering ---

  /** One radio or checkbox, wrapped in a real `label` so the text is its name
    * and clicking the text works.
    */
  private def choice(
      groupName: String,
      kind: String,
      text: String,
      selected: Signal[Boolean],
      onSelect: () => Unit
  ): HtmlElement =
    label(
      cls := "choice",
      input(
        tpe := kind,
        nameAttr := groupName,
        checked <-- selected,
        onChange --> (_ => onSelect())
      ),
      span(text)
    )

  private def choiceGroup(
      rt: Runtime[AppState, Msg],
      legendText: String,
      groupName: String,
      kind: String,
      waitingText: String,
      loadingText: String,
      choices: Signal[LoadState[List[NamedId]]],
      isSelected: NamedId => Signal[Boolean],
      select: NamedId => Msg,
      chosen: Option[Signal[String]]
  ): HtmlElement =
    fieldSet(
      cls := "field",
      legend(legendText),
      // What is chosen is shown whatever the dictionary of alternatives is
      // doing, so an edited monitor's saved criteria are readable immediately.
      chosen
        .map(text =>
          p(cls := "chosen", "Chosen: ", child.text <-- text.distinct)
        )
        .toList,
      children <-- choices.map {
        case LoadState.NotAsked =>
          List(p(cls := "hint", waitingText))
        case LoadState.Loading =>
          List(p(cls := "loading", loadingText))
        case LoadState.Failed(message) =>
          List(
            p(cls := "error", role := "alert", message),
            button(
              tpe := "button",
              "Try again",
              onClick.mapTo(Msg.DictionaryRetryRequested) --> (m =>
                rt.dispatch(m)
              )
            )
          )
        case LoadState.Loaded(Nil) =>
          List(p(cls := "placeholder", "Luxmed offered nothing to choose."))
        case LoadState.Loaded(items) =>
          items.map: item =>
            choice(
              groupName = groupName,
              kind = kind,
              text = item.name,
              selected = isSelected(item),
              onSelect = () => rt.dispatch(select(item))
            )
      }
    )

  private def mapLoad[A, B](load: LoadState[A])(f: A => B): LoadState[B] =
    load match
      case LoadState.NotAsked        => LoadState.NotAsked
      case LoadState.Loading         => LoadState.Loading
      case LoadState.Loaded(value)   => LoadState.Loaded(f(value))
      case LoadState.Failed(message) => LoadState.Failed(message)

  private def facilityChoices(
      response: FacilitiesDoctorsResponse
  ): List[NamedId] =
    response.facilities.map(f => NamedId(f.id, f.name))

  private def doctorChoices(
      response: FacilitiesDoctorsResponse
  ): List[NamedId] =
    response.doctors.map(d => NamedId(d.id, d.name))

  /** `LocalDate.toString` and `LocalTime.toString` are already the ISO text
    * `input type="date"` and `type="time"` read and write, so no formatter is
    * involved; an unanswered field is empty text.
    */
  private def isoText[A](value: Option[A]): String =
    value.fold("")(_.toString)

  private def stateText(state: MonitorState): String = state match
    case MonitorState.Active    => "Active"
    case MonitorState.Paused    => "Paused"
    case MonitorState.Completed => "Completed"
    case MonitorState.Failed    => "Failed"

  private def nameOrNone(chosen: Option[NamedId]): String =
    chosen.map(_.name).getOrElse("nothing yet")

  private def chosenText(kind: String, chosen: List[NamedId]): String =
    if chosen.isEmpty then s"Any $kind"
    else chosen.map(_.name).mkString(", ")

  private def daysText(days: List[DayOfWeek]): String =
    if days.isEmpty then "No days chosen"
    else days.map(dayName).mkString(", ")

  private def dayName(day: DayOfWeek): String =
    val lower = day.toString.toLowerCase
    s"${lower.head.toUpper}${lower.tail}"
