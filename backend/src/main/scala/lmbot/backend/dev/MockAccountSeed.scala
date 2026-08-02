package lmbot.backend.dev

import java.time.{Instant, ZoneOffset}
import java.util.UUID

import lmbot.backend.config.Secret
import lmbot.backend.crypto.{AesGcm, EncryptionContext, EncryptionPurpose}
import lmbot.backend.db.{AccountRepo, LuxmedAccountRow}
import lmbot.backend.luxmed.model.{LuxmedSession, TokenType}
import lmbot.backend.luxmed.{CookieJar, SessionCodec}
import lmbot.shared.domain.{AccountStatus, UserId}

/** Idempotently creates the safe account shown by the local mock API. */
object MockAccountSeed:

  val label = "Mock Luxmed"
  val username = "mock.patient@example.test"
  val password = "mock-password"
  private val deviceUuid =
    UUID.fromString("00000000-0000-4000-8000-000000000007")

  def ensure(
      owner: UserId,
      accounts: AccountRepo,
      crypto: AesGcm,
      now: () => Instant
  ): Unit =
    accounts.insertIfAbsent(
      owner,
      label,
      accountId =>
        val current = now()
        val timestamp = current.atOffset(ZoneOffset.UTC)
        val context = (purpose: EncryptionPurpose) =>
          EncryptionContext(owner, accountId, purpose)
        val session = LuxmedSession(
          accessToken = Secret("mock-access-token"),
          tokenType = TokenType.Bearer,
          refreshToken = Secret("mock-refresh-token"),
          expiresAt = current.plusSeconds(600),
          jwtToken = Secret("mock-jwt"),
          cookies = CookieJar("ASP.NET_SessionId" -> Secret("mock-session"))
        )
        LuxmedAccountRow(
          id = accountId.value,
          ownerUserId = owner.value,
          label = label,
          encryptedUsername = crypto
            .encrypt(username, context(EncryptionPurpose.Username))
            .render,
          encryptedPassword = crypto
            .encrypt(password, context(EncryptionPurpose.Password))
            .render,
          encryptedDeviceUuid = crypto
            .encrypt(deviceUuid.toString, context(EncryptionPurpose.DeviceId))
            .render,
          encryptedSession = Some(
            crypto
              .encrypt(
                SessionCodec.encode(session),
                context(EncryptionPurpose.Session)
              )
              .render
          ),
          status = AccountStatus.Active.wireName,
          statusReason = None,
          lastSuccessfulLogin = Some(timestamp),
          createdAt = timestamp,
          updatedAt = timestamp
        )
    )
    ()
