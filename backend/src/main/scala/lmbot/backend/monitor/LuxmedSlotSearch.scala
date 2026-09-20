package lmbot.backend.monitor

import gears.async.Async
import lmbot.backend.db.MonitorRow
import lmbot.backend.luxmed.model.{
  CityId,
  Doctor,
  DoctorId,
  FacilityId,
  ServiceVariantId,
  Term,
  TermsQuery
}
import lmbot.backend.luxmed.{LuxmedClient, LuxmedError}
import lmbot.shared.api.ApiError
import lmbot.shared.domain.{AccountId, FoundSlot}

/** Resolves a monitor's Luxmed account through the shared registry and turns a
  * terms search into the engine's `FoundSlot` values.
  *
  * The client lookup is a function so tests can inject a stub-backed client;
  * production passes `AccountClientRegistry.forAccount`.
  */
final class LuxmedSlotSearch(
    clientFor: AccountId => Either[ApiError, LuxmedClient]
) extends SlotSearch:

  def search(monitor: MonitorRow)(using
      Async
  ): Either[CheckFailure, List[FoundSlot]] =
    for
      client <- clientFor(AccountId(monitor.luxmedAccountId)).left.map(_ =>
        CheckFailure.Persistent("The Luxmed account is no longer available.")
      )
      response <- client
        .searchTerms(LuxmedSlotMapping.query(monitor))
        .left
        .map(
          LuxmedSlotMapping.classify
        )
    yield response.termsForService.termsForDays
      .flatMap(_.terms)
      .map(LuxmedSlotMapping.toFoundSlot)

/** The pure half of [[LuxmedSlotSearch]]: mapping a monitor to a query, a term
  * to a slot, and a `LuxmedError` to the engine's failure vocabulary.
  */
object LuxmedSlotMapping:

  def query(monitor: MonitorRow): TermsQuery =
    TermsQuery(
      cityId = CityId(monitor.cityId),
      serviceVariantId = ServiceVariantId(monitor.serviceId),
      searchDateFrom = monitor.dateFrom.toLocalDate,
      searchDateTo = monitor.dateTo.toLocalDate,
      facilityIds = monitor.facilityIds.map(FacilityId.apply),
      doctorIds = monitor.doctorIds.map(DoctorId.apply)
    )

  /** `key` is the stable identity dedup is built on: the Luxmed schedule id
    * plus the Warsaw-local start. Datetimes come from `LuxmedDateTime`, which
    * already normalises both wire formats to Europe/Warsaw.
    */
  def toFoundSlot(term: Term): FoundSlot =
    val from = term.dateTimeFrom.value.toLocalDateTime
    val to = term.dateTimeTo.value.toLocalDateTime
    FoundSlot(
      key = s"${term.scheduleId.value}:$from",
      clinicId = term.clinicId,
      clinicName = term.clinic,
      doctorId = term.doctor.id.value,
      doctorName = doctorName(term.doctor),
      from = from,
      to = to,
      telemedicine = term.isTelemedicine
    )

  def classify(error: LuxmedError): CheckFailure =
    error match
      case LuxmedError.AuthFailed                => CheckFailure.AuthRejected
      case _: LuxmedError.UnexpectedAuthResponse => CheckFailure.Challenge
      case LuxmedError.SessionExpired            =>
        CheckFailure.Transient("The Luxmed session expired.")
      case LuxmedError.RateLimited             => CheckFailure.RateLimited
      case _: LuxmedError.VersionRejected      => CheckFailure.VersionRejected
      case LuxmedError.NetworkFailure(details) =>
        CheckFailure.Transient(details.value)
      case LuxmedError.Transient(status) =>
        CheckFailure.Transient(s"Luxmed returned HTTP $status")
      case LuxmedError.PersistenceFailed(details) =>
        CheckFailure.Transient(details.value)
      case LuxmedError.DecodeFailed(details) =>
        CheckFailure.Persistent(details.value)
      case LuxmedError.ProtocolViolation(details) =>
        CheckFailure.Persistent(details.value)
      case LuxmedError.ApiRejected(details) =>
        CheckFailure.Persistent(details.value)
      case LuxmedError.SlotGone =>
        CheckFailure.Persistent("The slot is no longer available.")

  private def doctorName(doctor: Doctor): String =
    List(doctor.academicTitle, doctor.firstName, doctor.lastName).flatten
      .mkString(" ")
      .trim
