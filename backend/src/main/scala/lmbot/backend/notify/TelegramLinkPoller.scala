package lmbot.backend.notify

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.DurationConverters.*
import scala.util.{Failure, Success}

import gears.async.{Async, Future, ReadableChannel}
import lmbot.backend.db.UserRepo
import lmbot.backend.support.Sleeper

/** Long-polls Telegram for `/start <code>` messages (spec §3.5: the only
  * inbound interaction in v1). A poll races the stop signal, so shutdown does
  * not wait for the long-poll timeout.
  */
final class TelegramLinkPoller(
    api: TelegramApi,
    link: TelegramLinkService,
    users: UserRepo,
    sleeper: Sleeper,
    retryDelay: FiniteDuration = 5.seconds,
    pollTimeoutSeconds: Int = 10
):

  def run(stop: ReadableChannel[Unit])(using Async.Spawn): Unit =
    var offset = 0L
    var running = true
    while running do
      val poll = Future(api.getUpdates(offset, pollTimeoutSeconds))
      Async.either(stop.readSource, poll).awaitResult match
        case Left(_) =>
          poll.cancel()
          running = false
        case Right(Success(Right(updates))) =>
          updates.foreach: update =>
            offset = Math.max(offset, update.updateId + 1)
            handle(update)
        case Right(Success(Left(NotificationError.Transient(_)))) =>
          sleeper.sleep(retryDelay.toJava)
        case Right(Success(Left(NotificationError.Rejected(_)))) =>
          // A bad token or a removed bot will not fix itself by retrying fast.
          sleeper.sleep((retryDelay * 6).toJava)
        case Right(Failure(_)) =>
          sleeper.sleep(retryDelay.toJava)

  private def handle(update: TelegramUpdate)(using Async): Unit =
    for
      message <- update.message
      text <- message.text
      if text.startsWith("/start")
    do
      val code = text.stripPrefix("/start").trim
      link.consume(code) match
        case Some(userId) =>
          users.setTelegramChatId(userId, message.chat.id)
          api.sendMessage(message.chat.id, TelegramLinkPoller.linkedMessage)
        case None =>
          api.sendMessage(message.chat.id, TelegramLinkPoller.expiredMessage)

object TelegramLinkPoller:
  val linkedMessage: String =
    "Linked. lm-bot will send monitor notifications to this chat."
  val expiredMessage: String =
    "This link is expired or unknown. Generate a new one in lm-bot settings."
