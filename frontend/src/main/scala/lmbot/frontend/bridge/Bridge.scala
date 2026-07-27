package lmbot.frontend.bridge

import gears.async.ScalaConverters.asGears
import gears.async.Async

import scala.concurrent.{ExecutionContext, Future as StdFuture}

/** The single adapter between foreign async APIs and Gears (spec §5.7.1).
  *
  * Nothing outside this package may mention `scala.concurrent.Future`: sttp's
  * Scala.js backend returns one, and this is where that fact stops.  Every
  * layer above speaks Gears `Async`.
  *
  * == Linker note ==
  * Bridge.await uses `f.asGears.awaitResult` which converts a
  * `scala.concurrent.Future` to a Gears `Future` and awaits it.  The Gears
  * `Future.awaitResult` call is where the linker traces into JSPI internals
  * (`JsAsync.await` → `js.await`).  This is only reachable from @main through
  * ApiClient → Bridge, which is why the setTimeout deferral in Main.scala is
  * necessary — it breaks the linker trace before it reaches this file.
  */
object Bridge:

  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  def await[T](f: => StdFuture[T])(using Async): Either[Throwable, T] =
    f.asGears.awaitResult.toEither
