package lmbot.backend.monitor

import scala.concurrent.duration.*

import lmbot.backend.account.AccountStatusReason

class EnginePolicyTest extends munit.FunSuite:

  test("a successful check waits the interval ±20% jitter"):
    assertEquals(EnginePolicy.successSleep(10, 0.0), 8.minutes)
    assertEquals(EnginePolicy.successSleep(10, 0.5), 10.minutes)
    assertEquals(EnginePolicy.successSleep(10, 1.0), 12.minutes)

  test("transient failures back off exponentially up to 30 minutes"):
    assertEquals(EnginePolicy.transientBackoff(1), 1.minute)
    assertEquals(EnginePolicy.transientBackoff(2), 2.minutes)
    assertEquals(EnginePolicy.transientBackoff(3), 4.minutes)
    assertEquals(EnginePolicy.transientBackoff(10), 30.minutes)

  test("rate limiting backs off from 5 minutes up to an hour"):
    assertEquals(EnginePolicy.rateLimitedBackoff(1), 5.minutes)
    assertEquals(EnginePolicy.rateLimitedBackoff(2), 10.minutes)
    assertEquals(EnginePolicy.rateLimitedBackoff(10), 1.hour)

  test("transient failures never count toward the fail budget"):
    EnginePolicy.onFailure(CheckFailure.Transient("boom"), 0, 0) match
      case FailureAction.Retry(sleep, persistent, consecutive) =>
        assertEquals(sleep, 1.minute)
        assertEquals(persistent, 0)
        assertEquals(consecutive, 1)
      case other => fail(s"expected Retry, got $other")

  test("three consecutive persistent failures fail the monitor"):
    EnginePolicy.onFailure(CheckFailure.Persistent("bad"), 0, 0) match
      case FailureAction.Retry(_, 1, 1) => ()
      case other => fail(s"expected the first retry, got $other")

    EnginePolicy.onFailure(CheckFailure.Persistent("bad"), 1, 1) match
      case FailureAction.Retry(_, 2, 2) => ()
      case other => fail(s"expected the second retry, got $other")

    assertEquals(
      EnginePolicy.onFailure(CheckFailure.Persistent("bad"), 2, 2),
      FailureAction.FailMonitor
    )

  test("auth failures pause the account with the matching reason"):
    assertEquals(
      EnginePolicy.onFailure(CheckFailure.AuthRejected, 0, 0),
      FailureAction.PauseAccount(AccountStatusReason.AuthFailed)
    )
    assertEquals(
      EnginePolicy.onFailure(CheckFailure.Challenge, 0, 0),
      FailureAction.PauseAccount(AccountStatusReason.Challenge)
    )

  test("a version rejection notifies the admin and backs off"):
    EnginePolicy.onFailure(CheckFailure.VersionRejected, 0, 0) match
      case FailureAction.NotifyAdmin(sleep) => assertEquals(sleep, 1.minute)
      case other => fail(s"expected NotifyAdmin, got $other")
