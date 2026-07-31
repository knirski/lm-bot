package lmbot.backend.http

import gears.async.Async
import gears.async.default.given

/** Enters a Gears `Async` context once, on the calling thread, for the handful
  * of routes whose service call needs one (account linking and every dictionary
  * lookup — both make outbound Luxmed calls).
  *
  * `Server.start` gives every request handler its own virtual thread (see its
  * doc comment: "Gears is used inside them"), so blocking that thread here
  * until the async body completes is exactly the pattern the server was built
  * for — this mirrors the test-only `GearsTest.runAsync` helper.
  */
private[http] object AsyncBoundary:
  def run[A](body: Async.Spawn ?=> A): A = Async.fromSync(body)
