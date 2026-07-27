package lmbot.frontend.elm

import com.raquo.laminar.api.L.Var

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/** The Elm architecture on scala.concurrent.Future (spec §5.1 fallback).
  *
  *   - one store: `store`, the only Airstream `Var` in the app;
  *   - one message queue, fed by DOM handlers and async effect completions;
  *   - a micro-task event loop that applies the pure `update` and then runs
  *     each effect, dispatching async results back into the loop.
  */
class Runtime[S, M](initial: S, update: (S, M) => Transition[S, M]):

  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  private val queue = scala.collection.mutable.Queue[M]()

  private var running = true

  val store: Var[S] = Var(initial)

  /** The one thing a DOM handler is allowed to do. Non-suspending. */
  def dispatch(msg: M): Unit =
    queue.enqueue(msg)
    if queue.size == 1 then drain()

  def stop(): Unit =
    running = false

  /** Drains the queue micro-task by micro-task, processing messages and
    * running effects. Each effect's result (sync or async) is fed back into
    * the queue.
    */
  private def drain(): Unit =
    def process(): Unit =
      if running then
        queue.dequeue() match
          case null => ()
          case msg =>
            val Transition(next, effects, asyncEffects) = update(store.now(), msg)
            store.set(next)
            // Run sync effects immediately — results go back into the queue.
            effects.foreach: effect =>
              effect.run().foreach(result => dispatch(result))
            // Run async effects — results go back via Future callbacks.
            asyncEffects.foreach: effect =>
              effect.run().onComplete:
                case Success(Some(result)) => dispatch(result)
                case Success(None)         => ()
                case Failure(_)            => ()
        if queue.nonEmpty then
          scala.scalajs.js.timers.setTimeout(0)(process())
    if queue.nonEmpty then
      scala.scalajs.js.timers.setTimeout(0)(process())
