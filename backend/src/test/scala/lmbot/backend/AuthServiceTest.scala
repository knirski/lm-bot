package lmbot.backend

import java.time.OffsetDateTime

import scala.concurrent.duration.*

import com.augustnagro.magnum.{sql, transact}
import lmbot.backend.auth.{AuthService, Passwords, Tokens}
import lmbot.backend.db.{SessionRepo, UserRepo}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.api.ApiError
import lmbot.shared.domain.Role

class AuthServiceTest extends PostgresSuite:

  private val ttl = 7.days

  private def service(
      now: () => OffsetDateTime = () => OffsetDateTime.now()
  ): AuthService =
    AuthService(UserRepo(xa), SessionRepo(xa), ttl, now)

  private def aUser(
      username: String = "krzysiek",
      password: String = "s3cret",
      role: Role = Role.User
  ): Long =
    UserRepo(xa).insert(username, "Krzysiek", Passwords.hash(password), role).id

  test("login with correct credentials returns the user and a token"):
    aUser()
    val result = service().login("krzysiek", "s3cret")

    result match
      case Right((view, token)) =>
        assertEquals(view.username, "krzysiek")
        assertEquals(view.role, Role.User)
        assert(token.nonEmpty)
      case Left(e) => fail(s"expected success, got $e")

  test("login stores only the hash of the token, never the token"):
    aUser()
    val Right((_, token)) = service().login("krzysiek", "s3cret"): @unchecked

    assert(SessionRepo(xa).find(Tokens.hash(token)).isDefined)
    assertEquals(SessionRepo(xa).find(token), None)

  test("login with a wrong password is Unauthorized"):
    aUser()
    assertEquals(
      service().login("krzysiek", "wrong"),
      Left(ApiError.Unauthorized)
    )

  test("login for an unknown user is Unauthorized, not NotFound"):
    // Distinguishing the two would let an attacker enumerate usernames.
    assertEquals(
      service().login("ghost", "s3cret"),
      Left(ApiError.Unauthorized)
    )

  test("a disabled user cannot log in even with the right password"):
    val id = aUser()
    transact(xa):
      sql"update users set disabled = true where id = $id".update.run()

    assertEquals(
      service().login("krzysiek", "s3cret"),
      Left(ApiError.Forbidden)
    )

  test("authenticate accepts a token minted by login"):
    aUser(role = Role.Admin)
    val svc = service()
    val Right((_, token)) = svc.login("krzysiek", "s3cret"): @unchecked

    svc.authenticate(Some(token)) match
      case Right(user) =>
        assertEquals(user.username, "krzysiek")
        assertEquals(user.role, Role.Admin)
      case Left(e) => fail(s"expected success, got $e")

  test("authenticate rejects a missing, unknown or malformed token"):
    val svc = service()
    assertEquals(svc.authenticate(None), Left(ApiError.Unauthorized))
    assertEquals(svc.authenticate(Some("")), Left(ApiError.Unauthorized))
    assertEquals(
      svc.authenticate(Some("not-a-real-token")),
      Left(ApiError.Unauthorized)
    )

  test("authenticate rejects an expired session and cleans it up"):
    aUser()
    val issued = OffsetDateTime.now()
    val svc = service(() => issued)
    val Right((_, token)) = svc.login("krzysiek", "s3cret"): @unchecked

    // Same repos, but "now" is past the TTL.
    val later = AuthService(
      UserRepo(xa),
      SessionRepo(xa),
      ttl,
      () => issued.plusNanos(ttl.toNanos).plusMinutes(1)
    )
    assertEquals(later.authenticate(Some(token)), Left(ApiError.Unauthorized))
    assertEquals(SessionRepo(xa).find(Tokens.hash(token)), None)

  test("authenticate rejects a token whose user was disabled after login"):
    val id = aUser()
    val svc = service()
    val Right((_, token)) = svc.login("krzysiek", "s3cret"): @unchecked

    transact(xa):
      sql"update users set disabled = true where id = $id".update.run()

    assertEquals(svc.authenticate(Some(token)), Left(ApiError.Forbidden))

  test("logout revokes the session"):
    aUser()
    val svc = service()
    val Right((_, token)) = svc.login("krzysiek", "s3cret"): @unchecked

    svc.logout(Some(token))

    assertEquals(svc.authenticate(Some(token)), Left(ApiError.Unauthorized))

  test("logout of an absent or unknown token is a no-op"):
    val svc = service()
    svc.logout(None)
    svc.logout(Some("never-existed"))
    // Reaching here without an exception is the assertion.
    assert(true)
