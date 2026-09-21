package lmbot.backend.monitor

import java.time.{OffsetDateTime, ZoneId}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CancellationException, ConcurrentHashMap}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.*
import scala.util.Failure
import scala.util.control.NonFatal

import gears.async.{Async, Future, Listener, ReadableChannel}
import lmbot.backend.account.{AccountHealthReporter, AccountStatusReason}
import lmbot.backend.db.{AccountRepo, MonitorEventRepo, MonitorRepo, MonitorRow}
import lmbot.backend.notify.NotificationService
import lmbot.backend.support.Sleeper
import lmbot.shared.domain.{
  AccountId,
  MonitorEventKind,
  MonitorId,
  MonitorState,
  UserId
}
import org.slf4j.LoggerFactory

/** The Gears supervisor: reconciles the active set from Postgres and runs one
  * cancellable fiber per active monitor (spec §5.5).
  *
  * Reconciliation is what makes restart and live edits work: the database is
  * the source of truth, so a monitor created or resumed after startup gets a
  * loop at the next pass, and a paused or deleted monitor's loop is cancelled.
  * When `run` returns, every loop it started is cancelled and awaited.
  */
final class MonitorEngine(
    monitors: MonitorRepo,
    accounts: AccountRepo,
    checks: MonitorCheck,
    notifier: NotificationService,
    health: AccountHealthReporter,
    events: MonitorEventRepo,
    sleeper: Sleeper,
    jitter: () => Double,
    now: () => OffsetDateTime,
    warsaw: ZoneId = ZoneId.of("Europe/Warsaw"),
    reconcileInterval: FiniteDuration = 15.seconds
):
  private val log = LoggerFactory.getLogger(getClass)
  private val versionNotified = new AtomicBoolean(false)

  def run(stop: ReadableChannel[Unit])(using Async.Spawn): Unit =
    Async.spawning.use: engine =>
      val loops = ConcurrentHashMap[MonitorId, Future[Unit]]()
      val reconciler =
        Future(reconcileLoop(loops, engine))(using engine, engine)
      stop.read()
      reconciler.cancel()

  private def reconcileLoop(
      loops: ConcurrentHashMap[MonitorId, Future[Unit]],
      engine: Async.Spawn
  )(using Async.Spawn): Unit =
    while true do
      reconcile(loops, engine)
      sleeper.sleep(reconcileInterval.toJava)

  private def reconcile(
      loops: ConcurrentHashMap[MonitorId, Future[Unit]],
      engine: Async.Spawn
  ): Unit =
    val active = monitors.listActive().map(row => MonitorId(row.id)).toSet
    loops
      .keySet()
      .asScala
      .filterNot(active)
      .toList
      .foreach: id =>
        Option(loops.remove(id)).foreach(_.cancel())
    active
      .filterNot(id => loops.containsKey(id))
      .foreach: id =>
        spawnLoop(id, loops, engine)

  /** Spawns one monitor loop and removes its entry when the loop ends — whether
    * it returned or died. Removing on completion is what lets reconciliation
    * restart a monitor whose loop crashed outside the per-check guard (a
    * database blip in `recordCheck`, say) instead of leaving it silently dead
    * while its row still says `active`.
    */
  private def spawnLoop(
      id: MonitorId,
      loops: ConcurrentHashMap[MonitorId, Future[Unit]],
      engine: Async.Spawn
  ): Unit =
    val loop = Future(monitorLoop(id))(using engine, engine)
    // Put first, then listen: `onComplete` fires immediately for an already
    // completed source, so an instantly-finished loop cannot linger in the map.
    loops.put(id, loop)
    loop.onComplete(Listener { (result, _) =>
      loops.remove(id)
      result match
        case Failure(_: CancellationException) => ()
        case Failure(error)                    =>
          log.warn(
            s"Monitor ${id.value} loop died; reconciliation will restart it",
            error
          )
        case _ => ()
    })

  private def monitorLoop(monitorId: MonitorId)(using Async.Spawn): Unit =
    var persistentFailures = 0
    var consecutiveFailures = 0
    var running = true
    while running do
      monitors.findById(monitorId) match
        case None => running = false
        case Some(monitor) if monitor.state != MonitorState.Active.wireName =>
          running = false
        case Some(monitor) =>
          accounts.findById(AccountId(monitor.luxmedAccountId)) match
            case None          => running = false
            case Some(account) =>
              val ownerId = UserId(account.ownerUserId)
              iterate(
                monitor,
                ownerId,
                persistentFailures,
                consecutiveFailures
              ) match
                case Iteration.Stop => running = false
                case Iteration.Continue(sleepFor, persistent, consecutive) =>
                  persistentFailures = persistent
                  consecutiveFailures = consecutive
                  sleeper.sleep(sleepFor.toJava)

  private[monitor] def iterate(
      monitor: MonitorRow,
      ownerId: UserId,
      persistentFailures: Int,
      consecutiveFailures: Int
  )(using Async): Iteration =
    if isPastDateRange(monitor) then
      complete(monitor, ownerId)
      Iteration.Stop
    else
      guardedCheck(monitor, ownerId) match
        case CheckResult.Succeeded(slotsFound, newSlots) =>
          recordCheck(monitor, successSummary(slotsFound, newSlots))
          Iteration.Continue(
            EnginePolicy.successSleep(monitor.intervalMinutes, jitter()),
            0,
            0
          )
        case CheckResult.Failed(failure) =>
          EnginePolicy.onFailure(
            failure,
            persistentFailures,
            consecutiveFailures
          ) match
            case FailureAction.Retry(sleepFor, persistent, consecutive) =>
              recordCheck(monitor, failureSummary(failure))
              Iteration.Continue(sleepFor, persistent, consecutive)
            case FailureAction.FailMonitor =>
              fail(monitor, ownerId, failure)
              Iteration.Stop
            case FailureAction.PauseAccount(reason) =>
              health.reportAuthFailure(
                AccountId(monitor.luxmedAccountId),
                reason
              )
              Iteration.Stop
            case FailureAction.NotifyAdmin(sleepFor) =>
              if versionNotified.compareAndSet(false, true) then
                notifier.notifyAdminsVersionRejected(failureDetail(failure))
              recordCheck(monitor, failureSummary(failure))
              Iteration.Continue(
                sleepFor,
                persistentFailures,
                consecutiveFailures + 1
              )

  /** A crashing check is one counted persistent failure, not a dead fiber (spec
    * §5.5); cancellation is rethrown untouched.
    */
  private def guardedCheck(monitor: MonitorRow, ownerId: UserId)(using
      Async
  ): CheckResult =
    try checks.run(monitor, ownerId)
    catch
      case cancelled: CancellationException => throw cancelled
      case NonFatal(error)                  =>
        CheckResult.Failed(
          CheckFailure.Persistent(
            s"The check crashed: ${error.getClass.getSimpleName}"
          )
        )

  private def complete(monitor: MonitorRow, ownerId: UserId)(using
      Async
  ): Unit =
    monitors.complete(MonitorId(monitor.id)) match
      case Some(completed) =>
        log.info(
          s"Monitor ${monitor.id} completed: its date range has passed"
        )
        events.append(
          MonitorId(monitor.id),
          MonitorEventKind.MonitorCompleted,
          None,
          Some("The date range has passed."),
          now()
        )
        notifier.notifyMonitorCompleted(ownerId, completed)
      case None => ()

  private def fail(
      monitor: MonitorRow,
      ownerId: UserId,
      failure: CheckFailure
  )(using Async): Unit =
    val detail = failureDetail(failure)
    monitors.fail(MonitorId(monitor.id)) match
      case Some(failed) =>
        log.warn(s"Monitor ${monitor.id} failed: $detail")
        events.append(
          MonitorId(monitor.id),
          MonitorEventKind.MonitorFailed,
          None,
          Some(detail),
          now()
        )
        notifier.notifyMonitorFailed(ownerId, failed, detail)
      case None => ()

  private def recordCheck(monitor: MonitorRow, summary: String): Unit =
    monitors.recordCheck(MonitorId(monitor.id), now(), summary)

  private def isPastDateRange(monitor: MonitorRow): Boolean =
    monitor.dateTo.toLocalDate.isBefore(
      now().atZoneSameInstant(warsaw).toLocalDate
    )

  private def successSummary(slotsFound: Int, newSlots: Int): String =
    if newSlots == 0 then "No new slots"
    else if newSlots == 1 then s"Found 1 new slot of $slotsFound"
    else s"Found $newSlots new slots of $slotsFound"

  private def failureSummary(failure: CheckFailure): String =
    s"Check failed: ${failureDetail(failure)}"

  private def failureDetail(failure: CheckFailure): String = failure match
    case CheckFailure.AuthRejected =>
      AccountStatusReason.AuthFailed.value
    case CheckFailure.Challenge =>
      AccountStatusReason.Challenge.value
    case CheckFailure.RateLimited =>
      AccountStatusReason.RateLimited.value
    case CheckFailure.VersionRejected =>
      AccountStatusReason.VersionRejected.value
    case CheckFailure.Transient(detail)  => detail
    case CheckFailure.Persistent(detail) => detail
