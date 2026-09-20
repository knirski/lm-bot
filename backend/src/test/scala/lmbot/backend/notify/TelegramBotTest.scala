package lmbot.backend.notify

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

import scala.collection.mutable

import com.sun.net.httpserver.HttpServer
import lmbot.backend.config.Secret
import lmbot.backend.luxmed.support.GearsTest
import sttp.model.Uri

/** Wire-boundary tests for the Bot API client: real HTTP, real serialization.
  */
class TelegramBotTest extends munit.FunSuite with GearsTest:

  final private case class Recorded(
      path: String,
      query: String,
      body: String
  )

  private def withServer(response: Recorded => (Int, String))(
      body: (Uri, mutable.ListBuffer[Recorded]) => Unit
  ): Unit =
    val recorded = mutable.ListBuffer.empty[Recorded]
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
    server.createContext(
      "/",
      exchange =>
        val request = Recorded(
          exchange.getRequestURI.getPath,
          Option(exchange.getRequestURI.getRawQuery).getOrElse(""),
          new String(
            exchange.getRequestBody.readAllBytes,
            StandardCharsets.UTF_8
          )
        )
        recorded += request
        val (status, responseBody) = response(request)
        val bytes = responseBody.getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.length)
        exchange.getResponseBody.write(bytes)
        exchange.close()
    )
    server.start()
    try
      body(
        Uri.unsafeParse(s"http://127.0.0.1:${server.getAddress.getPort}"),
        recorded
      )
    finally server.stop(0)

  private def bot(base: Uri): TelegramBot =
    TelegramBot.production(Secret("TOKEN"), base)

  test("sendMessage posts chat_id and text and accepts ok"):
    withServer(_ =>
      (
        200,
        """{"ok":true,"result":{"message_id":1,"chat":{"id":5},"text":"hi"}}"""
      )
    ): (base, recorded) =>
      assertEquals(runAsync(bot(base).sendMessage(5L, "hi")), Right(()))
      assertEquals(recorded.head.path, "/botTOKEN/sendMessage")
      assert(recorded.head.body.contains("chat_id=5"))
      assert(recorded.head.body.contains("text=hi"))

  test("getUpdates sends offset, timeout and allowed_updates"):
    withServer(_ =>
      (
        200,
        """{"ok":true,"result":[{"update_id":7,"message":{"message_id":1,"chat":{"id":99},"text":"/start ABC"}}]}"""
      )
    ): (base, recorded) =>
      val result = runAsync(bot(base).getUpdates(7L, 25))
      assertEquals(
        result,
        Right(
          List(
            TelegramUpdate(
              7L,
              Some(TelegramMessage(TelegramChat(99L), Some("/start ABC")))
            )
          )
        )
      )
      assertEquals(recorded.head.path, "/botTOKEN/getUpdates")
      assert(recorded.head.query.contains("offset=7"))
      assert(recorded.head.query.contains("timeout=25"))
      assert(recorded.head.query.contains("allowed_updates"))

  test("an ok=false envelope is Rejected with Telegram's description"):
    withServer(_ => (200, """{"ok":false,"description":"bot was blocked"}""")):
      (
          base,
          _
      ) =>
        assertEquals(
          runAsync(bot(base).sendMessage(1L, "hi")),
          Left(NotificationError.Rejected("bot was blocked"))
        )

  test("a 5xx is Transient and never leaks the token"):
    withServer(_ => (503, "nope")): (base, _) =>
      val result = runAsync(bot(base).sendMessage(1L, "hi"))
      result match
        case Left(NotificationError.Transient(detail)) =>
          assert(!detail.contains("TOKEN"), s"token leaked: $detail")
        case other => fail(s"expected Transient, got $other")

  test("a transport failure is Transient and never leaks the token"):
    // sttp's SttpClientException message embeds the request URI, and this
    // request's URI contains the token; the detail must not carry it.
    val bot = TelegramBot.production(
      Secret("TOKEN"),
      Uri.unsafeParse("http://127.0.0.1:1")
    )

    val result = runAsync(bot.sendMessage(1L, "hi"))

    result match
      case Left(NotificationError.Transient(detail)) =>
        assert(!detail.contains("TOKEN"), s"token leaked: $detail")
        assert(!detail.contains("127.0.0.1"), s"URI leaked: $detail")
      case other => fail(s"expected Transient, got $other")

  test("malformed JSON is Rejected, not an exception"):
    withServer(_ => (200, "not json")): (base, _) =>
      assertEquals(
        runAsync(bot(base).getUpdates(0L, 1)),
        Left(NotificationError.Rejected("Malformed Telegram response"))
      )
