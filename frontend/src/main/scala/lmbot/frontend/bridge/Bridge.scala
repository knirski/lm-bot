package lmbot.frontend.bridge

import gears.async.{Async, ScalaConverters}
import scala.concurrent.{ExecutionContext, Future as StdFuture}

/** The single adapter between foreign async APIs and Gears (spec §5.7.1).
  *
  * Nothing outside this package may mention `scala.concurrent.Future`: sttp's
  * Scala.js backend returns one, and this is where that fact stops.
  */
object Bridge:

  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  /** Awaits a foreign Future as a value. Failures come back as `Left` rather
    * than as thrown exceptions, because a failed network call is an expected
    * outcome, not a bug (spec §7).
    */
  def await[T](f: => StdFuture[T])(using Async): Either[Throwable, T] =
    ScalaConverters.asGears(f).awaitResult.toEither
