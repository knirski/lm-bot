package lmbot.backend.luxmed

import gears.async.Async
import lmbot.backend.config.{SafeDiagnostic, Secret}
import lmbot.backend.luxmed.model.*
import sttp.client3.*
import sttp.model.Uri
import java.net.http.HttpClient
import java.time.Duration

private[luxmed] trait RequestPermit:
  def beforeRequest()(using Async): Unit

final case class RedactedResponse(
    status: Int,
    headers: Map[String, String],
    bodySummary: String
)

final class LuxmedTransport(config: LuxmedConfig):

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
      extraCookies: CookieJar = CookieJar.empty
  )(using
      a: Async,
      p: RequestPermit
  ): Either[LuxmedError, TransportResponse[String]] =
    val mergedCookies = session.cookies.merge(extraCookies.toList)
    var r = mkRequest
      .post(uri(config.newApi, endpoint, Map.empty))
      .headers(newApiHeaders)
      .header("Authorization-Token", s"Bearer ${session.jwtToken.value}")
      .header("Content-Type", "application/json")
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
          Left(LuxmedError.NetworkFailure(safeDiagnostic(e)))
    response.flatMap(classify)

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

    if status >= 200 && status < 300 then
      Right(
        TransportResponse(
          body = body,
          headers = response.headers.map(h => h.name -> h.value).toList,
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
    response.headers
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
