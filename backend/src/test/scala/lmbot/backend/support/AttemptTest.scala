package lmbot.backend.support

import lmbot.backend.support.result.?

class AttemptTest extends munit.FunSuite:

  test("attempt converts a thrown failure into a Left via the classifier"):
    val actual = attempt[String, Int](_.getMessage):
      throw RuntimeException("boom")
    assertEquals(actual, Left("boom"))

  test("attempt passes through a successful block unchanged"):
    val actual = attempt[String, Int](_.getMessage)(21 + 21)
    assertEquals(actual, Right(42))

  test("attempt.either flattens a block that already returns Either"):
    val ok = attempt.either[String, Int](_.getMessage)(Right(1))
    val failure = attempt.either[String, Int](_.getMessage)(Left("nope"))
    assertEquals(ok, Right(1))
    assertEquals(failure, Left("nope"))

  test(
    "attempt rethrows a boundary.Break instead of swallowing it as a failure"
  ):
    // `.?` inside the `attempt` block breaks out to the *enclosing* `result`
    // boundary, not to a boundary of `attempt`'s own — proving `attempt` never
    // catches `boundary.Break` and reports it as an ordinary thrown failure.
    val actual = result[String, Int]:
      val guarded: Either[String, Int] =
        attempt[String, Int](_.getMessage):
          val inner: Either[String, Int] = Left("deep failure")
          inner.?
      guarded.?
    assertEquals(actual, Left("deep failure"))
