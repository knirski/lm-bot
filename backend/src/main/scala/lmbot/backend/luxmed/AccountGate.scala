package lmbot.backend.luxmed

import java.time.{Duration, Instant}

import gears.async.{Async, JvmAsyncOperations, Semaphore}

/** A pacing capability that the `AccountGate` hands out to the body of
  * `serialized`. Only `AccountGate` can construct an instance.
  */
final class AccountGatePermit private[luxmed] (
    gate: AccountGate
) extends RequestPermit:

  def beforeRequest()(using Async): Unit =
    val now = gate.now()
    val waitMs = gate.remainingWaitMillis(now)
    if waitMs > 0 then gate.sleeper.sleep(Duration.ofMillis(waitMs))
    gate.recordRequestAt(gate.now())

/** Serializes access to a single Luxmed account and paces every HTTP request.
  */
final class AccountGate(
    val minimumSpacing: Duration,
    val now: () => Instant = () => Instant.now(),
    val sleeper: Sleeper = Sleeper.Default
):

  private val semaphore = Semaphore(1)
  private var lastRequestAt: Option[Instant] = None

  def serialized[A](body: AccountGatePermit ?=> A)(using Async): A =
    val guard = semaphore.awaitResult
    try body(using AccountGatePermit(this))
    finally guard.release()

  private[luxmed] def remainingWaitMillis(now: Instant): Long =
    lastRequestAt match
      case Some(last) =>
        val elapsed = Duration.between(last, now).toMillis
        Math.max(0L, minimumSpacing.toMillis - elapsed)
      case None => 0L

  private[luxmed] def recordRequestAt(at: Instant): Unit =
    lastRequestAt = Some(at)

/** An injectable sleeper for deterministic testing.
  */
trait Sleeper:
  def sleep(duration: Duration)(using Async): Unit

object Sleeper:
  object Default extends Sleeper:
    def sleep(duration: Duration)(using Async): Unit =
      JvmAsyncOperations.sleep(duration.toMillis)
