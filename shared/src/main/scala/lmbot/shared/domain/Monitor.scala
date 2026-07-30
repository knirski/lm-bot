package lmbot.shared.domain

import java.time.{DayOfWeek, Instant, LocalDate, LocalTime}

opaque type MonitorId = Long
object MonitorId:
  def apply(value: Long): MonitorId = value
  extension (id: MonitorId) def value: Long = id

enum MonitorState:
  case Active, Paused, Completed, Failed

final case class MonitorDraft(
    accountId: AccountId,
    name: String,
    city: NamedId,
    service: NamedId,
    facilities: List[NamedId] = List.empty,
    doctors: List[NamedId] = List.empty,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    timeFrom: LocalTime,
    timeTo: LocalTime,
    daysOfWeek: List[DayOfWeek],
    autoBook: Boolean,
    intervalMinutes: Int = 10
)

final case class MonitorView(
    id: MonitorId,
    accountId: AccountId,
    name: String,
    state: MonitorState,
    city: NamedId,
    service: NamedId,
    facilities: List[NamedId],
    doctors: List[NamedId],
    dateFrom: LocalDate,
    dateTo: LocalDate,
    timeFrom: LocalTime,
    timeTo: LocalTime,
    daysOfWeek: List[DayOfWeek],
    autoBook: Boolean,
    intervalMinutes: Int,
    createdAt: Instant,
    updatedAt: Instant
)
