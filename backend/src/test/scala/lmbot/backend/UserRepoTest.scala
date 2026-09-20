package lmbot.backend

import java.time.OffsetDateTime

import com.augustnagro.magnum.{sql, transact}
import lmbot.backend.db.UserRepo
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.{Role, UserId}

class UserRepoTest extends PostgresSuite:

  test("an empty database has no users"):
    assertEquals(UserRepo(xa).count(), 0L)

  test("an inserted user can be found by username and by id"):
    val repo = UserRepo(xa)
    val stored = repo.insert("krzysiek", "Krzysiek", "hash-1", Role.Admin)

    assertEquals(repo.count(), 1L)
    assertEquals(repo.findByUsername("krzysiek").map(_.id), Some(stored.id))
    assertEquals(
      repo.findById(UserId(stored.id)).map(_.username),
      Some("krzysiek")
    )
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

  // -- Telegram linking (Plan 5) --

  test("a link code is listed only while it is unexpired"):
    val repo = UserRepo(xa)
    val stored = repo.insert("telegram", "Telegram", "hash", Role.User)
    val now = OffsetDateTime.parse("2026-08-10T07:00:00Z")
    repo.setTelegramLinkCode(
      UserId(stored.id),
      "argon2-hash",
      now.plusMinutes(15)
    )

    assertEquals(repo.listLinkableUsers(now).map(_.id), Seq(stored.id))
    assertEquals(repo.listLinkableUsers(now.plusMinutes(16)), Seq.empty)

    repo.clearTelegramLinkCode(UserId(stored.id))
    assertEquals(repo.listLinkableUsers(now), Seq.empty)

  test("the Telegram chat id can be set and cleared"):
    val repo = UserRepo(xa)
    val stored = repo.insert("telegram2", "Telegram", "hash", Role.User)

    repo.setTelegramChatId(UserId(stored.id), 12345L)
    assertEquals(
      repo.findById(UserId(stored.id)).flatMap(_.telegramChatId),
      Some(12345L)
    )

    repo.clearTelegramChatId(UserId(stored.id))
    assertEquals(
      repo.findById(UserId(stored.id)).flatMap(_.telegramChatId),
      None
    )

  test("listAdmins returns enabled admins only"):
    val repo = UserRepo(xa)
    val admin = repo.insert("admin", "Admin", "hash", Role.Admin)
    repo.insert("user", "User", "hash", Role.User)
    val disabled = repo.insert("admin2", "Admin 2", "hash", Role.Admin)
    transact(xa):
      sql"update users set disabled = true where id = ${disabled.id}".update
        .run()

    assertEquals(repo.listAdmins().map(_.id), Seq(admin.id))
