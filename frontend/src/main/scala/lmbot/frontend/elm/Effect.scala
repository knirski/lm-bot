package lmbot.frontend.elm

/** A side effect to run outside `update`: an API call, a timer, storage access.
  * Written as synchronous code. Async effects (API calls) produce a Future,
  * which the Runtime resolves before dispatching the result message.
  */
trait Effect[+M]:
  def run(): Option[M]

/** An async effect: produces a Future that resolves to an optional message. */
trait AsyncEffect[+M]:
  def run(): scala.concurrent.Future[Option[M]]

/** The result of `update`: the next state, plus effects to run. */
case class Transition[S, M](state: S, effects: List[Effect[M]], asyncEffects: List[AsyncEffect[M]] = Nil)
