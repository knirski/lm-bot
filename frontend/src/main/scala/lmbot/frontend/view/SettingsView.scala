package lmbot.frontend.view

import com.raquo.laminar.api.L.*
import lmbot.frontend.elm.Runtime
import lmbot.frontend.{AppState, LoadState, Msg, TelegramSettings}

/** The Telegram half of the settings page (spec §3.5). Rendering only: status,
  * code creation, and unlinking are `Update`'s decisions, and the code's
  * lifetime is the server's.
  */
object SettingsView:

  def apply(rt: Runtime[AppState, Msg]): HtmlElement =
    div(
      cls := "settings",
      idAttr := "telegram-settings",
      h2("Settings"),
      h3("Telegram notifications"),
      child <-- rt.store.signal
        .map(_.telegram)
        .distinct
        .map(telegramSection(rt, _))
    )

  private def telegramSection(
      rt: Runtime[AppState, Msg],
      telegram: TelegramSettings
  ): HtmlElement =
    div(
      cls := "telegram",
      telegram.status match
        case LoadState.NotAsked | LoadState.Loading =>
          p(cls := "loading", "Loading Telegram status…")
        case LoadState.Failed(message) =>
          p(cls := "error", role := "alert", message)
        case LoadState.Loaded(status) if !status.available =>
          p(
            cls := "placeholder",
            "Telegram notifications are not configured on this server. " +
              "Monitor events are recorded, but no notifications are delivered."
          )
        case LoadState.Loaded(status) if status.linked =>
          p(
            "Telegram is linked to this account. Monitor notifications are " +
              "delivered to your chat."
          )
          button(
            disabled := telegram.submitting,
            if telegram.submitting then "Unlinking…" else "Unlink Telegram",
            onClick.mapTo(Msg.TelegramUnlinkRequested) --> (m => rt.dispatch(m))
          )
        case LoadState.Loaded(_) =>
          p(
            "No Telegram chat is linked. Monitors run and record events, but " +
              "no notifications will be delivered."
          )
          linkControls(rt, telegram)
      ,
      telegram.error
        .map(message => p(cls := "error", role := "alert", message))
        .toList
    )

  private def linkControls(
      rt: Runtime[AppState, Msg],
      telegram: TelegramSettings
  ): HtmlElement =
    div(
      cls := "link-controls",
      telegram.link match
        case LoadState.Loaded(link) =>
          div(
            cls := "link-code",
            p("Open the link, or send this code to the bot:"),
            p(cls := "code", link.code),
            a(href := link.deepLink, cls := "cta", "Open Telegram")
          )
        case _ => span()
      ,
      button(
        disabled := telegram.submitting,
        if telegram.submitting then "Requesting…" else "Link Telegram",
        onClick.mapTo(Msg.TelegramLinkRequested) --> (m => rt.dispatch(m))
      )
    )
