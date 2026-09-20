package lmbot.shared.domain

import java.time.Instant

opaque type MonitorEventId = Long
object MonitorEventId:
  def apply(value: Long): MonitorEventId = value
  extension (id: MonitorEventId) def value: Long = id

/** The append-only kinds of `monitor_events` (spec §5.3). Booking kinds are
  * defined now — Plan 6 writes them — so the log's contract and the detail view
  * do not change when auto-booking lands.
  */
enum MonitorEventKind:
  case SlotFound
  case NotificationSent
  case NotificationFailed
  case BookingAttempted
  case BookingSucceeded
  case BookingFailed
  case MonitorPaused
  case MonitorCompleted
  case MonitorFailed
  case Error

object MonitorEventKind:
  extension (kind: MonitorEventKind)
    def wireName: String = kind match
      case SlotFound          => "slot_found"
      case NotificationSent   => "notification_sent"
      case NotificationFailed => "notification_failed"
      case BookingAttempted   => "booking_attempted"
      case BookingSucceeded   => "booking_succeeded"
      case BookingFailed      => "booking_failed"
      case MonitorPaused      => "monitor_paused"
      case MonitorCompleted   => "monitor_completed"
      case MonitorFailed      => "monitor_failed"
      case Error              => "error"

  def fromWire(value: String): Either[String, MonitorEventKind] =
    values
      .find(_.wireName == value)
      .toRight(s"unknown monitor event kind: $value")

/** One entry of a monitor's event log. `slot` is present exactly for
  * slot-related kinds; `detail` carries fixed text or a `SafeDiagnostic`, never
  * a raw payload.
  */
final case class MonitorEventView(
    id: MonitorEventId,
    monitorId: MonitorId,
    kind: MonitorEventKind,
    slot: Option[FoundSlot],
    detail: Option[String],
    createdAt: Instant
)
