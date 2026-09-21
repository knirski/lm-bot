package lmbot.backend.account

import gears.async.Async
import lmbot.shared.domain.AccountId

/** Records a Luxmed auth failure against a stored account: mark it
  * `auth_failed` with the reason, pause its monitors, and notify the owner once
  * per episode.
  *
  * Both the monitor engine and the dictionary proxy can discover that an
  * account's credentials or session stopped working; keeping the policy behind
  * one interface means they cannot report it differently (issue #40).
  */
trait AccountHealthReporter:
  def reportAuthFailure(accountId: AccountId, reason: AccountStatusReason)(using
      Async
  ): Unit

object AccountHealthReporter:

  /** For harnesses and tests that do not exercise account health. */
  object Noop extends AccountHealthReporter:
    def reportAuthFailure(accountId: AccountId, reason: AccountStatusReason)(
        using Async
    ): Unit = ()
