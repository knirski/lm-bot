package lmbot.backend.luxmed.support

import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}

import scala.io.Source
import scala.jdk.CollectionConverters.*

import com.sun.net.httpserver.HttpServer

/** A real loopback HTTP server for wire-boundary tests.
  *
  * Exposes both Luxmed base paths on one random-port server and captures
  * requests for exact method, path, full query parameter, header, cookie, and
  * body assertions. Response fixtures are enqueued as [[MockResponse]].
  *
  * This is the renamed and reduced replacement for [[MockLuxmedServer]].
  * Luxmed-specific auth scripts are in [[LuxmedResponseScripts]] — this class
  * has no knowledge of auth flows.
  */
final class RealHttpLuxmedServer(port: Int = 0):

  private val responseQueue = new ConcurrentLinkedQueue[MockResponse]()
  private val capturedRequests = new ConcurrentLinkedQueue[RecordedRequest]()

  private val server = HttpServer.create(new InetSocketAddress(port), 0)
  server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
  val context = server.createContext("/")
  context.setHandler { exchange =>
    val body = Source.fromInputStream(exchange.getRequestBody).mkString
    capturedRequests.add(
      RecordedRequest(
        method = exchange.getRequestMethod,
        path = exchange.getRequestURI.getRawPath,
        rawQuery = Option(exchange.getRequestURI.getRawQuery),
        headers = exchange.getRequestHeaders.asScala.map { (k, v) =>
          k -> v.asScala.toList
        }.toMap,
        body = body
      )
    )
    val response = Option(responseQueue.poll()).getOrElse(MockResponse(404))
    response.headers.foreach { (k, vs) =>
      vs.foreach(v => exchange.getResponseHeaders.add(k, v))
    }
    val bodyBytes = response.body.getBytes("UTF-8")
    exchange.sendResponseHeaders(response.status, bodyBytes.length)
    exchange.getResponseBody.write(bodyBytes)
    exchange.close()
  }
  server.start()

  def localPort: Int = server.getAddress.getPort
  def baseUri: String = s"http://localhost:$localPort"

  def enqueue(response: MockResponse): Unit = responseQueue.add(response)

  def enqueue(
      status: Int,
      headers: Map[String, String] = Map.empty,
      body: String = ""
  ): Unit =
    responseQueue.add(
      MockResponse(
        status = status,
        headers = headers.map((k, v) => k -> List(v)),
        body = body
      )
    )

  def requests: List[RecordedRequest] = capturedRequests.asScala.toList

  def close(): Unit = server.stop(0)
