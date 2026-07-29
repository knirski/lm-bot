package lmbot.backend.luxmed.support

import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}

import scala.io.Source
import scala.jdk.CollectionConverters.*

import com.sun.net.httpserver.HttpServer

final case class MockResponse(
    status: Int,
    headers: Map[String, List[String]] = Map.empty,
    body: String = ""
)

final case class RecordedRequest(
    method: String,
    path: String,
    rawQuery: Option[String],
    headers: Map[String, List[String]],
    body: String
)

final class MockLuxmedServer(port: Int = 0):

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

  /** Enqueue the full realistic auth flow (matching real Luxmed API behavior):
    *
    *   1. OAuth password grant (200) with WAF cookies and OAuth tokens
    *   2. LogInToApp (302) with session cookie and Location header
    *   3. ReservationPage (200) with Authorization-Token header
    */
  def enqueueRealisticAuthFlow(
      accessToken: String = "AT1",
      refreshToken: String = "RT1",
      jwtToken: String = "JWT_TOKEN_1",
      sessionCookie: String = "ASP.NET_SessionId=sess1",
      expiresIn: Int = 600
  ): Unit =
    // 1. Token endpoint — 200 with OAuth tokens and WAF cookies
    enqueue(
      MockResponse(
        status = 200,
        headers = Map(
          "Set-Cookie" -> List(
            "visid_incap_2269135=waf123; Domain=.luxmed.pl; HttpOnly",
            "incap_ses_683_2269135=waf456; Domain=.luxmed.pl"
          ),
          "Content-Type" -> List("application/json")
        ),
        body =
          s"""{"access_token":"$accessToken","expires_in":$expiresIn,"refresh_token":"$refreshToken","token_type":"bearer"}"""
      )
    )
    // 2. LogInToApp — 302 redirect with session cookie
    enqueue(
      MockResponse(
        status = 302,
        headers = Map(
          "Set-Cookie" -> List(sessionCookie),
          "Location" -> List("/PatientPortal/NewPortal/Page/Reservation")
        ),
        body = ""
      )
    )
    // 3. ReservationPage — 200 with Authorization-Token header
    enqueue(
      MockResponse(
        status = 200,
        headers = Map(
          "Authorization-Token" -> List(s"Bearer $jwtToken")
        ),
        body = ""
      )
    )

  /** Enqueue the bootstrap flow (LogInToApp + ReservationPage) with realistic
    * responses:
    *   - LogInToApp: 302 redirect with session cookie
    *   - ReservationPage: 200 with Authorization-Token header
    *
    * This is the common second half of both the initial auth and the refresh
    * flow.
    */
  def enqueueRealisticBootstrapFlow(
      jwtToken: String = "JWT_TOKEN_1",
      sessionCookie: String = "ASP.NET_SessionId=sess1"
  ): Unit =
    enqueue(
      MockResponse(
        status = 302,
        headers = Map(
          "Set-Cookie" -> List(sessionCookie),
          "Location" -> List("/PatientPortal/NewPortal/Page/Reservation")
        ),
        body = ""
      )
    )
    enqueue(
      MockResponse(
        status = 200,
        headers = Map(
          "Authorization-Token" -> List(s"Bearer $jwtToken")
        ),
        body = ""
      )
    )

  def requests: List[RecordedRequest] = capturedRequests.asScala.toList

  def close(): Unit = server.stop(0)
