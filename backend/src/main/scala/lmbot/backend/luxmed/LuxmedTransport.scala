package lmbot.backend.luxmed

import java.net.http.HttpClient
import java.time.Duration

import gears.async.Async
import lmbot.backend.config.{SafeDiagnostic, Secret}
import lmbot.backend.luxmed.model.*
import sttp.client3.*
import sttp.model.Uri

private[luxmed] trait RequestPermit:
  def beforeRequest()(using Async): Unit

final case class RedactedResponse(
    status: Int,
    headers: Map[String, String],
    bodySummary: String
)

private[luxmed] trait WireObserver:
  def observed(fingerprint: WireFingerprint): Unit

object WireObserver:
  object Noop extends WireObserver:
    def observed(fingerprint: WireFingerprint): Unit = ()

final case class WireFingerprint(
    step: String,
    status: Int,
    headerNames: Set[String],
    cookieNames: Set[String],
    decodedBody: String,
    bodyShape: Option[JsonShape]
)

final class LuxmedTransport(
    config: LuxmedConfig,
    observer: WireObserver = WireObserver.Noop
):

  private val backend = HttpClientSyncBackend.usingClient(
    HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(15))
      .build()
  )

  private val commonHeaders = Map(
    "Accept" -> "application/json, text/plain, */*",
    "Accept-Encoding" -> "gzip, deflate, br",
    "Accept-Language" -> "pl;q=1.0, pl;q=0.9, en;q=0.8"
  )

  private val deviceAgent =
    s"Patient Portal; ${config.appVersion.value}; ${config.deviceUuid}; Android; ${config.apiLevel}; ${config.deviceModel}"

  private val oldApiHeaders = commonHeaders ++ Map(
    "X-Api-Client-Identifier" -> "Android",
    "Custom-User-Agent" -> deviceAgent,
    "User-Agent" -> "okhttp/4.9.0"
  )

  private val newApiHeaders = commonHeaders ++ Map(
    "Custom-User-Agent" -> deviceAgent,
    "User-Agent" -> "Mozilla/5.0 (Linux; Android 13; Galaxy S23 Build/TQ2B.230505.005.A1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/101.0.4951.61 Safari/537.36"
  )

  private def mkRequest =
    basicRequest
      .response(asStringAlways)
      .followRedirects(false)
      .asInstanceOf[Request[String, Any]]

  /** GET on the old API (PatientPortalMobileAPI). */
  def oldApiGet(
      endpoint: LuxmedEndpoint,
      params: Map[String, String] = Map.empty
  )(using
      a: Async,
      p: RequestPermit
  ): Either[LuxmedError, TransportResponse[String]] =
    run(a, p)(
      mkRequest
        .get(uri(config.oldApi, endpoint, params))
        .headers(oldApiHeaders)
    )

  /** POST form-encoded on the old API (PatientPortalMobileAPI). */
  def oldApiPostForm(
      endpoint: LuxmedEndpoint,
      body: Map[String, String]
  )(using
      a: Async,
      p: RequestPermit
  ): Either[LuxmedError, TransportResponse[String]] =
    run(a, p)(
      mkRequest
        .post(uri(config.oldApi, endpoint, Map.empty))
        .headers(oldApiHeaders)
        .body(body)
    )

  /** GET on the new Portal API with an authenticated session. */
  def newApiGet(
      endpoint: LuxmedEndpoint,
      params: Map[String, String] = Map.empty,
      session: LuxmedSession
  )(using
      a: Async,
      p: RequestPermit
  ): Either[LuxmedError, TransportResponse[String]] =
    run(a, p)(
      mkRequest
        .get(uri(config.newApi, endpoint, params))
        .headers(newApiHeaders)
        .header("Authorization-Token", s"Bearer ${session.jwtToken.value}")
        .cookies(session.cookies.toSeq*)
    )

  /** GET on the new Portal API during the bootstrap flow (no JWT yet). */
  def newApiBootstrapGet(
      endpoint: LuxmedEndpoint,
      accessToken: Secret,
      cookies: CookieJar,
      params: Map[String, String] = Map.empty
  )(using
      a: Async,
      p: RequestPermit
  ): Either[LuxmedError, TransportResponse[String]] =
    run(a, p)(
      mkRequest
        .get(uri(config.newApi, endpoint, params))
        .headers(newApiHeaders)
        .header("Authorization", accessToken.value)
        .header("X-Requested-With", "pl.luxmed.pp")
        .cookies(cookies.toSeq*)
    )

  /** POST JSON on the new Portal API with optional XSRF protection. */
  def newApiPost(
      endpoint: LuxmedEndpoint,
      body: String,
      session: LuxmedSession,
      xsrfToken: Option[Secret] = None,
      extraCookies: CookieJar = CookieJar.empty,
      params: Map[String, String] = Map.empty
  )(using
      a: Async,
      p: RequestPermit
  ): Either[LuxmedError, TransportResponse[String]] =
    val mergedCookies = session.cookies.merge(extraCookies.toList)
    var r = mkRequest
      .post(uri(config.newApi, endpoint, params))
      .headers(newApiHeaders)
      .header("Authorization-Token", s"Bearer ${session.jwtToken.value}")
      .header("Content-Type", "application/json")
      .body(body)
      .cookies(mergedCookies.toSeq*)
    xsrfToken.foreach: tok =>
      r = r.header("xsrf-token", tok.value)
    run(a, p)(r)

  /** POST form-encoded on the new Portal API with optional XSRF protection. */
  def newApiPostForm(
      endpoint: LuxmedEndpoint,
      body: Map[String, String],
      session: LuxmedSession,
      xsrfToken: Option[Secret] = None,
      extraCookies: CookieJar = CookieJar.empty,
      params: Map[String, String] = Map.empty
  )(using
      a: Async,
      p: RequestPermit
  ): Either[LuxmedError, TransportResponse[String]] =
    val mergedCookies = session.cookies.merge(extraCookies.toList)
    var r = mkRequest
      .post(uri(config.newApi, endpoint, params))
      .headers(newApiHeaders)
      .header("Authorization-Token", s"Bearer ${session.jwtToken.value}")
      .header("Content-Type", "application/x-www-form-urlencoded")
      .body(body)
      .cookies(mergedCookies.toSeq*)
    xsrfToken.foreach: tok =>
      r = r.header("xsrf-token", tok.value)
    run(a, p)(r)

  /** Convert a [[LuxmedEndpoint]] to a full URI by appending its path segments
    * and query params to the base.
    */
  private def uri(
      base: Uri,
      ep: LuxmedEndpoint,
      params: Map[String, String]
  ): Uri =
    base.addPath(ep.path.split('/').toSeq).addParams(params)

  private def run(
      async: Async,
      permit: RequestPermit
  )(
      request: Request[String, Any]
  ): Either[LuxmedError, TransportResponse[String]] =
    permit.beforeRequest()(using async)
    val response =
      try Right(request.send(backend))
      catch
        case e: SttpClientException =>
          val result = Left(LuxmedError.NetworkFailure(safeDiagnostic(e)))
          reportFingerprint(result, null)
          result
    val classified = response.flatMap(classify)
    response.foreach: resp =>
      reportFingerprint(classified, resp.body)
    classified

  private def reportFingerprint(
      result: Either[LuxmedError, TransportResponse[String]],
      rawBody: String | Null
  ): Unit =
    val fingerprint = result match
      case Right(resp) =>
        val jsonShape =
          if rawBody != null then
            try Some(JsonShape.parse(rawBody))
            catch case _: Exception => None
          else None
        val bodyLabel = result.toOption
          .flatMap(r =>
            if r.status >= 200 && r.status < 300 then guessBodyLabel(r.body)
            else None
          )
          .getOrElse(
            result.fold(
              _.getClass.getSimpleName,
              r => if r.body.isEmpty then "EmptySuccess" else "SuccessBody"
            )
          )
        WireFingerprint(
          step = "",
          status = resp.status,
          headerNames = resp.headers.map(_._1.toLowerCase).toSet,
          cookieNames = resp.cookies.map(_._1).toSet,
          decodedBody = bodyLabel,
          bodyShape = jsonShape
        )
      case Left(err) =>
        WireFingerprint(
          step = "",
          status = 0,
          headerNames = Set.empty,
          cookieNames = Set.empty,
          decodedBody = err.getClass.getSimpleName,
          bodyShape = None
        )
    observer.observed(fingerprint)

  private def guessBodyLabel(body: String): Option[String] =
    if body.isEmpty then Some("EmptySuccess")
    else
      try
        body.take(100) match
          case s if s.contains("""access_token""") => Some("OAuthTokens")
          case s
              if s.contains("""cities""") || (s
                .startsWith("[") && s.contains("""name""")) =>
            if s.contains("""id""") && s.contains("""expanded""") then
              Some("ServiceVariants")
            else Some("Cities")
          case s if s.contains("""facilities""") && s.contains("""doctors""") =>
            Some("FacilitiesAndDoctors")
          case s if s.contains("""correlationId""") => Some("TermsResponse")
          case s if s.contains("""token""") && s.length < 100 =>
            Some("XsrfToken")
          case s if s.contains("""temporaryReservationId""") =>
            Some("LockResponse")
          case s
              if s.contains("""reservationId""") && s.contains(
                """canSelfConfirm"""
              ) =>
            Some("ConfirmResponse")
          case _ => None
      catch case _: Exception => None

  private def classify(
      response: Response[String]
  ): Either[LuxmedError, TransportResponse[String]] =
    val body = response.body
    val status = response.code.code
    val bodyLower = body.toLowerCase

    if status >= 300 && status < 400 then
      val location = response.headers
        .find(h => h.name.equalsIgnoreCase("Location"))
        .map(_.value)
        .getOrElse("")
      val locationLower = location.toLowerCase
      if locationLower.contains("/logon") ||
        locationLower.contains("/universallink") ||
        bodyLower.contains("/logon") ||
        bodyLower.contains("/universallink")
      then return Left(LuxmedError.SessionExpired)

    if bodyLower.contains("session has expired") ||
      bodyLower.contains("logged out due to inactivity")
    then return Left(LuxmedError.SessionExpired)

    if status == 409 then
      if bodyLower.contains("invalid login or password") ||
        bodyLower.contains("nieprawidłowy login lub hasło")
      then return Left(LuxmedError.AuthFailed)

    if status == 429 then return Left(LuxmedError.RateLimited)
    if status >= 500 then return Left(LuxmedError.Transient(status))

    if body.contains(
        "Obecnie zainstalowana wersja aplikacji nie jest wspierana"
      )
    then return Left(LuxmedError.VersionRejected(LuxmedRedaction.safe(body)))

    if body.contains("\"challengeId\"") then
      return Left(
        LuxmedError.UnexpectedAuthResponse(LuxmedRedaction.safe(body))
      )

    // Redirects are deliberately not followed. Session-expiry redirects are
    // errors; other 3xx responses remain available for bootstrap callers to
    // inspect because Luxmed may attach cookies or authorization headers to
    // the redirect response itself.
    if status >= 200 && status < 400 then
      Right(
        TransportResponse(
          body = body,
          // Include history defensively if a different backend configuration
          // supplies it; the production request configuration does not follow
          // redirects.
          headers = (response.history.flatMap(_.headers) ++ response.headers)
            .map(h => h.name -> h.value)
            .toList,
          // Preserve cookies attached to the response.
          cookies = extractCookies(response),
          status = status,
          authTokenHeader = response.headers
            .find(h => h.name.equalsIgnoreCase("Authorization-Token"))
            .map(h => Secret(h.value)),
          jwtHeader = response.headers
            .find(h => h.name.equalsIgnoreCase("Authorization-Token"))
            .map(h => Secret(h.value))
        )
      )
    else
      Left(
        LuxmedError.ApiRejected(LuxmedRedaction.safe(body))
      )

  private def extractCookies(
      response: Response[String]
  ): List[(String, Secret)] =
    // Preserve any history defensively, though production requests do not
    // follow redirects.
    (response.history.flatMap(_.headers) ++ response.headers)
      .filter(h => h.name.equalsIgnoreCase("Set-Cookie"))
      .flatMap { h =>
        h.value
          .split(';')
          .headOption
          .map: cookiePart =>
            val separator = cookiePart.indexOf('=')
            if separator < 0 then (cookiePart.trim, Secret(""))
            else
              (
                cookiePart.substring(0, separator).trim,
                Secret(cookiePart.substring(separator + 1).trim)
              )
      }
      .toList

  private def safeDiagnostic(error: Throwable): SafeDiagnostic =
    SafeDiagnostic(
      Option(error.getMessage)
        .map(_.nn)
        .filter(_.nonEmpty)
        .getOrElse(error.getClass.getSimpleName)
    )

final case class TransportResponse[+A](
    body: A,
    headers: List[(String, String)],
    cookies: List[(String, Secret)],
    status: Int,
    authTokenHeader: Option[Secret],
    jwtHeader: Option[Secret]
):
  override def toString: String =
    s"TransportResponse(status=$status, headers=${headers.map(_._1).distinct}, " +
      s"cookies=${cookies.map(_._1).distinct}, " +
      s"bodySummary=${LuxmedRedaction.summary(body.toString)})"

/** Redaction logic for diagnostic messages.
  *
  * Extracted as a package-level utility so it can be reused by
  * [[TransportResponse.toString]] and [[SafeDiagnostic]] construction without
  * duplication.
  */
private[luxmed] object LuxmedRedaction:
  private val secretFields =
    """(?i)((?:access_token|refresh_token|password|jwt|jwtToken|cookie|authorization-token|authorization)\s*["':=]+\s*"?)[^",}\s]+("?)""".r
  private val bearerToken = """(?i)\bBearer\s+[^\s",}]+""".r
  private val email =
    """[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""".r
  private val phone = """\b\d{3}\s\d{3}\s\d{3}\b""".r

  /** Produce a redacted [[SafeDiagnostic]] from raw content. */
  def safe(raw: String): SafeDiagnostic =
    SafeDiagnostic(summary(raw))

  /** Produce a redacted string summary from raw content. */
  def summary(body: String): String =
    val withBearer = bearerToken.replaceAllIn(
      body,
      m => m.group(0).take(7) + "***"
    )
    val withSecrets =
      secretFields.replaceAllIn(
        withBearer,
        m => s"${m.group(1)}***${m.group(2)}"
      )
    val withEmails = email.replaceAllIn(withSecrets, "<redacted-email>")
    phone.replaceAllIn(withEmails, "<redacted-phone>").take(200)
