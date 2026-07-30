package lmbot.backend.luxmed.support

/** Backend-independent response script components for realistic Luxmed API
  * behaviour.
  *
  * Methods return (status, headers, body) triples where headers is a sequence
  * of name-value pairs supporting multiple headers with the same name (required
  * for `Set-Cookie`). These triples can be enqueued to either
  * [[StubLuxmedBackend]] or [[RealHttpLuxmedServer]]. Response values are
  * constructed as literal strings — never using production JSON codecs — so
  * they cannot silently encode a mismatch between encoder and decoder.
  */
object LuxmedResponseScripts:

  /** OAuth password-grant response with WAF cookies and token JSON. */
  def oauthPasswordGrant(
      accessToken: String = "AT1",
      refreshToken: String = "RT1",
      expiresIn: Int = 600
  ): (Int, List[(String, String)], String) =
    val headers = List(
      "Set-Cookie" -> "visid_incap_2269135=waf123; Domain=.luxmed.pl; HttpOnly",
      "Set-Cookie" -> "incap_ses_683_2269135=waf456; Domain=.luxmed.pl",
      "Content-Type" -> "application/json"
    )
    val body =
      s"""{"access_token":"$accessToken","expires_in":$expiresIn,"refresh_token":"$refreshToken","token_type":"bearer"}"""
    (200, headers, body)

  /** LogInToApp 302 redirect with session cookie. */
  def logInToAppRedirect(
      sessionCookie: String = "ASP.NET_SessionId=sess1"
  ): (Int, List[(String, String)], String) =
    val headers = List(
      "Set-Cookie" -> sessionCookie,
      "Location" -> "/PatientPortal/NewPortal/Page/Reservation"
    )
    (302, headers, "")

  /** ReservationPage 200 with Authorization-Token header. */
  def reservationPage(
      jwtToken: String = "JWT_TOKEN_1"
  ): (Int, List[(String, String)], String) =
    val headers = List(
      "Authorization-Token" -> s"Bearer $jwtToken"
    )
    (200, headers, "")

  /** Full three-response auth flow: password grant → LogInToApp →
    * ReservationPage.
    */
  def realisticAuthFlow(
      accessToken: String = "AT1",
      refreshToken: String = "RT1",
      jwtToken: String = "JWT_TOKEN_1",
      sessionCookie: String = "ASP.NET_SessionId=sess1",
      expiresIn: Int = 600
  ): List[(Int, List[(String, String)], String)] = List(
    oauthPasswordGrant(accessToken, refreshToken, expiresIn),
    logInToAppRedirect(sessionCookie),
    reservationPage(jwtToken)
  )

  /** Two-response bootstrap flow: LogInToApp → ReservationPage. Used after an
    * OAuth refresh or initial password grant.
    */
  def realisticBootstrapFlow(
      jwtToken: String = "JWT_TOKEN_1",
      sessionCookie: String = "ASP.NET_SessionId=sess1"
  ): List[(Int, List[(String, String)], String)] = List(
    logInToAppRedirect(sessionCookie),
    reservationPage(jwtToken)
  )
