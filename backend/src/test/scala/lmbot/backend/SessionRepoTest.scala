package lmbot.backend

import lmbot.backend.db.{SessionRepo, UserRepo}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.Role

import java.time.{Duration, OffsetDateTime}

class SessionRepoTest extends PostgresSuite:

  private def aUser(): Long =
    UserRepo(xa).insert("krzysiek", "Krzysiek", "hash-1", Role.Admin).id

  test("a stored session is retrievable by its token hash"):
    val repo = SessionRepo(xa)
    val userId = aUser()
    val expiry = OffsetDateTime.now().plusDays(7)

    repo.insert("token-hash-1", userId, expiry)

    val found = repo.find("token-hash-1")
    assertEquals(found.map(_.userId), Some(userId))

  test("an unknown token hash yields None"):
    assertEquals(SessionRepo(xa).find("nope"), None)

  test("deleting a session revokes it"):
    val repo = SessionRepo(xa)
    val userId = aUser()
    repo.insert("token-hash-1", userId, OffsetDateTime.now().plusDays(7))

    repo.delete("token-hash-1")

    assertEquals(repo.find("token-hash-1"), None)

  test("deleteExpired removes only sessions already past their expiry"):
    val repo = SessionRepo(xa)
    val userId = aUser()
    val now = OffsetDateTime.now()
    repo.insert("stale", userId, now.minus(Duration.ofMinutes(1)))
    repo.insert("fresh", userId, now.plusDays(7))

    val removed = repo.deleteExpired(now)

    assertEquals(removed, 1)
    assertEquals(repo.find("stale"), None)
    assert(repo.find("fresh").isDefined)

  test("deleting a user cascades to their sessions"):
    val sessions = SessionRepo(xa)
    val users = UserRepo(xa)
    val userId = aUser()
    sessions.insert("token-hash-1", userId, OffsetDateTime.now().plusDays(7))

    users.deleteById(userId)

    assertEquals(sessions.find("token-hash-1"), None)
