package lmbot.backend.monitor

import java.sql.{Date as SqlDate, Time as SqlTime}
import java.time.{DayOfWeek, Instant, OffsetDateTime, ZoneOffset}

import lmbot.backend.db.{
  AccountRepo,
  LuxmedAccountRow,
  MonitorEventRepo,
  MonitorEventRow,
  MonitorRepo,
  MonitorRow
}
import lmbot.backend.support.attempt
import lmbot.backend.support.result
import lmbot.backend.support.result.?
import lmbot.shared.api.ApiError
import lmbot.shared.domain.{
  AccountId,
  FoundSlot,
  MonitorDraft,
  MonitorEventId,
  MonitorEventKind,
  MonitorEventView,
  MonitorId,
  MonitorState,
  MonitorView,
  NamedId,
  UserId
}

/** Owns monitor validation, ownership enforcement, and state-transition policy.
  * `MonitorRepo` stays a dumb persistence layer: it enforces the expected
  * current state atomically and reports whether a row changed, but it has no
  * opinion on which transitions are allowed — that policy lives here.
  *
  * Scheduling and running monitors (the execution loop that actually queries
  * Luxmed for slots) is Plan 5's job; this service only manages the stored
  * definition and its lifecycle state.
  */
final class MonitorService(
    monitors: MonitorRepo,
    accounts: AccountRepo,
    monitorEvents: MonitorEventRepo,
    now: () => Instant = () => Instant.now()
):
  private val createFailed = "The monitor could not be created."
  private val updateFailed = "The monitor could not be updated."
  private val loadFailed = "The monitor could not be loaded."
  private val listFailed = "The monitors could not be loaded."
  private val deleteFailed = "The monitor could not be deleted."

  /** New monitors start here; `pause`/`resume` are the only ways out. */
  private val activePaused = List(MonitorState.Active, MonitorState.Paused)

  def create(
      ownerId: UserId,
      draft: MonitorDraft
  ): Either[ApiError, MonitorView] =
    result:
      val valid = validate(draft).?
      val account = findOwnedAccount(ownerId, draft.accountId, createFailed).?
      val id = attempt(dbFailure(createFailed))(monitors.reserveId()).?
      val timestamp = now().atOffset(ZoneOffset.UTC)
      val row = toRow(
        id = id,
        accountId = account.id,
        state = MonitorState.Active.wireName,
        createdAt = timestamp,
        updatedAt = timestamp,
        valid = valid
      )
      val stored = attempt(dbFailure(createFailed))(monitors.insert(row)).?
      toView(stored)

  def list(ownerId: UserId): Either[ApiError, List[MonitorView]] =
    attempt(dbFailure(listFailed))(monitors.listOwned(ownerId))
      .map(_.toList.map(toView))

  def get(
      ownerId: UserId,
      monitorId: MonitorId
  ): Either[ApiError, MonitorView] =
    result:
      val row = findOwnedMonitor(ownerId, monitorId, loadFailed).?
      toView(row)

  def update(
      ownerId: UserId,
      monitorId: MonitorId,
      draft: MonitorDraft
  ): Either[ApiError, MonitorView] =
    result:
      val valid = validate(draft).?
      val existing = findOwnedMonitor(ownerId, monitorId, updateFailed).?
      val account = findOwnedAccount(ownerId, draft.accountId, updateFailed).?
      val row = toRow(
        id = monitorId.value,
        accountId = account.id,
        state = existing.state,
        createdAt = existing.createdAt,
        updatedAt = now().atOffset(ZoneOffset.UTC),
        valid = valid
      )
      val stored = attempt(dbFailure(updateFailed))(
        monitors.updateOwned(row, ownerId)
      ).?.toRight(ApiError.NotFound).?
      toView(stored)

  /** Pausing an already-paused monitor is a no-op success, not an error: the
    * expected-state set for the atomic update includes both `Active` and
    * `Paused`.
    */
  def pause(
      ownerId: UserId,
      monitorId: MonitorId
  ): Either[ApiError, MonitorView] =
    transition(ownerId, monitorId, activePaused, MonitorState.Paused)

  /** Symmetric to `pause`: resuming an already-active monitor is a no-op
    * success. A `failed` monitor is resumable once its cause is addressed (spec
    * §5.5); `completed` is terminal, so resuming it is a disallowed transition
    * on an owned monitor, reported as `Conflict`.
    */
  def resume(
      ownerId: UserId,
      monitorId: MonitorId
  ): Either[ApiError, MonitorView] =
    transition(
      ownerId,
      monitorId,
      activePaused :+ MonitorState.Failed,
      MonitorState.Active
    )

  /** The monitor's append-only event log, newest first, owner-scoped. The limit
    * is clamped rather than rejected: a UI asking for 0 gets 1, a UI asking for
    * 10_000 gets the maximum.
    */
  def events(
      ownerId: UserId,
      monitorId: MonitorId,
      limit: Int
  ): Either[ApiError, List[MonitorEventView]] =
    result:
      findOwnedMonitor(ownerId, monitorId, loadFailed).?
      val clamped = Math.max(1, Math.min(limit, MonitorService.maxEventLimit))
      attempt(dbFailure(loadFailed))(
        monitorEvents.listRecent(monitorId, clamped)
      ).?.toList.map(toEventView)

  def delete(ownerId: UserId, monitorId: MonitorId): Either[ApiError, Unit] =
    attempt.either(dbFailure(deleteFailed)):
      if monitors.deleteOwned(monitorId, ownerId) then Right(())
      else Left(ApiError.NotFound)

  private def transition(
      ownerId: UserId,
      monitorId: MonitorId,
      expectedStates: List[MonitorState],
      newState: MonitorState
  ): Either[ApiError, MonitorView] =
    result:
      val existing = findOwnedMonitor(ownerId, monitorId, updateFailed).?
      val accountId = AccountId(existing.luxmedAccountId)
      val updated = attempt(dbFailure(updateFailed))(
        monitors.transitionOwned(
          monitorId,
          accountId,
          ownerId,
          expectedStates,
          newState
        )
      ).?
      val row = updated.toRight(ApiError.Conflict(conflictMessage(newState))).?
      toView(row)

  private def conflictMessage(newState: MonitorState): String = newState match
    case MonitorState.Paused =>
      "The monitor cannot be paused from its current state."
    case MonitorState.Active =>
      "The monitor cannot be resumed from its current state."
    case other =>
      s"The monitor cannot transition to ${other.wireName} from its current state."

  /** Plain `Either`-returning (no `.?`): called both from within `result`
    * blocks (where the caller unwraps with `.?`) and, in `transition`, needs
    * its own subsequent `.?` at the call site — neither of which this helper
    * can assume, so it stays a total function over `Either`.
    */
  private def findOwnedAccount(
      ownerId: UserId,
      accountId: AccountId,
      onFailure: String
  ): Either[ApiError, LuxmedAccountRow] =
    attempt(dbFailure(onFailure))(accounts.findOwned(accountId, ownerId))
      .flatMap(_.toRight(ApiError.NotFound))

  private def findOwnedMonitor(
      ownerId: UserId,
      monitorId: MonitorId,
      onFailure: String
  ): Either[ApiError, MonitorRow] =
    attempt(dbFailure(onFailure))(monitors.findOwned(monitorId, ownerId))
      .flatMap(_.toRight(ApiError.NotFound))

  private def dbFailure(message: String): Throwable => ApiError =
    _ => ApiError.Unexpected(message)

  /** A `MonitorDraft` that has passed every structural check and carries its
    * derived `daysOfWeek` bitmask, so `toRow` never has to re-validate.
    */
  final private case class ValidMonitorDraft(
      draft: MonitorDraft,
      daysOfWeekMask: Short
  )

  private def validate(
      draft: MonitorDraft
  ): Either[ApiError, ValidMonitorDraft] =
    result:
      Either
        .cond(
          draft.intervalMinutes >= 5,
          (),
          ApiError.Validation("Interval must be at least 5 minutes.")
        )
        .?
      Either
        .cond(
          !draft.dateFrom.isAfter(draft.dateTo),
          (),
          ApiError.Validation("dateFrom must not be after dateTo.")
        )
        .?
      Either
        .cond(
          draft.timeFrom.isBefore(draft.timeTo),
          (),
          ApiError.Validation("timeFrom must be before timeTo.")
        )
        .?
      Either
        .cond(
          draft.daysOfWeek.nonEmpty,
          (),
          ApiError.Validation("Select at least one day of the week.")
        )
        .?
      ValidMonitorDraft(
        draft,
        MonitorService.encodeDaysOfWeek(draft.daysOfWeek)
      )

  private def toRow(
      id: Long,
      accountId: Long,
      state: String,
      createdAt: OffsetDateTime,
      updatedAt: OffsetDateTime,
      valid: ValidMonitorDraft
  ): MonitorRow =
    val draft = valid.draft
    MonitorRow(
      id = id,
      luxmedAccountId = accountId,
      name = draft.name,
      cityId = draft.city.id,
      cityName = draft.city.name,
      serviceId = draft.service.id,
      serviceName = draft.service.name,
      facilityIds = draft.facilities.map(_.id),
      facilityNames = draft.facilities.map(_.name),
      doctorIds = draft.doctors.map(_.id),
      doctorNames = draft.doctors.map(_.name),
      dateFrom = SqlDate.valueOf(draft.dateFrom),
      dateTo = SqlDate.valueOf(draft.dateTo),
      timeFrom = SqlTime.valueOf(draft.timeFrom),
      timeTo = SqlTime.valueOf(draft.timeTo),
      daysOfWeek = valid.daysOfWeekMask,
      autoBook = draft.autoBook,
      intervalMinutes = draft.intervalMinutes,
      state = state,
      createdAt = createdAt,
      updatedAt = updatedAt
    )

  private def toView(row: MonitorRow): MonitorView =
    MonitorView(
      id = MonitorId(row.id),
      accountId = AccountId(row.luxmedAccountId),
      name = row.name,
      state = parseState(row.state),
      city = NamedId(row.cityId, row.cityName),
      service = NamedId(row.serviceId, row.serviceName),
      facilities = row.facilityIds.zip(row.facilityNames).map(NamedId.apply),
      doctors = row.doctorIds.zip(row.doctorNames).map(NamedId.apply),
      dateFrom = row.dateFrom.toLocalDate,
      dateTo = row.dateTo.toLocalDate,
      timeFrom = row.timeFrom.toLocalTime,
      timeTo = row.timeTo.toLocalTime,
      daysOfWeek = MonitorService.decodeDaysOfWeek(row.daysOfWeek),
      autoBook = row.autoBook,
      intervalMinutes = row.intervalMinutes,
      createdAt = row.createdAt.toInstant,
      updatedAt = row.updatedAt.toInstant,
      lastCheckAt = row.lastCheckAt.map(_.toInstant),
      lastCheckSummary = row.lastCheckSummary
    )

  private def toEventView(row: MonitorEventRow): MonitorEventView =
    MonitorEventView(
      id = MonitorEventId(row.id),
      monitorId = MonitorId(row.monitorId),
      kind = parseEventKind(row.kind),
      slot = toSlot(row),
      detail = row.detail,
      createdAt = row.createdAt.toInstant
    )

  /** The slot columns are all-or-none (a database constraint), so the first
    * present value means the slot is present.
    */
  private def toSlot(row: MonitorEventRow): Option[FoundSlot] =
    for
      key <- row.slotKey
      clinicId <- row.slotClinicId
      doctorId <- row.slotDoctorId
      from <- row.slotFrom
      to <- row.slotTo
      telemedicine <- row.slotTelemedicine
    yield FoundSlot(
      key = key,
      clinicId = clinicId,
      clinicName = row.slotClinicName,
      doctorId = doctorId,
      doctorName = row.slotDoctorName.getOrElse(""),
      from = from,
      to = to,
      telemedicine = telemedicine
    )

  /** The `kind` column has a DB `check` constraint mirroring
    * `MonitorEventKind.wireName`, so an unrecognised value here means the
    * invariant has already been violated at the storage layer.
    */
  private def parseEventKind(value: String): MonitorEventKind =
    MonitorEventKind
      .fromWire(value)
      .getOrElse(
        throw IllegalStateException(
          s"corrupt monitor event kind persisted: $value"
        )
      )

  /** The `state` column has a DB `check` constraint mirroring
    * `MonitorState.wireName`, so an unrecognised value here means the invariant
    * has already been violated at the storage layer, not something this service
    * can recover from.
    */
  private def parseState(value: String): MonitorState =
    MonitorState
      .fromWire(value)
      .getOrElse(
        throw IllegalStateException(s"corrupt monitor state persisted: $value")
      )

object MonitorService:
  /** The largest page of events the API will return. */
  private[backend] val maxEventLimit: Int = 200

  /** Monday is bit 0, Sunday is bit 6 — matches the `days_of_week smallint`
    * column's documented layout in the migration.
    */
  private[backend] def encodeDaysOfWeek(days: List[DayOfWeek]): Short =
    days.foldLeft(0)((mask, day) => mask | (1 << (day.getValue - 1))).toShort

  private[backend] def decodeDaysOfWeek(mask: Short): List[DayOfWeek] =
    DayOfWeek.values.toList.filter(day =>
      (mask & (1 << (day.getValue - 1))) != 0
    )
