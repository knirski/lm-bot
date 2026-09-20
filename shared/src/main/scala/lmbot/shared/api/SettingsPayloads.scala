package lmbot.shared.api

import java.time.Instant

/** Whether the Telegram boundary is configured at all, and whether this user
  * has linked a chat to it.
  */
final case class TelegramSettingsView(
    available: Boolean,
    linked: Boolean,
    botUsername: Option[String]
)

/** A one-time `/start <code>` payload. The code expires and is consumed on the
  * first successful use; `deepLink` is what the settings page renders.
  */
final case class TelegramLinkCodeView(
    code: String,
    deepLink: String,
    expiresAt: Instant
)
