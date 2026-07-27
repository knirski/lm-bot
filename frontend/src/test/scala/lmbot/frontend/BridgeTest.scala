package lmbot.frontend

import gears.async.js.JsAsyncFromSync
import lmbot.frontend.bridge.Bridge

import scala.concurrent.Future as StdFuture

class BridgeTest extends munit.FunSuite:

  test("a successful std Future becomes a Right"):
    JsAsyncFromSync:
      assertEquals(Bridge.await(StdFuture.successful(42)), Right(42))

  test("a failed std Future becomes a Left carrying the throwable"):
    JsAsyncFromSync:
      val boom = new RuntimeException("boom")
      Bridge.await(StdFuture.failed(boom)) match
        case Left(e)  => assertEquals(e.getMessage, "boom")
        case Right(v) => fail(s"expected Left, got $v")
