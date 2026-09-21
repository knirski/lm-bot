package lmbot.backend.monitor

import java.time.OffsetDateTime

import gears.async.Async
import lmbot.backend.account.{AccountHealthReporter, AccountStatusReason}
import lmbot.backend.db.{AccountRepo, MonitorEventRepo, MonitorRepo}
import lmbot.backend.notify.NotificationService
import lmbot.shared.domain.{AccountId, MonitorEventKind, MonitorId, UserId}
import org.slf4j.LoggerFactory

/** The one place an auth failure is recorded against an account: mark it
  * `auth_failed` with the reason, pause every active monitor of that account,
  * and notify the owner once per episode.
  *
  * "Once per episode" is the status transition itself: `markAuthFailed` reports
  * whether it changed anything, and only the first monitor to observe the
  * failure sends a message.
  */
final class AccountHealth(
    accounts: AccountRepo,
    monitors: MonitorRepo,
    events: MonitorEventRepo,
    notifier: NotificationService,
    now: () => OffsetDateTime
) extends AccountHealthReporter:

  private val log = LoggerFactory.getLogger(getClass)

  def reportAuthFailure(accountId: AccountId, reason: AccountStatusReason)(using
      Async
  ): Unit =
    accounts
      .findById(accountId)
      .foreach: account =>
        val firstFailure =
          accounts.markAuthFailed(accountId, reason.value, now())
        monitors
          .pauseAllForAccount(accountId)
          .foreach: paused =>
            events.append(
              MonitorId(paused.id),
              MonitorEventKind.MonitorPaused,
              None,
              Some(reason.value),
              now()
            )
        if firstFailure then
          log.warn(
            s"Luxmed account ${accountId.value} needs attention: ${reason.value}; " +
              "its monitors are paused"
          )
          notifier.notifyAccountAuthFailure(
            UserId(account.ownerUserId),
            account.label,
            reason
          )
