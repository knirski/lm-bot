package lmbot.frontend.bridge

import gears.async.ScalaConverters.asGears
import gears.async.Async

import scala.concurrent.{ExecutionContext, Future as StdFuture}

/** The single adapter between foreign async APIs and Gears (spec §5.7.1).
  *
  * Nothing outside this package may mention `scala.concurrent.Future`: sttp's
  * Scala.js backend returns one, and this is where that fact stops. Every layer
  * above speaks Gears `Async`.
  */
object Bridge:

  private given ExecutionContext =
    scala.scalajs.concurrent.JSExecutionContext.queue

  /** Awaits a foreign Future as a value. Failures come back as `Left` rather
    * than thrown, because a failed network call is an expected outcome, not a
    * bug (spec §7).
    */
  def await[T](f: => StdFuture[T])(using Async): Either[Throwable, T] =
    f.asGears.awaitResult.toEither

  /** Awaits a foreign Future that already carries a domain error, flattening a
    * transport or decode failure into the same error channel.
    *
    * This lives here rather than in `ApiClient` so that no application
    * signature outside this package names `scala.concurrent.Future` (spec
    * §5.7.1) — `ApiClient` would otherwise have to spell the type to describe
    * what it is awaiting.
    */
  def awaitEither[E, T](
      f: => StdFuture[Either[E, T]]
  )(onFailure: Throwable => E)(using
      Async
  ): Either[E, T] =
    await(f) match
      case Right(result) => result
      case Left(err)     => Left(onFailure(err))
