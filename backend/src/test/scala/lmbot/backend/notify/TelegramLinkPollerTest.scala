package lmbot.backend.notify

import java.time.{Duration, OffsetDateTime}

import scala.collection.mutable

import gears.async.{Async, UnboundedChannel}
import lmbot.backend.db.UserRepo
import lmbot.backend.luxmed.support.GearsTest
import lmbot.backend.support.{PostgresSuite, Sleeper}
import lmbot.shared.domain.{Role, UserId}

class TelegramLinkPollerTest extends PostgresSuite with GearsTest:

  private val now = OffsetDateTime.parse("2026-08-10T07:00:00Z")
  private val code = "ABCDEFGHJK"

  private def aUser(): UserId =
    UserId(UserRepo(xa).insert("telegram", "Telegram", "hash", Role.User).id)

  private def linkService() =
    TelegramLinkService(UserRepo(xa), Some("lm_bot"), () => now, () => code)

  final private class ScriptedApi(
      responses: mutable.Queue[Either[NotificationError, List[TelegramUpdate]]],
      closeStop: () => Unit
  ) extends TelegramApi:
    val offsets = mutable.ListBuffer.empty[Long]
    val sent = mutable.ListBuffer.empty[(Long, String)]

    def sendMessage(chatId: Long, text: String)(using
        Async
    ): Either[NotificationError, Unit] =
      sent += ((chatId, text))
      Right(())

    def getUpdates(offset: Long, timeoutSeconds: Int)(using
        Async
    ): Either[NotificationError, List[TelegramUpdate]] =
      offsets += offset
      if responses.isEmpty then
        closeStop()
        Right(Nil)
      else responses.dequeue()

  final private class RecordingSleeper extends Sleeper:
    val sleeps = mutable.ListBuffer.empty[Duration]
    def sleep(duration: Duration)(using Async): Unit =
      sleeps += duration

  private def anUpdate(id: Long, chatId: Long, text: String): TelegramUpdate =
    TelegramUpdate(
      id,
      Some(TelegramMessage(TelegramChat(chatId), Some(text)))
    )

  test("a valid /start code links the chat and replies"):
    val userId = aUser()
    linkService().createCode(userId)
    val stop = UnboundedChannel[Unit]()
    val api = ScriptedApi(
      mutable.Queue(
        Right(List(anUpdate(5L, 99L, s"/start $code")))
      ),
      () => stop.close()
    )
    val poller =
      TelegramLinkPoller(api, linkService(), UserRepo(xa), RecordingSleeper())

    runAsync(poller.run(stop))

    assertEquals(
      UserRepo(xa).findById(userId).flatMap(_.telegramChatId),
      Some(99L)
    )
    assertEquals(
      api.sent.toList,
      List(99L -> TelegramLinkPoller.linkedMessage)
    )
    assertEquals(api.offsets.toList.take(2), List(0L, 6L))

  test("an unknown or expired code replies without linking"):
    val userId = aUser()
    linkService().createCode(userId)
    val stop = UnboundedChannel[Unit]()
    val api = ScriptedApi(
      mutable.Queue(
        Right(List(anUpdate(5L, 99L, "/start WRONGWRONG")))
      ),
      () => stop.close()
    )
    val poller =
      TelegramLinkPoller(api, linkService(), UserRepo(xa), RecordingSleeper())

    runAsync(poller.run(stop))

    assertEquals(UserRepo(xa).findById(userId).flatMap(_.telegramChatId), None)
    assertEquals(
      api.sent.toList,
      List(99L -> TelegramLinkPoller.expiredMessage)
    )

  test("a transient failure sleeps the retry delay and resumes"):
    val stop = UnboundedChannel[Unit]()
    val api = ScriptedApi(
      mutable.Queue(Left(NotificationError.Transient("boom"))),
      () => stop.close()
    )
    val sleeper = RecordingSleeper()
    val poller =
      TelegramLinkPoller(api, linkService(), UserRepo(xa), sleeper)

    runAsync(poller.run(stop))

    assertEquals(sleeper.sleeps.toList, List(Duration.ofSeconds(5)))
    assertEquals(api.offsets.toList.take(2), List(0L, 0L))

  test("a rejected request backs off for longer than a transient one"):
    val stop = UnboundedChannel[Unit]()
    val api = ScriptedApi(
      mutable.Queue(Left(NotificationError.Rejected("bad token"))),
      () => stop.close()
    )
    val sleeper = RecordingSleeper()
    val poller =
      TelegramLinkPoller(api, linkService(), UserRepo(xa), sleeper)

    runAsync(poller.run(stop))

    assertEquals(sleeper.sleeps.toList, List(Duration.ofSeconds(30)))

  test("closing the stop channel ends the loop"):
    val stop = UnboundedChannel[Unit]()
    stop.close()
    val api = ScriptedApi(mutable.Queue.empty, () => ())
    val poller =
      TelegramLinkPoller(api, linkService(), UserRepo(xa), RecordingSleeper())

    runAsync(poller.run(stop))

    // The first race may still observe the already-completed poll; what
    // matters is that the loop returns instead of polling forever.
    assert(
      api.offsets.size <= 1,
      s"expected at most one poll, got ${api.offsets.toList}"
    )
