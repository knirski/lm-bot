package lmbot.backend.http

import sttp.model.headers.Cookie.SameSite
import sttp.model.headers.CookieValueWithMeta

import java.time.Duration

object SessionCookie:

  /** `HttpOnly` keeps the token out of reach of page scripts, `SameSite=Lax`
    * blocks cross-site submission, `Secure` is on unless local dev turns it
    * off. Spec §6.
    */
  def issue(
      token: String,
      secure: Boolean,
      ttl: Duration
  ): CookieValueWithMeta =
    CookieValueWithMeta.unsafeApply(
      value = token,
      maxAge = Some(ttl.toSeconds),
      path = Some("/"),
      secure = secure,
      httpOnly = true,
      sameSite = Some(SameSite.Lax)
    )

  /** An empty value with `Max-Age=0` is what tells the browser to drop the
    * cookie; the server-side session is deleted separately.
    */
  def clear(secure: Boolean): CookieValueWithMeta =
    CookieValueWithMeta.unsafeApply(
      value = "",
      maxAge = Some(0L),
      path = Some("/"),
      secure = secure,
      httpOnly = true,
      sameSite = Some(SameSite.Lax)
    )
