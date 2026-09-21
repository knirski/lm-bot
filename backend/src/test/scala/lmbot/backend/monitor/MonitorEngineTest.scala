package lmbot.backend.monitor

import java.time.{Duration, LocalDate, OffsetDateTime}
import java.util.concurrent.CancellationException

import scala.collection.mutable
import scala.concurrent.duration.*

import gears.async.{Async, UnboundedChannel}
import lmbot.backend.db.{AccountRepo, MonitorEventRepo, MonitorRepo, MonitorRow}
import lmbot.backend.support.Sleeper
import lmbot.shared.domain.{AccountId, FoundSlot, MonitorId, Role}

class MonitorEngineTest extends MonitorFixtures:

  final private class ScriptedSearch(
      results: mutable.Queue[Either[CheckFailure, List[FoundSlot]]]
  ) extends SlotSearch:
    val searched = mutable.ListBuffer.empty[Long]
    def search(monitor: MonitorRow)(using
        Async
    ): Either[CheckFailure, List[FoundSlot]] =
      searched += monitor.id
      if results.isEmpty then Right(Nil) else results.dequeue()

  private class ImmediateSleeper(onSleep: Int => Unit = _ => ())
      extends Sleeper:
    var count = 0
    def sleep(duration: Duration)(using Async): Unit =
      if summon[Async].group.isCancelled then throw new CancellationException()
      count += 1
      onSleep(count)

  private def engine(
      search: SlotSearch,
      channel: Option[RecordingChannel],
      sleeper: Sleeper = ImmediateSleeper(),
      monitors: MonitorRepo = MonitorRepo(xa)
  ): MonitorEngine =
    val events = MonitorEventRepo(xa)
    val notifications = notifier(channel)
    val health = AccountHealth(
      AccountRepo(xa),
      monitors,
      events,
      notifications,
      () => fixedNow
    )
    MonitorEngine(
      monitors,
      AccountRepo(xa),
      MonitorCheck(search, events, notifications, () => fixedNow),
      notifications,
      health,
      events,
      sleeper,
      () => 0.5,
      () => fixedNow
    )

  private def storedMonitor(id: Long): MonitorRow =
    MonitorRepo(xa).findById(MonitorId(id)).get

  test("a successful check records a summary and continues"):
    val ownerId = anOwner()
    val monitor = aMonitor(anAccount(ownerId))
    val search = ScriptedSearch(mutable.Queue(Right(Nil)))

    val iteration =
      runAsync(engine(search, None).iterate(monitor, ownerId, 0, 0))

    assertEquals(iteration, Iteration.Continue(10.minutes, 0, 0))
    assertEquals(
      storedMonitor(monitor.id).lastCheckSummary,
      Some("No new slots")
    )

  test("new slots are summarised"):
    val ownerId = anOwner()
    val monitor = aMonitor(anAccount(ownerId))
    val search = ScriptedSearch(mutable.Queue(Right(List(aSlot()))))

    val iteration =
      runAsync(engine(search, None).iterate(monitor, ownerId, 0, 0))

    assertEquals(iteration, Iteration.Continue(10.minutes, 0, 0))
    assertEquals(
      storedMonitor(monitor.id).lastCheckSummary,
      Some("Found 1 new slot of 1")
    )

  test("a monitor past its date range completes, notifies, and stops"):
    val ownerId = anOwner(chatId = Some(555L))
    val monitor = aMonitor(
      anAccount(ownerId),
      dateFrom = LocalDate.parse("2026-07-01"),
      dateTo = LocalDate.parse("2026-08-01")
    )
    val channel = RecordingChannel()
    val search = ScriptedSearch(mutable.Queue.empty)

    val iteration =
      runAsync(
        engine(search, Some(channel)).iterate(monitor, ownerId, 0, 0)
      )

    assertEquals(iteration, Iteration.Stop)
    assertEquals(storedMonitor(monitor.id).state, "completed")
    assertEquals(events(monitor.id), Seq("monitor_completed"))
    assertEquals(channel.sent.size, 1)
    assert(channel.sent.head._2.contains("completed"))
    assertEquals(search.searched.toList, Nil)

  test("auth rejection pauses the account and notifies once"):
    val ownerId = anOwner(chatId = Some(555L))
    val accountId = anAccount(ownerId)
    val first = aMonitor(accountId)
    val second = aMonitor(accountId)
    val channel = RecordingChannel()
    val search = ScriptedSearch(
      mutable.Queue(
        Left(CheckFailure.AuthRejected),
        Left(CheckFailure.AuthRejected)
      )
    )
    val subject = engine(search, Some(channel))

    assertEquals(
      runAsync(subject.iterate(first, ownerId, 0, 0)),
      Iteration.Stop
    )
    assertEquals(
      AccountRepo(xa).findById(AccountId(accountId)).map(_.status),
      Some("auth_failed")
    )
    assertEquals(storedMonitor(first.id).state, "paused")
    assertEquals(storedMonitor(second.id).state, "paused")
    assertEquals(events(first.id), Seq("monitor_paused"))
    assertEquals(channel.sent.size, 1)

    // A second monitor failing in the same episode does not notify again.
    assertEquals(
      runAsync(subject.iterate(second, ownerId, 0, 0)),
      Iteration.Stop
    )
    assertEquals(channel.sent.size, 1)

  test("three persistent failures fail the monitor and notify once"):
    val ownerId = anOwner(chatId = Some(555L))
    val monitor = aMonitor(anAccount(ownerId))
    val channel = RecordingChannel()
    val search = ScriptedSearch(
      mutable.Queue(
        Left(CheckFailure.Persistent("bad payload")),
        Left(CheckFailure.Persistent("bad payload")),
        Left(CheckFailure.Persistent("bad payload"))
      )
    )
    val subject = engine(search, Some(channel))

    assertEquals(
      runAsync(subject.iterate(monitor, ownerId, 0, 0)),
      Iteration.Continue(1.minute, 1, 1)
    )
    assertEquals(
      runAsync(subject.iterate(monitor, ownerId, 1, 1)),
      Iteration.Continue(2.minutes, 2, 2)
    )
    assertEquals(
      runAsync(subject.iterate(monitor, ownerId, 2, 2)),
      Iteration.Stop
    )

    assertEquals(storedMonitor(monitor.id).state, "failed")
    assertEquals(events(monitor.id), Seq("monitor_failed"))
    assertEquals(channel.sent.size, 1)
    assert(channel.sent.head._2.contains("bad payload"))

  test("a transient failure retries without failing the monitor"):
    val ownerId = anOwner(chatId = Some(555L))
    val monitor = aMonitor(anAccount(ownerId))
    val search = ScriptedSearch(
      mutable.Queue(Left(CheckFailure.Transient("boom")))
    )

    val iteration =
      runAsync(engine(search, None).iterate(monitor, ownerId, 0, 0))

    assertEquals(iteration, Iteration.Continue(1.minute, 0, 1))
    assertEquals(storedMonitor(monitor.id).state, "active")
    assertEquals(
      storedMonitor(monitor.id).lastCheckSummary,
      Some("Check failed: boom")
    )

  test("version rejection notifies the admin once and keeps retrying"):
    anOwner(chatId = Some(111L), role = Role.Admin)
    val ownerId = anOwner(chatId = Some(555L))
    val monitor = aMonitor(anAccount(ownerId))
    val channel = RecordingChannel()
    val search = ScriptedSearch(
      mutable.Queue(
        Left(CheckFailure.VersionRejected),
        Left(CheckFailure.VersionRejected)
      )
    )
    val subject = engine(search, Some(channel))

    assertEquals(
      runAsync(subject.iterate(monitor, ownerId, 0, 0)),
      Iteration.Continue(1.minute, 0, 1)
    )
    assertEquals(
      runAsync(subject.iterate(monitor, ownerId, 0, 1)),
      Iteration.Continue(2.minutes, 0, 2)
    )

    assertEquals(channel.sent.map(_._1).toList, List(111L))
    assertEquals(storedMonitor(monitor.id).state, "active")

  test("run reconciles, picks up new monitors, and stops"):
    val ownerId = anOwner()
    val accountId = anAccount(ownerId)
    val first = aMonitor(accountId)
    val stop = UnboundedChannel[Unit]()
    var second: Option[MonitorRow] = None
    val sleeper = new ImmediateSleeper:
      override def sleep(duration: Duration)(using Async): Unit =
        super.sleep(duration)
        if count == 1 && second.isEmpty then second = Some(aMonitor(accountId))
        if count >= 4 then stop.close()
    val search = ScriptedSearch(mutable.Queue.empty)

    runAsync(engine(search, None, sleeper).run(stop))

    assert(
      search.searched.contains(first.id),
      s"first monitor was never checked: ${search.searched.toList}"
    )
    assert(
      second.exists(m => search.searched.contains(m.id)),
      s"newly created monitor was never picked up: ${search.searched.toList}"
    )

  test("a crashing check is a counted persistent failure, not a dead fiber"):
    val ownerId = anOwner()
    val monitor = aMonitor(anAccount(ownerId))
    val search = new SlotSearch:
      def search(monitor: MonitorRow)(using
          Async
      ): Either[CheckFailure, List[FoundSlot]] =
        throw IllegalStateException("boom")
    val subject = engine(search, Some(RecordingChannel()))

    assertEquals(
      runAsync(subject.iterate(monitor, ownerId, 0, 0)),
      Iteration.Continue(1.minute, 1, 1)
    )

    assertEquals(storedMonitor(monitor.id).state, "active")
    assertEquals(
      storedMonitor(monitor.id).lastCheckSummary,
      Some("Check failed: The check crashed: IllegalStateException")
    )

  test("a loop that dies outside the check guard is restarted"):
    val ownerId = anOwner()
    val accountId = anAccount(ownerId)
    aMonitor(accountId)
    var recordChecks = 0
    // A database blip in `recordCheck` happens outside `guardedCheck`, so the
    // loop dies; reconciliation must notice and start a fresh one.
    val flaky = new MonitorRepo(xa):
      override def recordCheck(
          id: MonitorId,
          at: OffsetDateTime,
          summary: String
      ): Unit =
        recordChecks += 1
        if recordChecks == 1 then throw IllegalStateException("db blip")
        super.recordCheck(id, at, summary)
    val stop = UnboundedChannel[Unit]()
    val search = ScriptedSearch(mutable.Queue.empty)
    val sleeper = new Sleeper:
      private var sleeps = 0
      def sleep(duration: Duration)(using Async): Unit =
        if summon[Async].group.isCancelled then
          throw new CancellationException()
        sleeps += 1
        if search.searched.size >= 2 || sleeps >= 200 then stop.close()

    runAsync(engine(search, None, sleeper, flaky).run(stop))

    assert(
      recordChecks >= 2,
      s"the loop was not restarted after the crash: $recordChecks recordCheck calls"
    )
    assert(
      search.searched.size >= 2,
      s"the restarted loop never searched: ${search.searched.toList}"
    )
