package lmbot.backend.monitor

import lmbot.backend.account.AccountStatusReason
import lmbot.backend.db.{AccountRepo, MonitorEventRepo, MonitorRepo}
import lmbot.shared.domain.{AccountId, MonitorId}

/** The shared account-health policy (issue #40): the engine and the dictionary
  * proxy both report through it, so this is where "mark, pause, notify once" is
  * pinned.
  */
class AccountHealthTest extends MonitorFixtures:

  private def health(channel: RecordingChannel): AccountHealth =
    AccountHealth(
      AccountRepo(xa),
      MonitorRepo(xa),
      MonitorEventRepo(xa),
      notifier(Some(channel)),
      () => fixedNow
    )

  test("the first report marks the account, pauses its monitors, and notifies"):
    val ownerId = anOwner(chatId = Some(555L))
    val accountId = anAccount(ownerId)
    val first = aMonitor(accountId)
    val second = aMonitor(accountId)
    val channel = RecordingChannel()
    val subject = health(channel)

    runAsync(
      subject.reportAuthFailure(
        AccountId(accountId),
        AccountStatusReason.AuthFailed
      )
    )

    val account = AccountRepo(xa).findById(AccountId(accountId))
    assertEquals(account.map(_.status), Some("auth_failed"))
    assertEquals(
      account.flatMap(_.statusReason),
      Some(AccountStatusReason.AuthFailed.value)
    )
    assertEquals(
      MonitorRepo(xa).findById(MonitorId(first.id)).map(_.state),
      Some("paused")
    )
    assertEquals(
      MonitorRepo(xa).findById(MonitorId(second.id)).map(_.state),
      Some("paused")
    )
    assertEquals(events(first.id), Seq("monitor_paused"))
    assertEquals(channel.sent.size, 1)

    // A second monitor failing in the same episode does not notify again.
    runAsync(
      subject.reportAuthFailure(
        AccountId(accountId),
        AccountStatusReason.AuthFailed
      )
    )

    assertEquals(channel.sent.size, 1)

  test("a disabled account is never overwritten and is not announced"):
    val ownerId = anOwner(chatId = Some(555L))
    val accountId = anAccount(ownerId, status = "disabled")
    val monitor = aMonitor(accountId)
    val channel = RecordingChannel()

    runAsync(
      health(channel).reportAuthFailure(
        AccountId(accountId),
        AccountStatusReason.AuthFailed
      )
    )

    assertEquals(
      AccountRepo(xa).findById(AccountId(accountId)).map(_.status),
      Some("disabled")
    )
    assertEquals(channel.sent.toList, Nil)
    assert(MonitorRepo(xa).findById(MonitorId(monitor.id)).isDefined)

  test("an unknown account is a no-op"):
    val channel = RecordingChannel()

    runAsync(
      health(channel).reportAuthFailure(
        AccountId(999999L),
        AccountStatusReason.Challenge
      )
    )

    assertEquals(channel.sent.toList, Nil)
