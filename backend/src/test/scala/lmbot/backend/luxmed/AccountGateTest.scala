package lmbot.backend.luxmed

import lmbot.backend.luxmed.support.{FakeTime, GearsTest}
import gears.async.{Async, Future}
import java.time.Duration

class AccountGateTest extends munit.FunSuite with GearsTest:

  private def millis(value: Long): Duration = Duration.ofMillis(value)

  test("two account operations never overlap"):
    val fake = FakeTime()
    val gate = AccountGate(
      minimumSpacing = millis(0),
      now = () => fake.now(),
      sleeper = fake.sleeper
    )
    runAsync:
      Async.group:
        val entered = Future:
          gate.serialized:
            fake.advance(millis(100))
        val second = Future:
          gate.serialized:
            ()
        entered.awaitResult
        second.awaitResult

  test("a permit spaces each HTTP request, not only each public operation"):
    val fake = FakeTime()
    val gate = AccountGate(
      minimumSpacing = Duration.ofSeconds(1),
      now = () => fake.now(),
      sleeper = fake.sleeper
    )
    runAsync:
      gate.serialized:
        summon[AccountGatePermit].beforeRequest()
        fake.advance(millis(200))
        summon[AccountGatePermit].beforeRequest()
    assertEquals(fake.sleeps, List(millis(800)))

  test("no spacing before the first request"):
    val fake = FakeTime()
    val gate = AccountGate(
      minimumSpacing = Duration.ofSeconds(1),
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
      minimumSpacing = millis(500),
      now = () => fake.now(),
      sleeper = fake.sleeper
    )
    runAsync:
      gate.serialized:
        summon[AccountGatePermit].beforeRequest()
        summon[AccountGatePermit].beforeRequest()
    assertEquals(fake.sleeps, List(millis(500)))

  test("spacing continues across serialized operations"):
    val fake = FakeTime()
    val gate = AccountGate(
      minimumSpacing = Duration.ofSeconds(1),
      now = () => fake.now(),
      sleeper = fake.sleeper
    )
    runAsync:
      gate.serialized:
        summon[AccountGatePermit].beforeRequest()
      fake.advance(millis(200))
      gate.serialized:
        summon[AccountGatePermit].beforeRequest()
    assertEquals(fake.sleeps, List(millis(800)))

  test("serialized operations queue up behind the semaphore"):
    val fake = FakeTime()
    val gate = AccountGate(
      minimumSpacing = millis(0),
      now = () => fake.now(),
      sleeper = fake.sleeper
    )
    runAsync:
      Async.group:
        val first = Future:
          gate.serialized:
            fake.advance(millis(50))
        fake.advance(millis(1))
        val second = Future:
          gate.serialized:
            ()
        first.awaitResult
        second.awaitResult
