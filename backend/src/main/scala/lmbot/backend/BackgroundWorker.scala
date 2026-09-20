package lmbot.backend

import gears.async.default.given
import gears.async.{Async, Channel, ReadableChannel, UnboundedChannel}

/** A background Gears runtime on its own virtual thread.
  *
  * `body` gets the stop channel; closing the worker closes the channel and
  * joins the thread. Virtual threads are daemons, so a worker that overruns the
  * join timeout (a Telegram long poll, say) can never block JVM exit.
  */
final class BackgroundWorker private (
    val name: String,
    stop: Channel[Unit],
    thread: Thread
) extends AutoCloseable:

  override def close(): Unit =
    stop.close()
    thread.join(BackgroundWorker.joinTimeoutMillis)

object BackgroundWorker:
  private val joinTimeoutMillis = 15_000L

  def start(
      name: String
  )(body: ReadableChannel[Unit] ?=> Async.Spawn ?=> Unit): BackgroundWorker =
    val stop = UnboundedChannel[Unit]()
    val thread = Thread
      .ofVirtual()
      .name(name)
      .unstarted(() => Async.fromSync(body(using stop)))
    thread.start()
    new BackgroundWorker(name, stop, thread)
