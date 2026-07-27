package lmbot.backend.auth

import lmbot.backend.db.{SessionRepo, UserRepo, UserRow}
import lmbot.shared.api.ApiError
import lmbot.shared.domain.{Role, UserView}

import java.time.{Duration, OffsetDateTime}

case class AuthedUser(
  id: Long,
  username: String,
  displayName: String,
  role: Role,
  telegramLinked: Boolean
):
  def toView: UserView = UserView(id, username, displayName, role, telegramLinked)

/** All authentication and account-state policy. The HTTP layer asks questions
  * here and never decides anything itself (spec §6).
  */
class AuthService(
  users: UserRepo,
  sessions: SessionRepo,
  sessionTtl: Duration,
  now: () => OffsetDateTime
):

  def login(username: String, password: String): Either[ApiError, (UserView, String)] =
    for
      row  <- users.findByUsername(username).toRight(ApiError.Unauthorized)
      // Verify before checking `disabled` so that a disabled account and a
      // wrong password take the same work; the distinction is only revealed
      // to someone who already knows the password.
      _    <- Either.cond(Passwords.verify(row.passwordHash, password), (), ApiError.Unauthorized)
      _    <- Either.cond(!row.disabled, (), ApiError.Forbidden)
      user <- toAuthed(row)
    yield
      val token = Tokens.generate()
      sessions.insert(Tokens.hash(token), user.id, now().plus(sessionTtl))
      (user.toView, token)

  def authenticate(token: Option[String]): Either[ApiError, AuthedUser] =
    for
      raw     <- token.filter(_.nonEmpty).toRight(ApiError.Unauthorized)
      session <- sessions.find(Tokens.hash(raw)).toRight(ApiError.Unauthorized)
      _       <- Either.cond(session.expiresAt.isAfter(now()), (), expire(Tokens.hash(raw)))
      row     <- users.findById(session.userId).toRight(ApiError.Unauthorized)
      _       <- Either.cond(!row.disabled, (), ApiError.Forbidden)
      user    <- toAuthed(row)
    yield user

  def logout(token: Option[String]): Unit =
    token.filter(_.nonEmpty).foreach(raw => sessions.delete(Tokens.hash(raw)))

  /** Drop the dead session on the way past, so expired rows do not accumulate
    * purely because nobody swept them.
    */
  private def expire(tokenHash: String): ApiError =
    sessions.delete(tokenHash)
    ApiError.Unauthorized

  private def toAuthed(row: UserRow): Either[ApiError, AuthedUser] =
    Role
      .fromString(row.role)
      .toRight(ApiError.Unexpected(s"user ${row.id} has unrecognised role"))
      .map: role =>
        AuthedUser(row.id, row.username, row.displayName, role, row.telegramChatId.isDefined)
