package lmbot.backend.luxmed

import gears.async.Async
import lmbot.backend.config.Secret
import lmbot.backend.luxmed.model.*
import lmbot.backend.luxmed.model.WireCodecs.given
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString

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
    store: SessionStore
):

  private val refreshThresholdSeconds = 300L

  private var state: ClientSessionState = ClientSessionState.Unloaded

  def authenticate()(using Async): Either[LuxmedError, LuxmedSession] =
    gate.serialized:
      authenticateInternal()

  def withSession[A](
      op: (AccountGatePermit, LuxmedSession) => Either[LuxmedError, A]
  )(using Async): Either[LuxmedError, A] =
    gate.serialized:
      given AccountGatePermit = summon[AccountGatePermit]
      ensureSession() match
        case Left(e)        => Left(e)
        case Right(session) =>
          op(summon[AccountGatePermit], session) match
            case Left(LuxmedError.SessionExpired) =>
              state = ClientSessionState.Unloaded
              ensureSession() match
                case Left(e2)     => Left(e2)
                case Right(fresh) =>
                  op(summon[AccountGatePermit], fresh)
            case other => other

  private def ensureSession()(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, LuxmedSession] =
    state match
      case ClientSessionState.Unloaded =>
        authenticateInternal()
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
    java.time.Duration
      .between(java.time.Instant.now(), session.expiresAt)
      .toSeconds < refreshThresholdSeconds

  private def authenticateInternal()(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, LuxmedSession] =
    for
      oauth <- passwordGrant()
      cookiesAndJwt <- bootstrapNewPortal(oauth.accessToken, CookieJar.empty)
      (cookies, jwtToken) = cookiesAndJwt
      expiresAt = java.time.Instant.now().plusSeconds(oauth.expiresIn.toLong)
      session = LuxmedSession(
        accessToken = oauth.accessToken,
        tokenType = oauth.tokenType,
        refreshToken = oauth.refreshToken,
        expiresAt = expiresAt,
        jwtToken = jwtToken,
        cookies = cookies
      )
      _ <- store
        .replace(None, session)
        .left
        .map:
          case SessionStoreError.Unavailable(m) =>
            LuxmedError.PersistenceFailed(m)
          case SessionStoreError.ConcurrentModification =>
            LuxmedError.PersistenceFailed("CAS conflict on initial login")
      _ = state = ClientSessionState.Ready(session)
    yield session

  private def refreshAndBootstrap(
      oldSession: LuxmedSession
  )(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, LuxmedSession] =
    for
      oauth <- refreshGrant(oldSession.refreshToken)
      cookiesAndJwt <- bootstrapNewPortal(oauth.accessToken, oldSession.cookies)
      (cookies, jwtToken) = cookiesAndJwt
      expiresAt = java.time.Instant.now().plusSeconds(oauth.expiresIn.toLong)
      newSession = LuxmedSession(
        accessToken = oauth.accessToken,
        tokenType = oauth.tokenType,
        refreshToken = oauth.refreshToken,
        expiresAt = expiresAt,
        jwtToken = jwtToken,
        cookies = cookies
      )
      _ <- store
        .replace(Some(oldSession.refreshToken), newSession)
        .left
        .map:
          case SessionStoreError.Unavailable(m) =>
            LuxmedError.PersistenceFailed(m)
          case SessionStoreError.ConcurrentModification =>
            LuxmedError.PersistenceFailed("CAS conflict on refresh")
      _ = state = ClientSessionState.Ready(newSession)
    yield newSession

  private def completeBootstrap(
      expectedRefreshToken: Option[Secret],
      oauth: OAuthTokens,
      cookies: CookieJar
  )(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, LuxmedSession] =
    for
      cookiesAndJwt <- bootstrapNewPortal(oauth.accessToken, cookies)
      (newCookies, jwtToken) = cookiesAndJwt
      expiresAt = java.time.Instant.now().plusSeconds(oauth.expiresIn.toLong)
      session = LuxmedSession(
        accessToken = oauth.accessToken,
        tokenType = oauth.tokenType,
        refreshToken = oauth.refreshToken,
        expiresAt = expiresAt,
        jwtToken = jwtToken,
        cookies = newCookies
      )
      _ <- persistSession(expectedRefreshToken, session)
    yield session

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
        Left(LuxmedError.PersistenceFailed("CAS conflict"))
      case Left(SessionStoreError.Unavailable(m)) =>
        state =
          ClientSessionState.PendingPersistence(expectedRefreshToken, session)
        Left(LuxmedError.PersistenceFailed(m))

  private def passwordGrant()(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, OAuthTokens] =
    for
      resp <- transport.oldApiPostForm(
        "token",
        Map(
          "client_id" -> "Android",
          "grant_type" -> "password",
          "username" -> credentials.username,
          "password" -> credentials.password.value
        )
      )
      tokens <- decodeJson[OAuthTokens](resp.body).left.map: msg =>
        LuxmedError.DecodeFailed(msg)
    yield tokens

  private def refreshGrant(
      refreshToken: Secret
  )(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, OAuthTokens] =
    for
      resp <- transport.oldApiPostForm(
        "token",
        Map(
          "client_id" -> "Android",
          "grant_type" -> "refresh_token",
          "refresh_token" -> refreshToken.value
        )
      )
      tokens <- decodeJson[OAuthTokens](resp.body).left.map: msg =>
        LuxmedError.DecodeFailed(msg)
    yield tokens

  private def bootstrapNewPortal(
      accessToken: Secret,
      cookies: CookieJar
  )(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, (CookieJar, Secret)] =
    for
      loginResp <- transport.newApiBootstrapGet(
        "Account/LogInToApp?app=search&client=3&lang=pl",
        accessToken,
        cookies
      )
      loginCookies = cookies.merge(loginResp.cookies)
      pageResp <- transport.newApiBootstrapGet(
        "NewPortal/Page/Reservation",
        accessToken,
        loginCookies
      )
      mergedCookies = loginCookies.merge(pageResp.cookies)
      jwtToken <- pageResp.jwtHeader match
        case Some(jwt) =>
          val token = jwt.value.stripPrefix("Bearer ").trim
          Right(Secret(token))
        case None =>
          Left(LuxmedError.ProtocolViolation("Authorization-Token missing"))
    yield (mergedCookies, jwtToken)

  private def decodeJson[A](json: String)(using
      codec: com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[A]
  ): Either[String, A] =
    try Right(readFromString[A](json))
    catch case e: Exception => Left(e.getMessage.nn)
