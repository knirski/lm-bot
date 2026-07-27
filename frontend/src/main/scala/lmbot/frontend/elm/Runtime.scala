package lmbot.frontend.elm

import com.raquo.laminar.api.L.Var
import gears.async.*

import scala.util.{Failure, Success, Try}

/** The Elm architecture on Gears (spec §5.6).
  *
  *   - one store: `store`, the only Airstream `Var` in the app;
  *   - one message channel (Gears `UnboundedChannel`);
  *   - one event loop fiber, which applies the pure `update` and then runs
  *     each effect in its own fiber via Gears `Future`.
  *
  * == Linker note ==
  * This class (specifically `run` and `dispatch`) is the main entry point
  * into Gears async internals for the linker.  During Compile linking the
  * Scala.js 1.22.0 linker traces from @main into `Runtime.run` and finds
  * `Future(...)` → `JsAsyncScheduler.execute` → `js.async`, which triggers
  * the "orphan await" error.  Test linking avoids this because MUnit provides
  * its own entry point and the pure `update` tests never instantiate Runtime.
  * See build.sbt for the full discussion.
  */
class Runtime[S, M](initial: S, update: (S, M) => Transition[S, M]):

  /** Unbounded so that `dispatch` never has to suspend — DOM event handlers
    * cannot.
    */
  private val inbox = UnboundedChannel[M]()

  /** Outstanding work, tracked so tests can wait for the loop to settle rather
    * than sleeping a guessed interval. `queued` is decremented only *after* a
    * message's effects have been counted into `inFlight`, and an effect's
    * follow-up message is dispatched before that effect's `inFlight` is
    * released — so the pair is never both zero while work remains.
    *
    * Plain `var`s are sound here: the browser runs this single-threaded, and
    * this runtime is JS-only.
    */
  private var queued   = 0
  private var inFlight = 0

  val store: Var[S] = Var(initial)

  /** The one thing a DOM handler is allowed to do. Non-suspending. */
  def dispatch(msg: M): Unit =
    queued += 1
    inbox.sendImmediately(msg)

  def stop(): Unit = inbox.close()

  def run(using Async.Spawn): Unit =
    var state   = initial
    var running = true
    while running do
      inbox.read() match
        case Left(_) => running = false
        case Right(msg) =>
          val Transition(next, effects) = update(state, msg)
          state = next
          store.set(next)
          inFlight += effects.size
          effects.foreach: effect =>
            // Each effect gets its own fiber, so a crashing effect takes down
            // only itself and never the loop (spec §5.7.2).
            Future:
              try
                Try(effect.run) match
                  case Success(Some(resultMsg)) => dispatch(resultMsg)
                  case Success(None)            => ()
                  case Failure(_)               => ()
              finally inFlight -= 1
          queued -= 1

  /** Waits until every dispatched message — including those produced by
    * effects — has been handled. Test support; the browser never calls it.
    */
  def awaitQuiescence()(using Async, AsyncOperations): Unit =
    while queued > 0 || inFlight > 0 do AsyncOperations.sleep(1)
