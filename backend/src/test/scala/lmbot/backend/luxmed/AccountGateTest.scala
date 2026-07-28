package lmbot.backend.luxmed

import lmbot.backend.luxmed.support.{FakeTime, GearsTest}
import gears.async.{Async, Future}
import scala.concurrent.duration.*

class AccountGateTest extends munit.FunSuite with GearsTest:

  test("two account operations never overlap"):
    val fake = FakeTime()
    val gate = AccountGate(
      minimumSpacing = 0.millis,
      now = () => fake.now(),
      sleeper = fake.sleeper
    )
    runAsync:
      Async.group:
        val entered = Future:
          gate.serialized:
            fake.advance(100.millis)
        val second = Future:
          gate.serialized:
            ()
        entered.awaitResult
        second.awaitResult

  test("a permit spaces each HTTP request, not only each public operation"):
    val fake = FakeTime()
    val gate = AccountGate(
      minimumSpacing = 1.second,
      now = () => fake.now(),
      sleeper = fake.sleeper
    )
    runAsync:
      gate.serialized:
        summon[AccountGatePermit].beforeRequest()
        fake.advance(200.millis)
        summon[AccountGatePermit].beforeRequest()
    assertEquals(fake.sleeps, List(800.millis))

  test("no spacing before the first request"):
    val fake = FakeTime()
    val gate = AccountGate(
      minimumSpacing = 1.second,
      now = () => fake.now(),
      sleeper = fake.sleeper
    )
    runAsync:
      gate.serialized:
        summon[AccountGatePermit].beforeRequest()
    assertEquals(fake.sleeps, Nil)

  test("full spacing is applied when no time has passed"):
    val fake = FakeTime()
    val gate = AccountGate(
      minimumSpacing = 500.millis,
      now = () => fake.now(),
      sleeper = fake.sleeper
    )
    runAsync:
      gate.serialized:
        summon[AccountGatePermit].beforeRequest()
        summon[AccountGatePermit].beforeRequest()
    assertEquals(fake.sleeps, List(500.millis))

  test("serialized operations queue up behind the semaphore"):
    val fake = FakeTime()
    val gate = AccountGate(
      minimumSpacing = 0.millis,
      now = () => fake.now(),
      sleeper = fake.sleeper
    )
    runAsync:
      Async.group:
        val first = Future:
          gate.serialized:
            fake.advance(50.millis)
        fake.advance(1.millis)
        val second = Future:
          gate.serialized:
            ()
        first.awaitResult
        second.awaitResult
