package lmbot.backend.luxmed.model

import lmbot.backend.config.Secret
import lmbot.backend.luxmed.CookieJar
import java.time.Instant

/** OAuth tokens returned by the password and refresh grants on the old
  * PatientPortalMobileAPI. Wire names are measured snake_case.
  *
  * @param accessToken
  *   The bearer access token (used in the NewPortal bootstrap, not as an HTTP
  *   Authorization header).
  * @param expiresIn
  *   Token lifetime in seconds (~600).
  * @param refreshToken
  *   Single-use refresh token.
  * @param tokenType
  *   Always "bearer".
  */
final case class OAuthTokens(
    accessToken: Secret,
    expiresIn: Int,
    refreshToken: Secret,
    tokenType: String
)

/** Plain credentials for the password grant.
  *
  * @param username
  *   Luxmed account username (email).
  * @param password
  *   Luxmed account password, secret-wrapped so that it never renders in logs.
  */
final case class Credentials(username: String, password: Secret)

/** A fully bootstrapped Luxmed session ready for authenticated NewPortal calls.
  *
  * @param accessToken
  *   The OAuth access token from the most recent password or refresh grant.
  * @param tokenType
  *   The token type string (e.g. "bearer").
  * @param refreshToken
  *   The current single-use refresh token.
  * @param expiresAt
  *   The instant at which the access token expires.
  * @param jwtToken
  *   The NewPortal JWT token obtained from the Authorization-Token header
  *   during bootstrap.
  * @param cookies
  *   The merged cookie jar accumulated across bootstrap and calls.
  */
final case class LuxmedSession(
    accessToken: Secret,
    tokenType: String,
    refreshToken: Secret,
    expiresAt: Instant,
    jwtToken: Secret,
    cookies: CookieJar
):
  override def toString: String =
    s"LuxmedSession(expiresAt=$expiresAt, cookies=${cookies.names})"

/** A decoded XSRF forgery token for reservation-mutating calls.
  *
  * @param token
  *   The XSRF token value.
  */
final case class XsrfToken(token: Secret)
