package lmbot.backend

import java.time.OffsetDateTime

import lmbot.backend.db.{AccountRepo, LuxmedAccountRow, UserRepo}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.{AccountId, Role}

class AccountRepoTest extends PostgresSuite:

  private var nextUser = 0
  private def anOwner(): Long =
    nextUser += 1
    UserRepo(xa)
      .insert(s"owner$nextUser", s"Owner $nextUser", "hash", Role.Admin)
      .id

  private val now = OffsetDateTime.now()

  test("reserveId allocates sequential IDs"):
    val repo = AccountRepo(xa)
    val id1 = repo.reserveId()
    val id2 = repo.reserveId()
    assertEquals(id2.value, id1.value + 1L)

  test("insert and findOwned round-trip"):
    val repo = AccountRepo(xa)
    val ownerId = anOwner()
    val accountId = repo.reserveId()
    val row = LuxmedAccountRow(
      id = accountId.value,
      ownerUserId = ownerId,
      label = "My Luxmed",
      encryptedUsername = "user@example.com",
      encryptedPassword = "enc-pass-1",
      encryptedDeviceUuid = "enc-device-1",
      encryptedSession = None,
      status = "active",
      statusReason = None,
      lastSuccessfulLogin = None,
      createdAt = now,
      updatedAt = now
    )

    repo.insert(row)
    val found = repo.findOwned(accountId, ownerId)
    assertEquals(found.map(_.label), Some("My Luxmed"))
    assertEquals(found.map(_.status), Some("active"))

  test("listOwned returns all accounts for an owner"):
    val repo = AccountRepo(xa)
    val ownerId = anOwner()
    val id1 = repo.reserveId()
    val id2 = repo.reserveId()
    repo.insert(
      LuxmedAccountRow(
        id1.value,
        ownerId,
        "A",
        "u1",
        "p1",
        "d1",
        None,
        "active",
        None,
        None,
        now,
        now
      )
    )
    repo.insert(
      LuxmedAccountRow(
        id2.value,
        ownerId,
        "B",
        "u2",
        "p2",
        "d2",
        None,
        "active",
        None,
        None,
        now,
        now
      )
    )

    assertEquals(repo.listOwned(ownerId).size, 2)

  test("accounts are isolated by owner"):
    val repo = AccountRepo(xa)
    val o1 = anOwner()
    val o2 = anOwner()
    val id = repo.reserveId()
    repo.insert(
      LuxmedAccountRow(
        id.value,
        o1,
        "A",
        "u",
        "p",
        "d",
        None,
        "active",
        None,
        None,
        now,
        now
      )
    )

    assertEquals(repo.findOwned(id, o2), None)
    assertEquals(repo.listOwned(o2).size, 0)

  test("deleteOwned removes the row and returns true"):
    val repo = AccountRepo(xa)
    val ownerId = anOwner()
    val id = repo.reserveId()
    repo.insert(
      LuxmedAccountRow(
        id.value,
        ownerId,
        "A",
        "u",
        "p",
        "d",
        None,
        "active",
        None,
        None,
        now,
        now
      )
    )

    assert(repo.deleteOwned(id, ownerId))
    assertEquals(repo.findOwned(id, ownerId), None)

  test("deleteOwned returns false for non-existent ID"):
    val repo = AccountRepo(xa)
    val ownerId = anOwner()
    assert(!repo.deleteOwned(AccountId(999L), ownerId))

  test("deleteOwned returns false when not owned by caller"):
    val repo = AccountRepo(xa)
    val o1 = anOwner()
    val o2 = anOwner()
    val id = repo.reserveId()
    repo.insert(
      LuxmedAccountRow(
        id.value,
        o1,
        "A",
        "u",
        "p",
        "d",
        None,
        "active",
        None,
        None,
        now,
        now
      )
    )

    assert(!repo.deleteOwned(id, o2))
    assert(repo.findOwned(id, o1).isDefined)

  test("unique label per owner is enforced"):
    val repo = AccountRepo(xa)
    val ownerId = anOwner()
    val id1 = repo.reserveId()
    val id2 = repo.reserveId()
    repo.insert(
      LuxmedAccountRow(
        id1.value,
        ownerId,
        "Same",
        "u1",
        "p1",
        "d1",
        None,
        "active",
        None,
        None,
        now,
        now
      )
    )
    intercept[Exception]:
      repo.insert(
        LuxmedAccountRow(
          id2.value,
          ownerId,
          "Same",
          "u2",
          "p2",
          "d2",
          None,
          "active",
          None,
          None,
          now,
          now
        )
      )

  test("same Luxmed username allowed under different owners"):
    val repo = AccountRepo(xa)
    val o1 = anOwner()
    val o2 = anOwner()
    val id1 = repo.reserveId()
    val id2 = repo.reserveId()
    repo.insert(
      LuxmedAccountRow(
        id1.value,
        o1,
        "A",
        "same-user",
        "p1",
        "d1",
        None,
        "active",
        None,
        None,
        now,
        now
      )
    )
    repo.insert(
      LuxmedAccountRow(
        id2.value,
        o2,
        "B",
        "same-user",
        "p2",
        "d2",
        None,
        "active",
        None,
        None,
        now,
        now
      )
    )
    assertEquals(repo.listOwned(o1).size, 1)
    assertEquals(repo.listOwned(o2).size, 1)
