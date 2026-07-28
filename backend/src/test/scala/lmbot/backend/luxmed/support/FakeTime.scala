package lmbot.backend.luxmed.support

import gears.async.Async
import lmbot.backend.luxmed.Sleeper
import scala.concurrent.duration.FiniteDuration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** A deterministic fake clock and sleeper for testing rate limiting and spacing
  * logic. Advances time manually.
  */
final class FakeTime:

  private var current = Instant.parse("2026-08-03T12:00:00Z")
  private val recordedSleeps = new ConcurrentLinkedQueue[FiniteDuration]()

  def now(): Instant = current

  def advance(duration: FiniteDuration): Unit =
    current = current.plusNanos(duration.toNanos)

  def set(time: Instant): Unit =
    current = time

  def sleeps: List[FiniteDuration] = recordedSleeps.asScala.toList

  val sleeper: Sleeper = new Sleeper:
    def sleep(duration: FiniteDuration)(using Async): Unit =
      recordedSleeps.add(duration)
      advance(duration)
