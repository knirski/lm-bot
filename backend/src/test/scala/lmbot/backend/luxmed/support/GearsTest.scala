package lmbot.backend.luxmed.support

import gears.async

/** Mixin for tests that need to run Gears Async code synchronously.
  *
  * {{{runAsync}} executes the given body within a Gears Async context, blocking
  * the calling thread until completion. This is the only meaning of "runAsync"
  * in Plan 3.
  */
trait GearsTest:
  import gears.async.default.given

  protected def runAsync[A](body: async.Async.Spawn ?=> A): A =
    async.Async.fromSync(body)
