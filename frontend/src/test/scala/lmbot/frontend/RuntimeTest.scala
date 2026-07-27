package lmbot.frontend

import gears.async.*
import gears.async.default.given
import gears.async.js.JsAsyncFromSync
import lmbot.frontend.elm.{Effect, Runtime, Transition}

class RuntimeTest extends munit.FunSuite:

  enum Msg:
    case Inc, Dec, Stop
    case Add(n: Int)

  private def counterUpdate(state: Int, msg: Msg): Transition[Int, Msg] = msg match
    case Msg.Inc    => Transition(state + 1, Nil)
    case Msg.Dec    => Transition(state - 1, Nil)
    case Msg.Add(n) => Transition(state + n, Nil)
    case Msg.Stop   => Transition(state, Nil)

  test("update is pure and needs no runtime at all"):
    assertEquals(counterUpdate(0, Msg.Inc).state, 1)
    assertEquals(counterUpdate(5, Msg.Add(3)).state, 8)
    assertEquals(counterUpdate(0, Msg.Inc).effects, Nil)

  // JsAsyncFromSync wraps the body in js.async and returns a
  // scala.concurrent.Future[T], which MUnit accepts as a test result.
  test("dispatched messages are folded into the store in order"):
    JsAsyncFromSync:
      Async.group:
        val rt   = new Runtime[Int, Msg](0, counterUpdate)
        val loop = Future(rt.run)

        rt.dispatch(Msg.Inc)
        rt.dispatch(Msg.Inc)
        rt.dispatch(Msg.Add(10))
        rt.dispatch(Msg.Dec)

        rt.awaitQuiescence()
        assertEquals(rt.store.now(), 11)
        rt.stop()
        loop.awaitResult
        ()

  test("an effect's resulting message is fed back into the loop"):
    JsAsyncFromSync:
      Async.group:
        def update(state: Int, msg: Msg): Transition[Int, Msg] = msg match
          case Msg.Inc =>
            val eff = new Effect[Msg]:
              def run(using Async): Option[Msg] = Some(Msg.Add(100))
            Transition(state + 1, List(eff))
          case other => counterUpdate(state, other)

        val rt   = new Runtime[Int, Msg](0, update)
        val loop = Future(rt.run)

        rt.dispatch(Msg.Inc)
        rt.awaitQuiescence()

        assertEquals(rt.store.now(), 101)
        rt.stop()
        loop.awaitResult
        ()

  test("an effect that yields no message still leaves state consistent"):
    JsAsyncFromSync:
      Async.group:
        def update(state: Int, msg: Msg): Transition[Int, Msg] = msg match
          case Msg.Inc =>
            val silent = new Effect[Msg]:
              def run(using Async): Option[Msg] = None
            Transition(state + 1, List(silent))
          case other => counterUpdate(state, other)

        val rt   = new Runtime[Int, Msg](0, update)
        val loop = Future(rt.run)
        rt.dispatch(Msg.Inc)
        rt.awaitQuiescence()

        assertEquals(rt.store.now(), 1)
        rt.stop()
        loop.awaitResult
        ()

  test("an effect that throws kills only its own fiber, not the loop"):
    JsAsyncFromSync:
      Async.group:
        def update(state: Int, msg: Msg): Transition[Int, Msg] = msg match
          case Msg.Inc =>
            val bad = new Effect[Msg]:
              def run(using Async): Option[Msg] = throw new RuntimeException("boom")
            Transition(state + 1, List(bad))
          case other => counterUpdate(state, other)

        val rt   = new Runtime[Int, Msg](0, update)
        val loop = Future(rt.run)

        rt.dispatch(Msg.Inc)
        rt.awaitQuiescence()
        // The loop survived, so this later message is still processed.
        rt.dispatch(Msg.Add(5))
        rt.awaitQuiescence()

        assertEquals(rt.store.now(), 6)
        rt.stop()
        loop.awaitResult
        ()
