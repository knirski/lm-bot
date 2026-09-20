package lmbot.backend.support

import java.time.Duration

import gears.async.{Async, JvmAsyncOperations}

/** An injectable sleeper for deterministic testing of pacing and retry loops.
  *
  * Lived in the Luxmed package until the monitor engine and Telegram poller
  * needed it too; it is not Luxmed-specific.
  */
trait Sleeper:
  def sleep(duration: Duration)(using Async): Unit

object Sleeper:
  object Default extends Sleeper:
    def sleep(duration: Duration)(using Async): Unit =
      JvmAsyncOperations.sleep(duration.toMillis)
