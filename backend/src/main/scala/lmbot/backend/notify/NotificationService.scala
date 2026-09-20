package lmbot.backend.notify

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

import gears.async.Async
import lmbot.backend.account.AccountStatusReason
import lmbot.backend.db.{MonitorEventRepo, MonitorRow, UserRepo}
import lmbot.shared.domain.{FoundSlot, MonitorEventKind, MonitorId, UserId}
import org.slf4j.LoggerFactory

/** Turns engine events into messages on the configured channel.
  *
  * Delivery failures never escape: they become `notification_failed` events
  * (for slot notifications) or are simply not delivered. A user without a
  * linked Telegram chat is not an error either — the slot is already in the
  * event log and the UI says no notifications will be delivered (spec §3.5).
  */
final class NotificationService(
    users: UserRepo,
    channel: Option[NotificationChannel],
    events: MonitorEventRepo,
    now: () => OffsetDateTime
):

  private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
  private val endFormat = DateTimeFormatter.ofPattern("HH:mm")
  private val log = LoggerFactory.getLogger(getClass)

  def notifySlot(
      ownerId: UserId,
      monitor: MonitorRow,
      slot: FoundSlot
  )(using Async): Unit =
    withChannel(ownerId): (channel, chatId) =>
      val text = s"${monitor.name}: new slot at ${slotText(slot)}."
      deliver(channel, chatId, text) match
        case Right(()) =>
          events.append(
            MonitorId(monitor.id),
            MonitorEventKind.NotificationSent,
            Some(slot),
            None,
            now()
          )
        case Left(error) =>
          log.warn(
            s"Notification delivery failed for monitor ${monitor.id}: ${detail(error)}"
          )
          events.append(
            MonitorId(monitor.id),
            MonitorEventKind.NotificationFailed,
            Some(slot),
            Some(detail(error)),
            now()
          )

  def notifyAccountAuthFailure(
      ownerId: UserId,
      accountLabel: String,
      reason: AccountStatusReason
  )(using Async): Unit =
    withChannel(ownerId): (channel, chatId) =>
      val text =
        s"Luxmed account '$accountLabel' needs attention: ${reason.value} " +
          "Its monitors are paused."
      logFailure(deliver(channel, chatId, text), "account auth failure")

  def notifyMonitorFailed(
      ownerId: UserId,
      monitor: MonitorRow,
      reason: String
  )(using Async): Unit =
    withChannel(ownerId): (channel, chatId) =>
      val text =
        s"Monitor '${monitor.name}' stopped after repeated errors: $reason " +
          "Resume it from the dashboard once the cause is fixed."
      logFailure(
        deliver(channel, chatId, text),
        s"monitor ${monitor.id} failed"
      )

  def notifyMonitorCompleted(ownerId: UserId, monitor: MonitorRow)(using
      Async
  ): Unit =
    withChannel(ownerId): (channel, chatId) =>
      val text =
        s"Monitor '${monitor.name}' completed: its date range has passed."
      logFailure(
        deliver(channel, chatId, text),
        s"monitor ${monitor.id} completed"
      )

  /** Ops notification for a rejected Luxmed app version (spec §5.5). Every
    * enabled admin with a linked chat gets one message; admins without one are
    * skipped rather than failing the check.
    */
  def notifyAdminsVersionRejected(detail: String)(using Async): Unit =
    channel.foreach: channel =>
      val text =
        s"lm-bot: Luxmed rejected the configured app version. $detail " +
          "Monitors cannot search until it is updated."
      users
        .listAdmins()
        .foreach: admin =>
          admin.telegramChatId.foreach: chatId =>
            channel.send(chatId, text)

  private def withChannel(ownerId: UserId)(
      body: (NotificationChannel, Long) => Unit
  ): Unit =
    for
      channel <- channel
      user <- users.findById(ownerId)
      chatId <- user.telegramChatId
    do body(channel, chatId)

  private def deliver(
      channel: NotificationChannel,
      chatId: Long,
      text: String
  )(using Async): Either[NotificationError, Unit] =
    channel.send(chatId, text)

  /** Non-slot notifications have no event to record a failure in, so at least
    * make it visible in the logs; the state transition itself is already
    * persisted.
    */
  private def logFailure(
      result: Either[NotificationError, Unit],
      context: String
  ): Unit = result match
    case Left(error) =>
      log.warn(s"Notification delivery failed ($context): ${detail(error)}")
    case Right(()) => ()

  private def detail(error: NotificationError): String = error match
    case NotificationError.Transient(detail) => detail
    case NotificationError.Rejected(detail)  => detail

  private def slotText(slot: FoundSlot): String =
    val clinic = slot.clinicName.getOrElse("an unnamed clinic")
    s"$clinic, ${slot.from.format(timeFormat)}–${slot.to.format(endFormat)}, " +
      slot.doctorName
