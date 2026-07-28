package lmbot.frontend.elm

import gears.async.Async

/** A side effect to run outside `update`: an API call, a timer, storage access.
  *
  * Written as ordinary sequential Gears code. Returning `None` means the effect
  * produced nothing the application needs to react to.
  *
  * ==Linker note==
  * `Effect.run` takes `(using Async)` — the Gears async capability. The bodies
  * of these effects (calling `api.login`, `api.me`, etc.) go through
  * Bridge.await which is where the linker traces into JSPI internals. This is
  * fine: the linker limitation is in the entry point (@main → Runtime.run →
  * Future), not in the effect itself. Pure `update` tests that construct
  * effects without calling `.run` link without issues.
  */
trait Effect[+M]:
  def run(using Async): Option[M]

/** The result of `update`: the next state, plus effects to run. */
case class Transition[S, M](state: S, effects: List[Effect[M]])
