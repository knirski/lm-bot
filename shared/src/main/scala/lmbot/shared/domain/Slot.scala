package lmbot.shared.domain

import java.time.{DayOfWeek, LocalDate, LocalDateTime, LocalTime}

/** A slot as returned by a Luxmed terms search, normalised to Europe/Warsaw.
  *
  * `key` is the slot's stable identity across searches: the Luxmed schedule id
  * plus the start instant. `monitor_events` persists it and a partial unique
  * index makes per-monitor dedup an insert-on-conflict decision.
  */
final case class FoundSlot(
    key: String,
    clinicId: Long,
    clinicName: Option[String],
    doctorId: Long,
    doctorName: String,
    from: LocalDateTime,
    to: LocalDateTime,
    telemedicine: Boolean
)

/** The monitor criteria slot filtering needs, independent of how the monitor is
  * stored.
  */
final case class SlotCriteria(
    facilityIds: List[Long],
    doctorIds: List[Long],
    dateFrom: LocalDate,
    dateTo: LocalDate,
    timeFrom: LocalTime,
    timeTo: LocalTime,
    daysOfWeek: List[DayOfWeek]
)

/** Pure Warsaw-local slot filtering (spec §8): no clock, no zone lookup — the
  * slot and the criteria are already Warsaw-local values.
  */
object SlotFilter:

  /** True when the slot starts inside `[timeFrom, timeTo)` on a selected
    * weekday inside `[dateFrom, dateTo]`, and belongs to a selected
    * clinic/doctor when any are selected. Empty provider lists mean "any".
    */
  def matches(slot: FoundSlot, criteria: SlotCriteria): Boolean =
    val day = slot.from.toLocalDate
    val time = slot.from.toLocalTime
    val facilityMatches =
      criteria.facilityIds.isEmpty || criteria.facilityIds.contains(
        slot.clinicId
      )
    val doctorMatches =
      criteria.doctorIds.isEmpty || criteria.doctorIds.contains(slot.doctorId)
    !day.isBefore(criteria.dateFrom) &&
    !day.isAfter(criteria.dateTo) &&
    !time.isBefore(criteria.timeFrom) &&
    time.isBefore(criteria.timeTo) &&
    criteria.daysOfWeek.contains(day.getDayOfWeek) &&
    facilityMatches &&
    doctorMatches
