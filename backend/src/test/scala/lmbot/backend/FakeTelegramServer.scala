package lmbot.backend

import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable
import scala.io.{Codec, Source}

import com.sun.net.httpserver.HttpServer

/** A loopback Bot API for the Plan 5 acceptance run.
  *
  * It records every `sendMessage` and serves queued `/start <code>` updates, so
  * the browser can link a chat while the real `TelegramLinkPoller` consumes the
  * update over HTTP. Nothing here reaches production: it lives in test scope
  * and is selected only by `TELEGRAM_API_BASE`.
  */
final class FakeTelegramServer:

  private val sent = mutable.ListBuffer.empty[(Long, String)]
  private val pending = mutable.Queue.empty[String]
  private val nextUpdateId = AtomicLong(1)
  private val sendFailures = new java.util.concurrent.atomic.AtomicInteger(0)

  /** Makes the next `times` sends answer ok=false, so an acceptance run can
    * drive the delivery-retry path.
    */
  def failNextSends(times: Int): Unit = sendFailures.set(times)

  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
  server.createContext(
    "/",
    exchange =>
      try
        val path = exchange.getRequestURI.getRawPath
        val body =
          val source =
            Source.fromInputStream(exchange.getRequestBody)(using Codec.UTF8)
          try source.mkString
          finally source.close()
        val response =
          if path.endsWith("/sendMessage") then sendMessage(body)
          else if path.endsWith("/getUpdates") then getUpdates()
          else """{"ok":false,"description":"unknown method"}"""
        val bytes = response.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.length)
        exchange.getResponseBody.write(bytes)
      catch
        case _: Throwable =>
          val bytes =
            """{"ok":false,"description":"fake telegram failure"}"""
              .getBytes(StandardCharsets.UTF_8)
          exchange.sendResponseHeaders(500, bytes.length)
          exchange.getResponseBody.write(bytes)
      finally exchange.close()
  )
  server.start()

  def baseUri: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  /** Every message the app sent, oldest first. */
  def messages: List[(Long, String)] = synchronized(sent.toList)

  /** Queues a `/start <code>` message from `chatId` for the next `getUpdates`.
    */
  def enqueueStart(code: String, chatId: Long): Unit = synchronized:
    val id = nextUpdateId.getAndIncrement()
    pending.enqueue(
      s"""{"update_id":$id,"message":{"message_id":$id,"chat":{"id":$chatId,"type":"private"},"text":"/start $code"}}"""
    )

  def close(): Unit = server.stop(0)

  private def sendMessage(body: String): String =
    val params = body
      .split('&')
      .flatMap: pair =>
        pair.split("=", 2) match
          case Array(key, value) =>
            Some(
              key -> URLDecoder.decode(value, StandardCharsets.UTF_8)
            )
          case _ => None
      .toMap
    val chatId = params.get("chat_id").flatMap(_.toLongOption).getOrElse(0L)
    val text = params.getOrElse("text", "")
    if sendFailures.getAndUpdate(n => Math.max(0, n - 1)) > 0 then
      """{"ok":false,"description":"fake transient failure"}"""
    else
      synchronized(sent += ((chatId, text)))
      s"""{"ok":true,"result":{"message_id":1,"chat":{"id":$chatId},"text":"ok"}}"""

  private def getUpdates(): String =
    val updates = synchronized(pending.dequeueAll(_ => true))
    s"""{"ok":true,"result":[${updates.mkString(",")}]}"""
