package lmbot.backend

import lmbot.backend.db.UserRepo
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.Role

class UserRepoTest extends PostgresSuite:

  test("an empty database has no users"):
    assertEquals(UserRepo(xa).count(), 0L)

  test("an inserted user can be found by username and by id"):
    val repo = UserRepo(xa)
    val stored = repo.insert("krzysiek", "Krzysiek", "hash-1", Role.Admin)

    assertEquals(repo.count(), 1L)
    assertEquals(repo.findByUsername("krzysiek").map(_.id), Some(stored.id))
    assertEquals(repo.findById(stored.id).map(_.username), Some("krzysiek"))
    assertEquals(stored.role, "admin")
    assertEquals(stored.disabled, false)
    assertEquals(stored.telegramChatId, None)

  test("usernames are unique"):
    val repo = UserRepo(xa)
    repo.insert("krzysiek", "Krzysiek", "hash-1", Role.Admin)
    intercept[Exception]:
      repo.insert("krzysiek", "Impostor", "hash-2", Role.User)

  test("an unknown username yields None rather than throwing"):
    assertEquals(UserRepo(xa).findByUsername("nobody"), None)
