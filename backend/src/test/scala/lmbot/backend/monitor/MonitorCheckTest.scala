package lmbot.backend.monitor

import scala.collection.mutable

import gears.async.Async
import lmbot.backend.db.{MonitorEventRepo, MonitorRow}
import lmbot.backend.notify.{NotificationChannel, NotificationError}
import lmbot.shared.domain.FoundSlot

class MonitorCheckTest extends MonitorFixtures:

  final private class ScriptedSearch(
      results: mutable.Queue[Either[CheckFailure, List[FoundSlot]]]
  ) extends SlotSearch:
    val searched = mutable.ListBuffer.empty[Long]
    def search(monitor: MonitorRow)(using
        Async
    ): Either[CheckFailure, List[FoundSlot]] =
      searched += monitor.id
      if results.isEmpty then Right(Nil) else results.dequeue()

  private def check(
      search: SlotSearch,
      channel: RecordingChannel
  ): MonitorCheck =
    MonitorCheck(
      search,
      MonitorEventRepo(xa),
      notifier(Some(channel)),
      () => fixedNow
    )

  test("slots outside the criteria are filtered before dedup"):
    val ownerId = anOwner(chatId = Some(555L))
    val monitor = aMonitor(anAccount(ownerId))
    val channel = RecordingChannel()
    val search = ScriptedSearch(
      mutable.Queue(
        Right(
          List(
            aSlot(), // Monday, in the window, selected clinic and doctor
            aSlot(from = "2026-08-11T09:00"), // Tuesday
            aSlot(clinicId = 99L) // unselected clinic
          )
        )
      )
    )

    val result = runAsync(check(search, channel).run(monitor, ownerId))

    assertEquals(result, CheckResult.Succeeded(slotsFound = 1, newSlots = 1))
    assertEquals(channel.sent.size, 1)
    // Same-timestamp events come back newest id first.
    assertEquals(events(monitor.id), Seq("notification_sent", "slot_found"))

  test("a slot is recorded and notified exactly once"):
    val ownerId = anOwner(chatId = Some(555L))
    val monitor = aMonitor(anAccount(ownerId))
    val channel = RecordingChannel()
    val search = ScriptedSearch(
      mutable.Queue(
        Right(List(aSlot())),
        Right(List(aSlot()))
      )
    )
    val subject = check(search, channel)

    assertEquals(
      runAsync(subject.run(monitor, ownerId)),
      CheckResult.Succeeded(1, 1)
    )
    assertEquals(
      runAsync(subject.run(monitor, ownerId)),
      CheckResult.Succeeded(1, 0)
    )

    assertEquals(channel.sent.size, 1)
    // Same-timestamp events come back newest id first.
    assertEquals(events(monitor.id), Seq("notification_sent", "slot_found"))

  test("a search failure propagates without writing events"):
    val ownerId = anOwner(chatId = Some(555L))
    val monitor = aMonitor(anAccount(ownerId))
    val channel = RecordingChannel()
    val search = ScriptedSearch(
      mutable.Queue(Left(CheckFailure.Transient("boom")))
    )

    val result = runAsync(check(search, channel).run(monitor, ownerId))

    assertEquals(result, CheckResult.Failed(CheckFailure.Transient("boom")))
    assertEquals(channel.sent.toList, Nil)
    assertEquals(events(monitor.id), Nil)

  test("the result counts every matching slot and only the new ones"):
    val ownerId = anOwner(chatId = Some(555L))
    val monitor = aMonitor(anAccount(ownerId))
    val channel = RecordingChannel()
    val search = ScriptedSearch(
      mutable.Queue(
        Right(List(aSlot(), aSlot(from = "2026-08-12T10:00"))),
        Right(List(aSlot(), aSlot(from = "2026-08-12T10:00")))
      )
    )
    val subject = check(search, channel)

    assertEquals(
      runAsync(subject.run(monitor, ownerId)),
      CheckResult.Succeeded(slotsFound = 2, newSlots = 2)
    )
    assertEquals(
      runAsync(subject.run(monitor, ownerId)),
      CheckResult.Succeeded(slotsFound = 2, newSlots = 0)
    )
    assertEquals(channel.sent.size, 2)

  // -- Delivery retries (Plan 5 review) --

  final private class FlakyChannel(var failures: Int)
      extends NotificationChannel:
    val sent = mutable.ListBuffer.empty[(Long, String)]
    def send(chatId: Long, text: String)(using
        Async
    ): Either[NotificationError, Unit] =
      sent += ((chatId, text))
      if failures > 0 then
        failures -= 1
        Left(NotificationError.Transient("down"))
      else Right(())

  private def retryingCheck(
      search: SlotSearch,
      channel: FlakyChannel
  ): MonitorCheck =
    MonitorCheck(
      search,
      MonitorEventRepo(xa),
      notifier(Some(channel)),
      () => fixedNow
    )

  test("a failed delivery is retried on the next check"):
    val ownerId = anOwner(chatId = Some(555L))
    val monitor = aMonitor(anAccount(ownerId))
    val channel = FlakyChannel(failures = 1)
    val search = ScriptedSearch(
      mutable.Queue(Right(List(aSlot())), Right(List(aSlot())))
    )
    val subject = retryingCheck(search, channel)

    runAsync(subject.run(monitor, ownerId))
    assert(
      events(monitor.id).contains("notification_failed"),
      s"expected a failure to record: ${events(monitor.id)}"
    )

    runAsync(subject.run(monitor, ownerId))
    assert(
      events(monitor.id).contains("notification_sent"),
      s"expected the retry to succeed: ${events(monitor.id)}"
    )
    assertEquals(channel.sent.size, 2)

  test("a delivery is retried at most three times"):
    val ownerId = anOwner(chatId = Some(555L))
    val monitor = aMonitor(anAccount(ownerId))
    val channel = FlakyChannel(failures = Int.MaxValue)
    val search = ScriptedSearch(
      mutable.Queue(
        Right(List(aSlot())),
        Right(List(aSlot())),
        Right(List(aSlot())),
        Right(List(aSlot()))
      )
    )
    val subject = retryingCheck(search, channel)

    (1 to 4).foreach(_ => runAsync(subject.run(monitor, ownerId)))

    assertEquals(
      channel.sent.size,
      MonitorCheck.maxDeliveryAttempts,
      "after the cap a failed delivery must stand"
    )
