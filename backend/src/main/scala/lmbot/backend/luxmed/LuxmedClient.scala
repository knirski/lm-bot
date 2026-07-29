package lmbot.backend.luxmed

import java.time.{Duration, Instant}

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  readFromString,
  writeToString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import gears.async.Async
import lmbot.backend.config.{SafeDiagnostic, Secret}
import lmbot.backend.luxmed.model.*
import lmbot.backend.luxmed.model.WireCodecs.given

private enum ClientSessionState:
  case Unloaded
  case Ready(session: LuxmedSession)
  case PendingBootstrap(
      expectedRefreshToken: Option[Secret],
      oauth: OAuthTokens,
      cookies: CookieJar
  )
  case PendingPersistence(
      expectedRefreshToken: Option[Secret],
      session: LuxmedSession
  )

final class LuxmedClient(
    transport: LuxmedTransport,
    credentials: Credentials,
    gate: AccountGate,
    store: SessionStore,
    now: () => Instant = () => Instant.now()
):

  private val refreshThresholdSeconds = 300L

  private var state: ClientSessionState = ClientSessionState.Unloaded

  def authenticate()(using Async): Either[LuxmedError, LuxmedSession] =
    gate.serialized:
      store.clear() match
        case Left(error) => Left(toLuxmedError(error))
        case Right(())   =>
          state = ClientSessionState.Unloaded
          authenticateInternal()

  def withSession[A](
      op: (AccountGatePermit, LuxmedSession) => Either[LuxmedError, A]
  )(using Async): Either[LuxmedError, A] =
    gate.serialized:
      given AccountGatePermit = summon[AccountGatePermit]
      ensureSession() match
        case Left(e)        => Left(e)
        case Right(session) => runOperation(op, session)

  private def ensureSession()(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, LuxmedSession] =
    state match
      case ClientSessionState.Unloaded =>
        store.load() match
          case Left(error)          => Left(toLuxmedError(error))
          case Right(None)          => authenticateInternal()
          case Right(Some(session)) =>
            state = ClientSessionState.Ready(session)
            if isExpiring(session) then refreshAndBootstrap(session)
            else Right(session)
      case ClientSessionState.Ready(session) =>
        if isExpiring(session) then refreshAndBootstrap(session)
        else Right(session)
      case ClientSessionState.PendingBootstrap(
            expectedRefresh,
            oauth,
            cookies
          ) =>
        completeBootstrap(expectedRefresh, oauth, cookies)
      case ClientSessionState.PendingPersistence(expectedRefresh, session) =>
        persistSession(expectedRefresh, session)

  private def isExpiring(session: LuxmedSession): Boolean =
    Duration.between(now(), session.expiresAt).getSeconds <=
      refreshThresholdSeconds

  private def authenticateInternal()(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, LuxmedSession] =
    passwordGrant() match
      case Left(error)                  => Left(error)
      case Right((oauth, oauthCookies)) =>
        state = ClientSessionState.PendingBootstrap(
          expectedRefreshToken = None,
          oauth = oauth,
          cookies = oauthCookies
        )
        completeBootstrap(None, oauth, oauthCookies)

  private def refreshAndBootstrap(
      oldSession: LuxmedSession
  )(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, LuxmedSession] =
    refreshGrant(oldSession.refreshToken) match
      case Left(_: LuxmedError.ApiRejected) =>
        reauthenticateSession()
      case Left(LuxmedError.AuthFailed) =>
        reauthenticateSession()
      case Left(LuxmedError.SessionExpired) =>
        reauthenticateSession()
      case Left(error)                    => Left(error)
      case Right((oauth, refreshCookies)) =>
        val mergedCookies = oldSession.cookies.merge(refreshCookies.toList)
        state = ClientSessionState.PendingBootstrap(
          expectedRefreshToken = Some(oldSession.refreshToken),
          oauth = oauth,
          cookies = mergedCookies
        )
        completeBootstrap(
          Some(oldSession.refreshToken),
          oauth,
          mergedCookies
        )

  private def completeBootstrap(
      expectedRefreshToken: Option[Secret],
      oauth: OAuthTokens,
      cookies: CookieJar
  )(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, LuxmedSession] =
    bootstrapNewPortal(oauth.accessToken, cookies) match
      case Left(error)                   => Left(error)
      case Right((newCookies, jwtToken)) =>
        val session = LuxmedSession(
          accessToken = oauth.accessToken,
          tokenType = oauth.tokenType,
          refreshToken = oauth.refreshToken,
          expiresAt = now().plusSeconds(oauth.expiresIn.toLong),
          jwtToken = jwtToken,
          cookies = newCookies
        )
        state = ClientSessionState.PendingPersistence(
          expectedRefreshToken,
          session
        )
        persistSession(expectedRefreshToken, session)

  private def persistSession(
      expectedRefreshToken: Option[Secret],
      session: LuxmedSession
  ): Either[LuxmedError, LuxmedSession] =
    store.replace(expectedRefreshToken, session) match
      case Right(()) =>
        state = ClientSessionState.Ready(session)
        Right(session)
      case Left(SessionStoreError.ConcurrentModification) =>
        state = ClientSessionState.Unloaded
        Left(
          LuxmedError.PersistenceFailed(
            lmbot.backend.config.SafeDiagnostic("CAS conflict")
          )
        )
      case Left(SessionStoreError.Unavailable(m)) =>
        state =
          ClientSessionState.PendingPersistence(expectedRefreshToken, session)
        Left(
          LuxmedError.PersistenceFailed(
            lmbot.backend.config.SafeDiagnostic(m)
          )
        )

  private def runOperation[A](
      op: (AccountGatePermit, LuxmedSession) => Either[LuxmedError, A],
      session: LuxmedSession
  )(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, A] =
    op(permit, session) match
      case Left(LuxmedError.SessionExpired) => reauthenticateAfterExpiry(op)
      case other                            => other

  private def reauthenticateAfterExpiry[A](
      op: (AccountGatePermit, LuxmedSession) => Either[LuxmedError, A]
  )(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, A] =
    reauthenticateSession() match
      case Left(error)         => Left(error)
      case Right(freshSession) => op(permit, freshSession)

  private def reauthenticateSession()(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, LuxmedSession] =
    state = ClientSessionState.Unloaded
    store.clear() match
      case Left(error) => Left(toLuxmedError(error))
      case Right(())   => authenticateInternal()

  private def toLuxmedError(error: SessionStoreError): LuxmedError =
    error match
      case SessionStoreError.Unavailable(message) =>
        LuxmedError.PersistenceFailed(
          lmbot.backend.config.SafeDiagnostic(message)
        )
      case SessionStoreError.ConcurrentModification =>
        LuxmedError.PersistenceFailed(
          lmbot.backend.config.SafeDiagnostic("CAS conflict")
        )

  private def passwordGrant()(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, (OAuthTokens, CookieJar)] =
    for
      resp <- transport.oldApiPostForm(
        LuxmedEndpoint.Token,
        Map(
          "client_id" -> "Android",
          "grant_type" -> "password",
          "username" -> credentials.username,
          "password" -> credentials.password.value
        )
      )
      tokens <- decodeJson[OAuthTokens](resp.body).left.map: msg =>
        LuxmedError.DecodeFailed(LuxmedRedaction.safe(msg))
      oauthCookies = CookieJar(resp.cookies*)
    yield (tokens, oauthCookies)

  private def refreshGrant(
      refreshToken: Secret
  )(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, (OAuthTokens, CookieJar)] =
    for
      resp <- transport.oldApiPostForm(
        LuxmedEndpoint.Token,
        Map(
          "client_id" -> "Android",
          "grant_type" -> "refresh_token",
          "refresh_token" -> refreshToken.value
        )
      )
      tokens <- decodeJson[OAuthTokens](resp.body).left.map: msg =>
        LuxmedError.DecodeFailed(LuxmedRedaction.safe(msg))
      oauthCookies = CookieJar(resp.cookies*)
    yield (tokens, oauthCookies)

  private def bootstrapNewPortal(
      accessToken: Secret,
      cookies: CookieJar
  )(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, (CookieJar, Secret)] =
    for
      loginResp <- transport.newApiBootstrapGet(
        LuxmedEndpoint.LogInToApp,
        accessToken,
        cookies,
        Map("app" -> "search", "client" -> "3", "lang" -> "pl")
      )
      loginCookies = cookies.merge(loginResp.cookies)
      // Add GlobalLang cookie as the reference implementation does
      loginCookiesWithLang = loginCookies.merge(
        List("GlobalLang" -> Secret("pl"))
      )
      pageResp <- transport.newApiBootstrapGet(
        LuxmedEndpoint.ReservationPage,
        accessToken,
        loginCookiesWithLang
      )
      mergedCookies = loginCookiesWithLang.merge(pageResp.cookies)
      // Extract JWT from multiple sources, matching dyrkin/luxmed-bot:
      // 1. Cookies (Authorization-Token set as cookie)
      // 2. LogInToApp response header
      // 3. ReservationPage response header
      // 4. Fall back to OAuth access token (matches dyrkin behavior)
      jwtToken <- mergedCookies.get("Authorization-Token") match
        case Some(token) => Right(Secret(token.value))
        case None        =>
          loginResp.jwtHeader match
            case Some(jwt) =>
              Right(Secret(jwt.value.stripPrefix("Bearer ").trim))
            case None =>
              pageResp.jwtHeader match
                case Some(jwt) =>
                  Right(Secret(jwt.value.stripPrefix("Bearer ").trim))
                case None =>
                  Left(
                    LuxmedError.ProtocolViolation(
                      SafeDiagnostic(
                        "Authorization-Token missing from cookies, headers, and body"
                      )
                    )
                  )
    yield (mergedCookies, jwtToken)

  private def decodeJson[A](json: String)(using
      codec: JsonValueCodec[A]
  ): Either[String, A] =
    try Right(readFromString[A](json))
    catch case _: Exception => Left("Malformed JSON response")

  private def decodeBody[A](json: String)(using
      codec: JsonValueCodec[A]
  ): Either[LuxmedError, A] =
    decodeJson[A](json).left.map: msg =>
      LuxmedError.DecodeFailed(LuxmedRedaction.safe(msg))

  // -- Dictionary operations (Task 6) --

  def cities()(using Async): Either[LuxmedError, List[City]] =
    given JsonValueCodec[List[City]] = JsonCodecMaker.make
    withSession: (permit, session) =>
      given AccountGatePermit = permit
      transport
        .newApiGet(LuxmedEndpoint.Cities, Map.empty, session)
        .flatMap: resp =>
          decodeBody[List[City]](resp.body)

  def serviceVariants()(using
      Async
  ): Either[LuxmedError, List[ServiceVariant]] =
    withSession: (permit, session) =>
      given AccountGatePermit = permit
      transport
        .newApiGet(
          LuxmedEndpoint.ServiceVariantsGroups,
          Map.empty,
          session
        )
        .flatMap: resp =>
          decodeBody[List[ServiceVariant]](resp.body)

  def facilitiesAndDoctors(
      cityId: CityId,
      serviceVariantId: ServiceVariantId
  )(using Async): Either[LuxmedError, FacilitiesAndDoctors] =
    withSession: (permit, session) =>
      given AccountGatePermit = permit
      transport
        .newApiGet(
          LuxmedEndpoint.FacilitiesAndDoctors,
          Map(
            "cityId" -> cityId.value.toString,
            "serviceVariantId" -> serviceVariantId.value.toString
          ),
          session
        )
        .flatMap: resp =>
          decodeBody[FacilitiesAndDoctors](resp.body)

  // -- Terms search (Task 6) --

  def searchTerms(
      query: TermsQuery
  )(using Async): Either[LuxmedError, TermsResponse] =
    withSession: (permit, session) =>
      given AccountGatePermit = permit
      val params = Map.newBuilder[String, String]
      params += "searchPlace.id" -> query.cityId.toString
      params += "searchPlace.type" -> "0"
      params += "serviceVariantId" -> query.serviceVariantId.value.toString
      params += "languageId" -> query.languageId.toString
      params += "searchDateFrom" -> query.searchDateFrom.toString
      params += "searchDateTo" -> query.searchDateTo.toString
      params += "searchDatePreset" -> query.searchDatePreset.toString
      params += "processId" -> query.processId.toString
      params += "serviceVariantSource" -> "0"
      params += "nextSearch" -> "false"
      params += "searchByMedicalSpecialist" -> "false"
      params += "delocalized" -> "false"
      query.facilityIds.foreach: id =>
        params += "facilitiesIds" -> id.value.toString
      query.doctorIds.foreach: id =>
        params += "doctorsIds" -> id.value.toString
      transport
        .newApiGet(LuxmedEndpoint.TermsIndex, params.result(), session)
        .flatMap: resp =>
          decodeBody[TermsResponse](resp.body)

  // -- XSRF token (Task 7) --

  def getXsrfToken()(using
      Async
  ): Either[LuxmedError, (XsrfToken, CookieJar)] =
    withSession: (permit, session) =>
      given AccountGatePermit = permit
      transport
        .newApiGet(LuxmedEndpoint.ForgeryToken, Map.empty, session)
        .flatMap: resp =>
          decodeBody[XsrfToken](resp.body).map: token =>
            (token, session.cookies.merge(resp.cookies))

  // -- Reservation primitives (Task 7) --

  def lockTerm(
      request: LockTermRequest,
      xsrfToken: XsrfToken,
      extraCookies: CookieJar
  )(using Async): Either[LuxmedError, LockTermResponse] =
    withSession: (permit, session) =>
      given AccountGatePermit = permit
      val jsonBody = writeToString(request)
      transport
        .newApiPost(
          LuxmedEndpoint.LockTerm,
          jsonBody,
          session,
          xsrfToken = Some(xsrfToken.token),
          extraCookies = extraCookies
        )
        .flatMap: resp =>
          decodeBody[LockTermResponse](resp.body)

  def confirm(
      request: ConfirmRequest,
      xsrfToken: XsrfToken,
      extraCookies: CookieJar
  )(using Async): Either[LuxmedError, ConfirmResponse] =
    withSession: (permit, session) =>
      given AccountGatePermit = permit
      val jsonBody = writeToString(request)
      transport
        .newApiPost(
          LuxmedEndpoint.ConfirmTerm,
          jsonBody,
          session,
          xsrfToken = Some(xsrfToken.token),
          extraCookies = extraCookies
        )
        .flatMap: resp =>
          decodeBody[ConfirmResponse](resp.body)

  def releaseTerm(
      reservationId: ReservationId,
      xsrfToken: XsrfToken,
      extraCookies: CookieJar
  )(using Async): Either[LuxmedError, Unit] =
    withSession: (permit, session) =>
      given AccountGatePermit = permit
      transport
        .newApiPost(
          LuxmedEndpoint.ReleaseTerm,
          body = "{}",
          session,
          xsrfToken = Some(xsrfToken.token),
          extraCookies = extraCookies,
          params = Map("reservationId" -> reservationId.value.toString)
        )
        .flatMap: resp =>
          // Release endpoint returns 200 with empty body on success
          if resp.body.trim.isEmpty then Right(())
          else
            // If there's a body, try decoding as error response
            resp.body.trim match
              case "true" | "false" => Right(()) // Some APIs return boolean
              case _                =>
                decodeBody[ConfirmResponse](resp.body).map(_ => ())

  // -- Conformance entrypoint (Task 9) --

  private[luxmed] def refreshNowForConformance()(using
      Async
  ): Either[LuxmedError, LuxmedSession] =
    gate.serialized:
      state match
        case ClientSessionState.Ready(session) =>
          refreshAndBootstrap(session)
        case ClientSessionState.Unloaded =>
          store.load() match
            case Left(error) => Left(toLuxmedError(error))
            case Right(None) =>
              Left(
                LuxmedError.ProtocolViolation(
                  SafeDiagnostic(
                    "No session to refresh — authenticate first"
                  )
                )
              )
            case Right(Some(session)) =>
              state = ClientSessionState.Ready(session)
              refreshAndBootstrap(session)
        case other =>
          Left(
            LuxmedError.ProtocolViolation(
              SafeDiagnostic(
                s"Unexpected session state for conformance refresh: $other"
              )
            )
          )
