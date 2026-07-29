package lmbot.backend.luxmed.support

import gears.async.Async
import lmbot.backend.luxmed.Sleeper
import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

/** A deterministic fake clock and sleeper for testing rate limiting and spacing
  * logic. Advances time manually.
  */
final class FakeTime:

  private val current =
    AtomicReference(Instant.parse("2026-08-03T12:00:00Z"))
  private val recordedSleeps = new ConcurrentLinkedQueue[Duration]()

  def now(): Instant = current.get()

  def advance(duration: Duration): Unit =
    current.updateAndGet(_.plusNanos(duration.toNanos))

  def set(time: Instant): Unit =
    current.set(time)

  def sleeps: List[Duration] = recordedSleeps.asScala.toList

  val sleeper: Sleeper = new Sleeper:
    def sleep(duration: Duration)(using Async): Unit =
      recordedSleeps.add(duration)
      advance(duration)
