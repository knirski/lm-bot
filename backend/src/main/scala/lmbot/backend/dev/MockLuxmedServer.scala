package lmbot.backend.dev

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

import scala.io.{Codec, Source}

import com.sun.net.httpserver.HttpServer
import sttp.model.Uri

/** A deterministic loopback stand-in for the Luxmed HTTP boundary.
  *
  * It is deliberately path-routed rather than FIFO-driven: browser requests for
  * dictionaries can arrive concurrently and in a different order after a
  * reload. The class is used when the live Luxmed API flag is false.
  */
final class MockLuxmedServer private (host: String) extends AutoCloseable:

  private val server = HttpServer.create(InetSocketAddress(host, 0), 0)
  private val executor = Executors.newVirtualThreadPerTaskExecutor()
  server.setExecutor(executor)
  server.createContext(
    "/",
    exchange =>
      try
        val path = exchange.getRequestURI.getRawPath
        val body = response(path, readBody(exchange))
        body.headers.foreach((name, value) =>
          exchange.getResponseHeaders.add(name, value)
        )
        val bytes = body.body.getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(body.status, bytes.length)
        exchange.getResponseBody.write(bytes)
      catch
        case _: Exception =>
          val bytes =
            "mock Luxmed server failure".getBytes(StandardCharsets.UTF_8)
          exchange.sendResponseHeaders(500, bytes.length)
          exchange.getResponseBody.write(bytes)
      finally exchange.close()
  )
  server.start()

  val oldApi: Uri = Uri.unsafeParse(
    s"http://$host:${server.getAddress.getPort}/PatientPortalMobileAPI/api"
  )
  val newApi: Uri = Uri.unsafeParse(
    s"http://$host:${server.getAddress.getPort}/PatientPortal"
  )

  override def close(): Unit =
    server.stop(0)
    executor.close()

  final private case class Response(
      status: Int,
      headers: List[(String, String)] = Nil,
      body: String = ""
  )

  private def readBody(exchange: com.sun.net.httpserver.HttpExchange): String =
    val source =
      Source.fromInputStream(exchange.getRequestBody)(using Codec.UTF8)
    try source.mkString
    finally source.close()

  private def response(path: String, requestBody: String): Response =
    if path.endsWith("/PatientPortalMobileAPI/api/token") then
      val refresh = requestBody.contains("grant_type=refresh_token")
      val token = if refresh then "mock-refresh-token" else "mock-access-token"
      Response(
        200,
        List(
          "Content-Type" -> "application/json",
          "Set-Cookie" -> "mock_waf=1; HttpOnly"
        ),
        s"""{"access_token":"$token","expires_in":600,"refresh_token":"mock-refresh-token","token_type":"bearer"}"""
      )
    else if path.endsWith("/Account/LogInToApp") then
      Response(
        302,
        List(
          "Set-Cookie" -> "ASP.NET_SessionId=mock-session",
          "Location" -> "/PatientPortal/NewPortal/Page/Reservation"
        )
      )
    else if path.endsWith("/NewPortal/Page/Reservation") then
      Response(200, List("Authorization-Token" -> "Bearer mock-jwt"))
    else if path.endsWith("/NewPortal/Dictionary/cities") then
      fixture("cities.json")
    else if path.endsWith("/NewPortal/Dictionary/serviceVariantsGroups") then
      fixture("service-variants.json")
    else if path.endsWith("/NewPortal/Dictionary/facilitiesAndDoctors") then
      fixture("facilities-and-doctors.json")
    else if path.endsWith("/NewPortal/terms/index") then fixture("terms.json")
    else if path.endsWith("/security/getforgerytoken") then
      Response(
        200,
        List("Content-Type" -> "application/json"),
        """{"token":"mock-xsrf-token"}"""
      )
    else Response(404)

  private def fixture(name: String): Response =
    val resource = s"/mock-luxmed/$name"
    val stream = Option(getClass.getResourceAsStream(resource)).getOrElse(
      throw IllegalStateException(s"Missing mock Luxmed fixture: $resource")
    )
    val source = Source.fromInputStream(stream)(using Codec.UTF8)
    val content =
      try source.mkString
      finally source.close()
    Response(200, List("Content-Type" -> "application/json"), content)

object MockLuxmedServer:
  def start(host: String = "127.0.0.1"): MockLuxmedServer =
    new MockLuxmedServer(host)
