package lmbot.backend.support

import lmbot.backend.support.result.?

class ResultTest extends munit.FunSuite:

  private def step[A](value: Either[String, A]): Either[String, A] = value

  test("a Right threaded through several `.?` calls reaches the caller"):
    val actual = result[String, Int]:
      val a = step(Right(1)).?
      val b = step(Right(2)).?
      a + b
    assertEquals(actual, Right(3))

  test("a `Left` several calls deep short-circuits the whole block"):
    val actual = result[String, Int]:
      val a = step(Right(1): Either[String, Int]).?
      val b = step(Left("boom"): Either[String, Int]).?
      val c = step(Right(3): Either[String, Int]).? // never reached
      a + b + c
    assertEquals(actual, Left("boom"))

  test("the value type of `?` may differ from the block's own result type"):
    val rows: Either[String, List[Int]] = Right(List(1, 2, 3))
    val actual = result[String, List[String]]:
      step(rows).?.map(_.toString)
    assertEquals(actual, Right(List("1", "2", "3")))
