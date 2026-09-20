package lmbot.backend

import java.util.concurrent.atomic.AtomicBoolean

import gears.async.Future

class BackgroundWorkerTest extends munit.FunSuite:

  test("a worker runs its body and stops when closed"):
    val started = new AtomicBoolean(false)
    val stopped = new AtomicBoolean(false)

    val worker = BackgroundWorker.start("test-worker")(stop ?=>
      started.set(true)
      stop.read()
      stopped.set(true)
    )

    worker.close()

    assert(started.get(), "body never started")
    assert(stopped.get(), "body never saw the stop signal")

  test("a worker awaits its children before close returns"):
    val childDone = new AtomicBoolean(false)

    val worker = BackgroundWorker.start("test-worker-children")(stop ?=>
      val child = Future:
        stop.read()
        childDone.set(true)
      child.awaitResult
    )

    worker.close()

    assert(childDone.get(), "child did not finish before close returned")

  test("closing twice is harmless"):
    val worker =
      BackgroundWorker.start("test-worker-idempotent")(stop ?=> stop.read())

    worker.close()
    worker.close()
