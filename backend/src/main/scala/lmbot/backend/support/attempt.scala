package lmbot.backend.support

import scala.util.boundary
import scala.util.control.NonFatal

/** Runs a block that may throw at a foreign boundary (JDBC, JCA) and converts
  * the failure into a domain error. Never catches `Break` (which would silently
  * discard `result` control flow) and never catches `InterruptedException` (a
  * cancellation signal, not a domain failure).
  */
object attempt:
  inline def apply[E, A](onFailure: Throwable => E)(
      inline block: => A
  ): Either[E, A] =
    try Right(block)
    catch
      case escape: boundary.Break[?]       => throw escape
      case interrupt: InterruptedException => throw interrupt
      case NonFatal(error)                 => Left(onFailure(error))

  /** As `apply`, for a block that already returns `Either`. */
  inline def either[E, A](onFailure: Throwable => E)(
      inline block: => Either[E, A]
  ): Either[E, A] = apply(onFailure)(block).flatten
