package lmbot.backend.luxmed

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import com.augustnagro.magnum.{Transactor, connect, sql, transact}
import lmbot.backend.config.Secret
import lmbot.backend.crypto.{
  AesGcm,
  EncryptedEnvelope,
  EncryptionContext,
  EncryptionPurpose
}
import lmbot.backend.luxmed.model.LuxmedSession
import lmbot.shared.domain.AccountId
import org.slf4j.LoggerFactory

/** Encrypted, owner-scoped PostgreSQL session store with refresh-token CAS. */
final class PostgresSessionStore(
    xa: Transactor,
    ownerId: Long,
    accountId: AccountId,
    crypto: AesGcm,
    // Test hook used to coordinate concurrent CAS attempts after the row read.
    afterRead: () => Unit = () => ()
) extends SessionStore:

  private val log = LoggerFactory.getLogger(getClass)
  private val rawAccountId = accountId.value
  private val context =
    EncryptionContext(ownerId, accountId, EncryptionPurpose.Session)

  private def unavailable: Left[SessionStoreError.Unavailable, Nothing] =
    Left(SessionStoreError.Unavailable("session persistence failed"))

  private def decodeStored(
      value: String
  ): Either[SessionStoreError, LuxmedSession] =
    for
      envelope <- EncryptedEnvelope
        .parse(value)
        .left
        .map(_ => SessionStoreError.Unavailable("session persistence failed"))
      plaintext <- crypto
        .decrypt(envelope, context)
        .left
        .map(_ => SessionStoreError.Unavailable("session persistence failed"))
      session <- SessionCodec
        .decode(plaintext.value)
        .left
        .map(_ => SessionStoreError.Unavailable("session persistence failed"))
    yield session

  private def refreshMatches(
      expected: Option[Secret],
      actual: Option[LuxmedSession]
  ): Boolean =
    (expected, actual) match
      case (None, None)                 => true
      case (Some(value), Some(session)) =>
        MessageDigest.isEqual(
          value.value.getBytes(StandardCharsets.UTF_8),
          session.refreshToken.value.getBytes(StandardCharsets.UTF_8)
        )
      case _ => false

  def load(): Either[SessionStoreError, Option[LuxmedSession]] =
    try
      connect(xa):
        sql"""select encrypted_session from luxmed_accounts
              where id = $rawAccountId and owner_user_id = $ownerId"""
          .query[Option[String]]
          .run()
          .headOption
          .flatten match
          case None        => Right(None)
          case Some(value) => decodeStored(value).map(Some(_))
    catch
      case error: Exception =>
        log.warn("Failed to load persisted LuxMed session", error)
        unavailable

  def replace(
      expectedRefreshToken: Option[Secret],
      updatedSession: LuxmedSession
  ): Either[SessionStoreError, Unit] =
    try
      connect(xa):
        val stored =
          sql"""select encrypted_session from luxmed_accounts
                where id = $rawAccountId and owner_user_id = $ownerId"""
            .query[Option[String]]
            .run()
            .headOption
            .flatten
        afterRead()
        val current = stored.flatMap(value => decodeStored(value).toOption)
        if stored.exists(value => decodeStored(value).isLeft) then
          Left(SessionStoreError.Unavailable("session persistence failed"))
        else if !refreshMatches(expectedRefreshToken, current) then
          Left(SessionStoreError.ConcurrentModification)
        else
          val encrypted =
            crypto.encrypt(SessionCodec.encode(updatedSession), context).render
          val changed = stored match
            case Some(previous) =>
              sql"""update luxmed_accounts
                    set encrypted_session = $encrypted, updated_at = now()
                    where id = $rawAccountId and owner_user_id = $ownerId
                      and encrypted_session = $previous""".update.run()
            case None =>
              sql"""update luxmed_accounts
                    set encrypted_session = $encrypted, updated_at = now()
                    where id = $rawAccountId and owner_user_id = $ownerId
                      and encrypted_session is null""".update.run()
          if changed == 1 then Right(())
          else Left(SessionStoreError.ConcurrentModification)
    catch
      case error: Exception =>
        log.warn("Failed to replace persisted LuxMed session", error)
        unavailable

  def clear(): Either[SessionStoreError, Unit] =
    try
      transact(xa):
        val changed = sql"""update luxmed_accounts
              set encrypted_session = null, updated_at = now()
              where id = $rawAccountId and owner_user_id = $ownerId""".update
          .run()
        if changed == 1 then Right(())
        else Left(SessionStoreError.Unavailable("session persistence failed"))
    catch
      case error: Exception =>
        log.warn("Failed to clear persisted LuxMed session", error)
        unavailable
