package lmbot.backend.notify

import java.time.OffsetDateTime

import lmbot.backend.db.UserRepo
import lmbot.backend.support.PostgresSuite
import lmbot.shared.api.{ApiError, TelegramSettingsView}
import lmbot.shared.domain.{Role, UserId}

class TelegramLinkServiceTest extends PostgresSuite:

  private val now = OffsetDateTime.parse("2026-08-10T07:00:00Z")
  private val code = "ABCDEFGHJK"

  private def aUser(): UserId =
    UserId(
      UserRepo(xa).insert("telegram", "Telegram", "hash", Role.User).id
    )

  private def service(username: Option[String] = Some("lm_bot")) =
    TelegramLinkService(UserRepo(xa), username, () => now, () => code)

  test("createCode stores only a hash and returns the deep link"):
    val userId = aUser()

    val view = service().createCode(userId).toOption.get

    assertEquals(view.code, code)
    assertEquals(view.deepLink, s"https://t.me/lm_bot?start=$code")
    assertEquals(view.expiresAt, now.plusMinutes(15).toInstant)

    val stored = UserRepo(xa).findById(userId).get
    assert(
      stored.telegramLinkCodeHash.exists(_ != code),
      "the code must never be stored in clear"
    )
    assertEquals(
      stored.telegramLinkCodeExpiresAt.map(_.toInstant),
      Some(now.plusMinutes(15).toInstant)
    )

  test("consume links the right user exactly once"):
    val userId = aUser()
    service().createCode(userId)

    assertEquals(service().consume(code), Some(userId))
    assertEquals(service().consume(code), None)

  test("consume rejects a wrong code"):
    val userId = aUser()
    service().createCode(userId)

    assertEquals(service().consume("WRONGWRONG"), None)

  test("consume rejects an expired code"):
    val userId = aUser()
    service().createCode(userId)
    val later = TelegramLinkService(
      UserRepo(xa),
      Some("lm_bot"),
      () => now.plusMinutes(16),
      () => code
    )

    assertEquals(later.consume(code), None)

  test("status reports availability and linkage"):
    val userId = aUser()

    assertEquals(
      service().status(userId),
      Right(TelegramSettingsView(true, false, Some("lm_bot")))
    )

    UserRepo(xa).setTelegramChatId(userId, 42L)
    assertEquals(service().status(userId).toOption.get.linked, true)

  test("without a bot, createCode conflicts and status is unavailable"):
    val userId = aUser()
    val unconfigured = service(None)

    assertEquals(
      unconfigured.status(userId),
      Right(TelegramSettingsView(false, false, None))
    )
    assertEquals(
      unconfigured.createCode(userId),
      Left(ApiError.Conflict("Telegram notifications are not configured."))
    )

  test("unlink clears the chat id and any pending code"):
    val userId = aUser()
    service().createCode(userId)
    UserRepo(xa).setTelegramChatId(userId, 42L)

    assertEquals(service().unlink(userId), Right(()))

    val stored = UserRepo(xa).findById(userId).get
    assertEquals(stored.telegramChatId, None)
    assertEquals(stored.telegramLinkCodeHash, None)

  test("status of a missing user is NotFound"):
    assertEquals(service().status(UserId(999999L)), Left(ApiError.NotFound))
