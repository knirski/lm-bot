package lmbot.frontend

import java.time.DayOfWeek

import lmbot.shared.api.ApiError
import lmbot.shared.domain.{
  AccountId,
  AccountView,
  DictionaryCity,
  DictionaryService,
  FacilitiesDoctorsResponse,
  MonitorId,
  MonitorState,
  MonitorView,
  NamedId,
  UserView
}

enum Msg:
  case UsernameChanged(value: String)
  case PasswordChanged(value: String)
  case LoginSubmitted
  case LoginSucceeded(user: UserView)
  case LoginFailed(error: ApiError)
  case SessionRestored(user: UserView)
  case SessionAbsent
  case LogoutRequested
  case LoggedOut

  case AccountsRequested
  case AccountsLoaded(accounts: List[AccountView])
  case AccountsLoadFailed(error: ApiError)

  case LinkLabelChanged(value: String)
  case LinkUsernameChanged(value: String)
  case LinkPasswordChanged(value: String)
  case LinkAccountSubmitted
  case AccountLinked(account: AccountView)
  case AccountLinkFailed(error: ApiError)

  case DeleteAccountRequested(accountId: AccountId)
  case DeleteConfirmed
  case DeleteCancelled
  case AccountDeleted(accountId: AccountId)
  case AccountDeleteFailed(error: ApiError)

  case MonitorsRequested
  case MonitorsLoaded(monitors: List[MonitorView])
  case MonitorsLoadFailed(error: ApiError)

  case MonitorCreateStarted
  case MonitorEditStarted(monitor: MonitorView)
  case MonitorFormCancelled

  /** "Next" on any step, and "Save" on the last one — advancing past the review
    * step is what submitting means, so one message covers the form's single
    * forward control.
    */
  case MonitorStepAdvanced
  case MonitorStepReturned

  case MonitorAccountSelected(accountId: AccountId)
  case MonitorNameChanged(value: String)
  case MonitorCitySelected(city: NamedId)
  case MonitorServiceSelected(service: NamedId)
  case MonitorFacilityToggled(facility: NamedId)
  case MonitorDoctorToggled(doctor: NamedId)

  /** Raw ISO text straight from `input type="date"` / `type="time"` /
    * `type="number"`; `Update` narrows it, because parsing is a decision.
    */
  case MonitorDateFromChanged(value: String)
  case MonitorDateToChanged(value: String)
  case MonitorTimeFromChanged(value: String)
  case MonitorTimeToChanged(value: String)
  case MonitorIntervalChanged(value: String)

  case MonitorDayToggled(day: DayOfWeek)
  case MonitorAutoBookChanged(value: Boolean)

  case MonitorSubmitted
  case MonitorSaved(monitor: MonitorView)
  case MonitorSaveFailed(error: ApiError)

  // Dictionary requests carry the ids they depend on, and every response
  // carries them back, so `Update` can drop an answer to a question the user
  // has since changed their mind about.
  case CitiesRequested(accountId: AccountId)
  case CitiesLoaded(accountId: AccountId, cities: List[DictionaryCity])
  case CitiesLoadFailed(accountId: AccountId, error: ApiError)

  case ServicesRequested(accountId: AccountId)
  case ServicesLoaded(accountId: AccountId, services: List[DictionaryService])
  case ServicesLoadFailed(accountId: AccountId, error: ApiError)

  /** "Try again" after a failed dictionary load. Which dictionary that is
    * follows from the step the wizard is on, which `Update` already knows — so
    * the view does not have to work it out.
    */
  case DictionaryRetryRequested

  case ProvidersRequested(accountId: AccountId, cityId: Long, serviceId: Long)
  case ProvidersLoaded(
      accountId: AccountId,
      cityId: Long,
      serviceId: Long,
      response: FacilitiesDoctorsResponse
  )
  case ProvidersLoadFailed(
      accountId: AccountId,
      cityId: Long,
      serviceId: Long,
      error: ApiError
  )

  case MonitorPauseRequested(monitorId: MonitorId)
  case MonitorResumeRequested(monitorId: MonitorId)
  case MonitorStateChanged(monitorId: MonitorId, state: MonitorState)
  case MonitorStateChangeFailed(monitorId: MonitorId, error: ApiError)

  case MonitorDeleteRequested(monitorId: MonitorId)
  case MonitorDeleteConfirmed
  case MonitorDeleteCancelled
  case MonitorDeleted(monitorId: MonitorId)
  case MonitorDeleteFailed(error: ApiError)
