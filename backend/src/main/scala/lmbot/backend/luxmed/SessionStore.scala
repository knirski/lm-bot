package lmbot.backend.luxmed

import java.util.concurrent.atomic.AtomicReference

import lmbot.backend.config.Secret
import lmbot.backend.luxmed.model.LuxmedSession

/** Errors that can arise from session store operations.
  */
enum SessionStoreError:
  case Unavailable(message: String)
  case ConcurrentModification

/** A per-account session store with atomic compare-and-set semantics.
  *
  * `replace` is an atomic compare-and-set operation: it writes the updated
  * session only when the stored refresh token still matches the expected value.
  * This prevents a race condition where two concurrent refresh sequences could
  * lose one rotated refresh token.
  *
  * Plan 3 supplies an in-memory implementation. Plan 4 supplies an encrypted
  * PostgreSQL implementation with restart round-trip coverage.
  */
trait SessionStore:
  /** Load the current session, if one exists. */
  def load(): Either[SessionStoreError, Option[LuxmedSession]]

  /** Atomically replace the session if its refresh token matches.
    *
    * @param expectedRefreshToken
    *   `None` means no session may exist (initial login). `Some(token)` means
    *   the stored refresh token must still match.
    * @param updatedSession
    *   The session to store.
    *
    * @return
    *   `Right(())` on success, `Left(ConcurrentModification)` if the expected
    *   token does not match the stored one.
    */
  def replace(
      expectedRefreshToken: Option[Secret],
      updatedSession: LuxmedSession
  ): Either[SessionStoreError, Unit]

  /** Clear the stored session. */
  def clear(): Either[SessionStoreError, Unit]

/** In-memory session store using a CAS loop.
  *
  * Uses `AtomicReference` for thread safety. The compare-and-set in `replace`
  * checks that the current stored session's refresh token matches the caller's
  * expectation before overwriting.
  */
final class InMemorySessionStore extends SessionStore:

  private val ref = AtomicReference[Option[LuxmedSession]](None)

  def load(): Either[SessionStoreError, Option[LuxmedSession]] =
    Right(ref.get())

  def replace(
      expectedRefreshToken: Option[Secret],
      updatedSession: LuxmedSession
  ): Either[SessionStoreError, Unit] =
    @scala.annotation.tailrec
    def loop(): Either[SessionStoreError, Unit] =
      val current = ref.get()
      val currentRefresh = current.map(_.refreshToken)
      val matches = (expectedRefreshToken, currentRefresh) match
        case (None, None) => true
        case (Some(expected), Some(actual)) if expected.value == actual.value =>
          true
        case _ => false
      if !matches then Left(SessionStoreError.ConcurrentModification)
      else if ref.compareAndSet(current, Some(updatedSession)) then Right(())
      else loop()
    loop()

  def clear(): Either[SessionStoreError, Unit] =
    ref.set(None)
    Right(())
