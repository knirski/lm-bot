package lmbot.backend.support

import scala.util.boundary
import scala.util.boundary.{Label, break}

/** Direct-style short-circuiting over `Either`.
  *
  * Inside a `result` block, `expr.?` yields the `Right` value or abandons the
  * whole block with the `Left`. This is the direct-style equivalent of a
  * for-comprehension, without forcing every step into a single chain.
  */
object result:
  inline def apply[E, A](inline body: Label[Either[E, A]] ?=> A): Either[E, A] =
    boundary(Right(body))

  extension [E, A](self: Either[E, A])
    /** Unwrap, or abandon the enclosing `result` block with this error. */
    def ?[B](using Label[Either[E, B]]): A = self match
      case Right(value) => value
      case Left(error)  => break(Left(error))
