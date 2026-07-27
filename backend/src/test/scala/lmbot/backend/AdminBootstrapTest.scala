package lmbot.backend

import lmbot.backend.auth.{AdminBootstrap, Passwords}
import lmbot.backend.db.UserRepo
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.Role

class AdminBootstrapTest extends PostgresSuite:

  test("on an empty database the admin is created from the environment"):
    val users   = UserRepo(xa)
    val outcome = AdminBootstrap(users).run(Some("root"), Some("hunter2"))

    assertEquals(outcome, AdminBootstrap.Outcome.Created("root"))
    val created = users.findByUsername("root")
    assertEquals(created.map(_.role), Some("admin"))
    assert(created.exists(r => Passwords.verify(r.passwordHash, "hunter2")))

  test("the admin password is stored hashed, not in plaintext"):
    val users = UserRepo(xa)
    AdminBootstrap(users).run(Some("root"), Some("hunter2"))

    assert(!users.findByUsername("root").exists(_.passwordHash.contains("hunter2")))

  test("bootstrap is skipped when any user already exists"):
    val users = UserRepo(xa)
    users.insert("krzysiek", "Krzysiek", Passwords.hash("x"), Role.User)

    val outcome = AdminBootstrap(users).run(Some("root"), Some("hunter2"))

    assertEquals(outcome, AdminBootstrap.Outcome.SkippedUsersExist)
    assertEquals(users.findByUsername("root"), None)
    assertEquals(users.count(), 1L)

  test("missing credentials on an empty database are reported, not guessed at"):
    val users = UserRepo(xa)
    assertEquals(users.count(), 0L)

    assertEquals(AdminBootstrap(users).run(None, None), AdminBootstrap.Outcome.MissingCredentials)
    assertEquals(AdminBootstrap(users).run(Some("root"), None), AdminBootstrap.Outcome.MissingCredentials)
    assertEquals(AdminBootstrap(users).run(None, Some("hunter2")), AdminBootstrap.Outcome.MissingCredentials)
    assertEquals(users.count(), 0L)
