package lmbot.backend.notify

import java.net.http.HttpClient
import java.time.Duration

import scala.concurrent.duration.*

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  readFromString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.{
  CodecMakerConfig,
  JsonCodecMaker
}
import gears.async.Async
import lmbot.backend.config.Secret
import sttp.client3.*
import sttp.model.Uri

/** One Telegram update as far as lm-bot cares: the message's chat and text. */
final case class TelegramUpdate(
    updateId: Long,
    message: Option[TelegramMessage]
)

final case class TelegramMessage(chat: TelegramChat, text: Option[String])

final case class TelegramChat(id: Long)

final private case class TelegramEnvelope[A](
    ok: Boolean,
    description: Option[String],
    result: Option[A]
)

private object TelegramEnvelope:
  private inline def config: CodecMakerConfig =
    CodecMakerConfig
      .withSkipUnexpectedFields(true)
      .withFieldNameMapper {
        case "updateId" => "update_id"
        case other      => other
      }

  given JsonValueCodec[TelegramChat] = JsonCodecMaker.make(config)
  given JsonValueCodec[TelegramMessage] = JsonCodecMaker.make(config)
  given JsonValueCodec[TelegramUpdate] = JsonCodecMaker.make(config)
  given JsonValueCodec[List[TelegramUpdate]] = JsonCodecMaker.make(config)
  given envelopeListCodec
      : JsonValueCodec[TelegramEnvelope[List[TelegramUpdate]]] =
    JsonCodecMaker.make(config)
  given envelopeMessageCodec
      : JsonValueCodec[TelegramEnvelope[TelegramMessage]] =
    JsonCodecMaker.make(config)

/** The poller's view of the Bot API, so tests can script it without HTTP. */
trait TelegramApi:
  def sendMessage(chatId: Long, text: String)(using
      Async
  ): Either[NotificationError, Unit]
  def getUpdates(offset: Long, timeoutSeconds: Int)(using
      Async
  ): Either[NotificationError, List[TelegramUpdate]]

/** The Bot API as the notification boundary sees it. */
final class TelegramNotificationChannel(api: TelegramApi)
    extends NotificationChannel:
  def send(chatId: Long, text: String)(using
      Async
  ): Either[NotificationError, Unit] =
    api.sendMessage(chatId, text)

/** Plain sttp Bot API calls — no bot framework dependency (spec §3.5).
  *
  * The token is part of the URL path, so it never appears in an error value:
  * failures carry only the HTTP status or Telegram's own description.
  */
final class TelegramBot private (
    token: Secret,
    baseUri: Uri,
    backend: SttpBackend[Identity, Any]
) extends TelegramApi:

  import TelegramEnvelope.given

  private def botUri(method: String): Uri =
    baseUri.addPath("bot" + token.value, method)

  def sendMessage(chatId: Long, text: String)(using
      Async
  ): Either[NotificationError, Unit] =
    val request = basicRequest
      .post(botUri("sendMessage"))
      .readTimeout(TelegramBot.readTimeout)
      .body(Map("chat_id" -> chatId.toString, "text" -> text))
    run(request) match
      case Left(error) => Left(error)
      case Right(body) =>
        decode[TelegramMessage](body).map(_ => ())

  def getUpdates(offset: Long, timeoutSeconds: Int)(using
      Async
  ): Either[NotificationError, List[TelegramUpdate]] =
    val request = basicRequest
      .get(
        botUri("getUpdates").addParams(
          "offset" -> offset.toString,
          "timeout" -> timeoutSeconds.toString,
          "allowed_updates" -> """["message"]"""
        )
      )
      .readTimeout(TelegramBot.readTimeout)
    run(request) match
      case Left(error) => Left(error)
      case Right(body) =>
        decode[List[TelegramUpdate]](body)
          .map(_.getOrElse(Nil))

  private def run(
      request: Request[Either[String, String], Any]
  ): Either[NotificationError, String] =
    try
      val response = request.send(backend)
      response.body match
        case Right(body)                           => Right(body)
        case Left(body) if response.code.isSuccess => Right(body)
        case Left(_)                               =>
          Left(
            NotificationError.Transient(
              s"Telegram returned ${response.code.code}"
            )
          )
    catch
      case error: Exception =>
        // Never use the exception message: sttp's SttpClientException embeds
        // the request URI, and this request's URI contains the bot token. The
        // detail is persisted into notification_failed events and shown in the
        // UI, so it must not carry the secret.
        Left(
          NotificationError.Transient(
            s"Telegram is unreachable (${error.getClass.getSimpleName})"
          )
        )

  private def decode[A](body: String)(using
      codec: JsonValueCodec[TelegramEnvelope[A]]
  ): Either[NotificationError, Option[A]] =
    try
      val envelope = readFromString[TelegramEnvelope[A]](body)(using codec)
      if envelope.ok then Right(envelope.result)
      else
        Left(
          NotificationError.Rejected(
            envelope.description.getOrElse("Telegram rejected the request")
          )
        )
    catch
      case _: Exception =>
        Left(NotificationError.Rejected("Malformed Telegram response"))

object TelegramBot:
  /** Longer than the 10-second long poll, short enough that a stalled response
    * cannot hold a virtual thread indefinitely.
    */
  private[notify] val readTimeout: FiniteDuration = 30.seconds

  private val defaultBackend: SttpBackend[Identity, Any] =
    HttpClientSyncBackend.usingClient(
      HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    )

  def production(token: Secret, baseUri: Uri): TelegramBot =
    new TelegramBot(token, baseUri, defaultBackend)

  /** Test seam: an injected sttp backend. */
  private[notify] def withBackend(
      token: Secret,
      baseUri: Uri,
      backend: SttpBackend[Identity, Any]
  ): TelegramBot =
    new TelegramBot(token, baseUri, backend)
