package lmbot.frontend.elm

import gears.async.Async

/** A side effect to run outside `update`: an API call, a timer, storage access.
  *
  * Written as ordinary sequential Gears code. Returning `None` means the effect
  * produced nothing the application needs to react to.
  */
trait Effect[+M]:
  def run(using Async): Option[M]

/** The result of `update`: the next state, plus effects to run. */
case class Transition[S, M](state: S, effects: List[Effect[M]])
