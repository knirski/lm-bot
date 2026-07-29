package lmbot.backend.luxmed

import gears.async.Async
import lmbot.backend.config.{SafeDiagnostic, Secret}
import lmbot.backend.luxmed.model.*
import lmbot.backend.luxmed.model.WireCodecs.given
import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  readFromString
}
import java.time.{Duration, Instant}

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
      case Left(error)  => Left(error)
      case Right(oauth) =>
        state = ClientSessionState.PendingBootstrap(
          expectedRefreshToken = None,
          oauth = oauth,
          cookies = CookieJar.empty
        )
        completeBootstrap(None, oauth, CookieJar.empty)

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
      case Left(error)  => Left(error)
      case Right(oauth) =>
        state = ClientSessionState.PendingBootstrap(
          expectedRefreshToken = Some(oldSession.refreshToken),
          oauth = oauth,
          cookies = oldSession.cookies
        )
        completeBootstrap(
          Some(oldSession.refreshToken),
          oauth,
          oldSession.cookies
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
  ): Either[LuxmedError, OAuthTokens] =
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
        LuxmedError.DecodeFailed(lmbot.backend.config.SafeDiagnostic(msg))
    yield tokens

  private def refreshGrant(
      refreshToken: Secret
  )(using
      permit: AccountGatePermit,
      async: Async
  ): Either[LuxmedError, OAuthTokens] =
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
        LuxmedError.DecodeFailed(lmbot.backend.config.SafeDiagnostic(msg))
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
        LuxmedEndpoint.LogInToApp,
        accessToken,
        cookies,
        Map("app" -> "search", "client" -> "3", "lang" -> "pl")
      )
      loginCookies = cookies.merge(loginResp.cookies)
      pageResp <- transport.newApiBootstrapGet(
        LuxmedEndpoint.ReservationPage,
        accessToken,
        loginCookies
      )
      mergedCookies = loginCookies.merge(pageResp.cookies)
      jwtToken <- pageResp.jwtHeader match
        case Some(jwt) =>
          val token = jwt.value.stripPrefix("Bearer ").trim
          Right(Secret(token))
        case None =>
          Left(
            LuxmedError.ProtocolViolation(
              SafeDiagnostic(
                "Authorization-Token missing"
              )
            )
          )
    yield (mergedCookies, jwtToken)

  private def decodeJson[A](json: String)(using
      codec: JsonValueCodec[A]
  ): Either[String, A] =
    try Right(readFromString[A](json))
    catch case _: Exception => Left("Malformed JSON response")
