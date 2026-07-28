package lmbot.backend.luxmed

import gears.async.Async
import scala.concurrent.duration.FiniteDuration
import java.time.Instant
import java.util.concurrent.Semaphore
import scala.annotation.nowarn

/** A pacing capability that the `AccountGate` hands out to the body of
  * `serialized`. Only `AccountGate` can construct an instance.
  */
final class AccountGatePermit private[luxmed] (
    gate: AccountGate
) extends RequestPermit:

  private var lastRequestAt: Option[Instant] = None

  def beforeRequest()(using Async): Unit =
    val now = gate.now()
    val waitMs = lastRequestAt match
      case Some(last) =>
        val elapsed = java.time.Duration.between(last, now).toMillis
        Math.max(0, gate.minimumSpacing.toMillis - elapsed)
      case None => 0L
    if waitMs > 0 then
      gate.sleeper.sleep(
        FiniteDuration(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      )
    lastRequestAt = Some(gate.now())

/** Serializes access to a single Luxmed account and paces every HTTP request.
  */
final class AccountGate(
    val minimumSpacing: FiniteDuration,
    val now: () => Instant = () => Instant.now(),
    val sleeper: Sleeper = Sleeper.Default
):

  private val semaphore = new Semaphore(1)

  @nowarn("msg=unused implicit parameter")
  def serialized[A](body: AccountGatePermit ?=> A)(using Async): A =
    semaphore.acquire()
    try body(using AccountGatePermit(this))
    finally semaphore.release()

/** An injectable sleeper for deterministic testing.
  */
trait Sleeper:
  def sleep(duration: FiniteDuration)(using Async): Unit

object Sleeper:
  object Default extends Sleeper:
    def sleep(duration: FiniteDuration)(using Async): Unit =
      Thread.sleep(duration.toMillis)
