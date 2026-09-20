package lmbot.backend.notify

import gears.async.Async

/** A delivery failure is a value: a notification that cannot be sent must never
  * break the check that produced it.
  */
enum NotificationError:
  case Transient(detail: String)
  case Rejected(detail: String)

/** The notification boundary (spec §3.5). v1 ships Telegram; other channels
  * only need to implement this trait.
  */
trait NotificationChannel:
  def send(chatId: Long, text: String)(using
      Async
  ): Either[NotificationError, Unit]
