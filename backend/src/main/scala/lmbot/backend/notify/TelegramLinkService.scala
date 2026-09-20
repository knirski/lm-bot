package lmbot.backend.notify

import java.security.SecureRandom
import java.time.{Duration, OffsetDateTime}

import lmbot.backend.auth.Passwords
import lmbot.backend.db.UserRepo
import lmbot.shared.api.{ApiError, TelegramLinkCodeView, TelegramSettingsView}
import lmbot.shared.domain.UserId

/** The settings-page half of Telegram linking: a one-time code, Argon2id-hashed
  * at rest, valid for 15 minutes and consumed by the first successful
  * `/start <code>`.
  */
final class TelegramLinkService(
    users: UserRepo,
    botUsername: Option[String],
    now: () => OffsetDateTime,
    randomCode: () => String = TelegramLinkService.randomCode
):
  private val codeTtl = Duration.ofMinutes(15)
  private val notConfigured =
    ApiError.Conflict("Telegram notifications are not configured.")

  def status(userId: UserId): Either[ApiError, TelegramSettingsView] =
    users
      .findById(userId)
      .toRight(ApiError.NotFound)
      .map: user =>
        TelegramSettingsView(
          available = botUsername.isDefined,
          linked = user.telegramChatId.isDefined,
          botUsername = botUsername
        )

  def createCode(userId: UserId): Either[ApiError, TelegramLinkCodeView] =
    botUsername match
      case None           => Left(notConfigured)
      case Some(username) =>
        users
          .findById(userId)
          .toRight(ApiError.NotFound)
          .map: _ =>
            val code = randomCode()
            val expiresAt = now().plus(codeTtl)
            users.setTelegramLinkCode(userId, Passwords.hash(code), expiresAt)
            TelegramLinkCodeView(
              code = code,
              deepLink = s"https://t.me/$username?start=$code",
              expiresAt = expiresAt.toInstant
            )

  def unlink(userId: UserId): Either[ApiError, Unit] =
    users
      .findById(userId)
      .toRight(ApiError.NotFound)
      .map: _ =>
        users.clearTelegramChatId(userId)
        users.clearTelegramLinkCode(userId)

  /** Verifies and consumes a `/start <code>` payload. Argon2id hashes cannot be
    * looked up, so the candidates are the users holding an unexpired code — a
    * handful at family scale.
    */
  def consume(code: String): Option[UserId] =
    users
      .listLinkableUsers(now())
      .collectFirst:
        case user
            if Passwords.verify(
              user.telegramLinkCodeHash.getOrElse(""),
              code
            ) =>
          val userId = UserId(user.id)
          users.clearTelegramLinkCode(userId)
          userId

object TelegramLinkService:
  private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
  private val random = SecureRandom()

  /** Ten characters from an alphabet without 0/O/1/I, so the code can also be
    * typed by hand if the deep link is inconvenient.
    */
  def randomCode(): String =
    val builder = StringBuilder()
    var index = 0
    while index < 10 do
      builder.append(alphabet.charAt(random.nextInt(alphabet.length)))
      index += 1
    builder.toString
