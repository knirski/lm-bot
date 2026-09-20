package lmbot.backend.monitor

import scala.concurrent.duration.*

import lmbot.backend.account.AccountStatusReason

/** What the engine should do after one loop iteration. */
private[monitor] enum Iteration:
  case Continue(
      sleepFor: FiniteDuration,
      persistentFailures: Int,
      consecutiveFailures: Int
  )
  case Stop

/** What to do after a failed check. */
private[monitor] enum FailureAction:
  case Retry(
      sleepFor: FiniteDuration,
      persistentFailures: Int,
      consecutiveFailures: Int
  )
  case FailMonitor
  case PauseAccount(reason: AccountStatusReason)
  case NotifyAdmin(sleepFor: FiniteDuration)

/** The pure retry policy from spec §5.5: transient failures back off
  * exponentially without ever failing the monitor, three consecutive
  * *persistent* failures fail it, and auth failures pause the account.
  */
private[monitor] object EnginePolicy:

  val MaxPersistentFailures = 3

  private val TransientBase = 1.minute
  private val TransientCap = 30.minutes
  private val RateLimitBase = 5.minutes
  private val RateLimitCap = 1.hour

  /** The wait between successful checks: the monitor's interval ±20 %. */
  def successSleep(intervalMinutes: Int, jitter: Double): FiniteDuration =
    val baseMillis = intervalMinutes.toLong * 60_000L
    val factor = 0.8 + 0.4 * Math.max(0.0, Math.min(1.0, jitter))
    Math.round(baseMillis * factor).millis

  def transientBackoff(consecutiveFailures: Int): FiniteDuration =
    capped(TransientBase, TransientCap, consecutiveFailures)

  def rateLimitedBackoff(consecutiveFailures: Int): FiniteDuration =
    capped(RateLimitBase, RateLimitCap, consecutiveFailures)

  def onFailure(
      failure: CheckFailure,
      persistentFailures: Int,
      consecutiveFailures: Int
  ): FailureAction =
    val nextConsecutive = consecutiveFailures + 1
    failure match
      case CheckFailure.AuthRejected =>
        FailureAction.PauseAccount(AccountStatusReason.AuthFailed)
      case CheckFailure.Challenge =>
        FailureAction.PauseAccount(AccountStatusReason.Challenge)
      case CheckFailure.RateLimited =>
        FailureAction.Retry(
          rateLimitedBackoff(nextConsecutive),
          persistentFailures,
          nextConsecutive
        )
      case CheckFailure.VersionRejected =>
        FailureAction.NotifyAdmin(transientBackoff(nextConsecutive))
      case _: CheckFailure.Transient =>
        FailureAction.Retry(
          transientBackoff(nextConsecutive),
          persistentFailures,
          nextConsecutive
        )
      case _: CheckFailure.Persistent =>
        val nextPersistent = persistentFailures + 1
        if nextPersistent >= MaxPersistentFailures then
          FailureAction.FailMonitor
        else
          FailureAction.Retry(
            transientBackoff(nextConsecutive),
            nextPersistent,
            nextConsecutive
          )

  private def capped(
      base: FiniteDuration,
      cap: FiniteDuration,
      consecutiveFailures: Int
  ): FiniteDuration =
    val exponent = Math.max(0, consecutiveFailures - 1)
    val millis = base.toMillis * Math.pow(2, exponent.toDouble).toLong
    FiniteDuration(Math.min(millis, cap.toMillis), MILLISECONDS)
