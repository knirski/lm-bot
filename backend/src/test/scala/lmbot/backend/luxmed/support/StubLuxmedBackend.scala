package lmbot.backend.luxmed.support

import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*

import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.model.{Header, StatusCode}

/** A test-only stub backend backed by a FIFO queue of responses.
  *
  * Built on [[SttpBackendStub.synchronous]]. Enqueue responses before sending
  * requests; the stub captures every request in send order. A request arriving
  * when the queue is empty fails deterministically with a [[RuntimeException]].
  *
  * {{{
  *   val stub = StubLuxmedBackend()
  *   stub.enqueue(status = 200, body = "ok")
  *   val transport = LuxmedTransport.withBackend(config, stub.backend)
  *   // ... exercise transport ...
  *   assert(stub.requests.size == 1)
  * }}}
  */
final class StubLuxmedBackend:

  private val responseQueue = new ConcurrentLinkedQueue[Response[String]]()
  private val capturedRequests =
    new ConcurrentLinkedQueue[Request[String, Any]]()

  /** Enqueue a pre-built sttp [[Response]]. */
  def enqueue(response: Response[String]): Unit = responseQueue.add(response)

  /** Enqueue a response from status, headers (multi-value), and body.
    *
    * Headers are a sequence of (name, value) pairs, supporting multiple headers
    * with the same name (required for `Set-Cookie`).
    */
  def enqueue(
      status: Int,
      headers: List[(String, String)] = Nil,
      body: String = ""
  ): Unit =
    responseQueue.add(
      Response(
        body = body,
        code = StatusCode.unsafeApply(status),
        statusText = "",
        headers = headers.map { case (k, v) => Header(k, v) }
      )
    )

  /** The stub backend. All responses come from the FIFO queue. */
  lazy val backend: SttpBackend[Identity, Any] =
    SttpBackendStub.synchronous
      .whenRequestMatches { request =>
        capturedRequests.add(request.asInstanceOf[Request[String, Any]])
        true
      }
      .thenRespondF { _ =>
        Option(responseQueue.poll())
          .getOrElse(
            throw new RuntimeException(
              "StubLuxmedBackend: no response queued"
            )
          )
      }

  /** Captured requests in send order. */
  def requests: List[Request[String, Any]] = capturedRequests.asScala.toList

  /** Extract body as string from a captured request. Form-encoded bodies and
    * plain string bodies are both stored as [[StringBody]] by sttp.
    */
  def bodyString(request: Request[?, ?]): String =
    request.body match
      case b: StringBody => b.s
      case _             => ""
