package lmbot.backend.monitor

import java.time.OffsetDateTime

import gears.async.Async
import lmbot.backend.db.{MonitorEventRepo, MonitorRow}
import lmbot.backend.notify.NotificationService
import lmbot.shared.domain.{MonitorId, SlotCriteria, SlotFilter, UserId}

/** What one check produced. */
enum CheckResult:
  case Succeeded(slotsFound: Int, newSlots: Int)
  case Failed(failure: CheckFailure)

/** One monitor's check: search, filter by the monitor's criteria, dedup through
  * `monitor_events`, and notify only the slots that were genuinely new.
  *
  * All I/O is behind `SlotSearch`, `MonitorEventRepo`, and
  * `NotificationService`, so this class is testable without HTTP.
  */
final class MonitorCheck(
    search: SlotSearch,
    events: MonitorEventRepo,
    notifier: NotificationService,
    now: () => OffsetDateTime
):

  def run(monitor: MonitorRow, ownerId: UserId)(using Async): CheckResult =
    search.search(monitor) match
      case Left(failure) => CheckResult.Failed(failure)
      case Right(slots)  =>
        val matching = slots.filter(SlotFilter.matches(_, criteria(monitor)))
        val newSlots = matching.filter: slot =>
          events.recordSlotFound(MonitorId(monitor.id), slot, now())
        newSlots.foreach(slot => notifier.notifySlot(ownerId, monitor, slot))
        // A delivery that failed earlier is retried here, so a transient
        // Telegram outage does not silently lose a slot. The attempt cap keeps
        // a permanently unreachable chat from being retried forever.
        events
          .slotsAwaitingDelivery(
            MonitorId(monitor.id),
            MonitorCheck.maxDeliveryAttempts
          )
          .filter(SlotFilter.matches(_, criteria(monitor)))
          .foreach(slot => notifier.notifySlot(ownerId, monitor, slot))
        CheckResult.Succeeded(matching.size, newSlots.size)

  private def criteria(monitor: MonitorRow): SlotCriteria =
    SlotCriteria(
      facilityIds = monitor.facilityIds,
      doctorIds = monitor.doctorIds,
      dateFrom = monitor.dateFrom.toLocalDate,
      dateTo = monitor.dateTo.toLocalDate,
      timeFrom = monitor.timeFrom.toLocalTime,
      timeTo = monitor.timeTo.toLocalTime,
      daysOfWeek = MonitorService.decodeDaysOfWeek(monitor.daysOfWeek)
    )

object MonitorCheck:
  /** Attempts per slot before a failed delivery stands. */
  val maxDeliveryAttempts: Int = 3
