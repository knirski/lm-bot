package lmbot.backend

import java.sql.{Date => SqlDate, Time => SqlTime}
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

import lmbot.backend.db.{
  AccountRepo,
  LuxmedAccountRow,
  MonitorRepo,
  MonitorRow,
  UserRepo
}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.{AccountId, MonitorId, MonitorState, Role, UserId}

class MonitorRepoTest extends PostgresSuite:

  private var nextUser = 0
  private def anOwner(): UserId =
    nextUser += 1
    UserId(
      UserRepo(xa)
        .insert(s"owner$nextUser", s"Owner $nextUser", "hash", Role.Admin)
        .id
    )

  private def anAccount(ownerId: UserId): Long =
    val repo = AccountRepo(xa)
    val accountId = repo.reserveId()
    val now = OffsetDateTime.now()
    repo.insert(
      LuxmedAccountRow(
        id = accountId.value,
        ownerUserId = ownerId.value,
        label = "Test Account",
        encryptedUsername = "user@example.com",
        encryptedPassword = "enc-pass",
        encryptedDeviceUuid = "enc-device",
        encryptedSession = None,
        status = "active",
        statusReason = None,
        lastSuccessfulLogin = None,
        createdAt = now,
        updatedAt = now
      )
    )
    accountId.value

  private val now = OffsetDateTime.now()
  private val today = SqlDate.valueOf(java.time.LocalDate.now())
  private val timeFrom = SqlTime.valueOf(java.time.LocalTime.of(8, 0))
  private val timeTo = SqlTime.valueOf(java.time.LocalTime.of(16, 0))

  private def aMonitor(
      accountId: Long,
      id: Long,
      state: String = "active"
  ): MonitorRow =
    MonitorRow(
      id = id,
      luxmedAccountId = accountId,
      name = "Test Monitor",
      cityId = 100L,
      cityName = "Warsaw",
      serviceId = 200L,
      serviceName = "Konsultacja",
      facilityIds = List(10L, 20L),
      facilityNames = List("Facility A", "Facility B"),
      doctorIds = List(30L, 40L),
      doctorNames = List("Dr Smith", "Dr Jones"),
      dateFrom = today,
      dateTo = SqlDate.valueOf(java.time.LocalDate.now().plusDays(7)),
      timeFrom = timeFrom,
      timeTo = timeTo,
      daysOfWeek = 0b0011111, // Mon-Fri
      autoBook = false,
      intervalMinutes = 10,
      state = state,
      createdAt = now,
      updatedAt = now
    )

  test("insert and findOwned round-trip"):
    val repo = MonitorRepo(xa)
    val ownerId = anOwner()
    val accountId = anAccount(ownerId)
    val monitorId = repo.reserveId()
    val row = aMonitor(accountId, monitorId)

    repo.insert(row)
    val found = repo.findOwned(MonitorId(monitorId), ownerId)
    assertEquals(found.map(_.name), Some("Test Monitor"))
    assertEquals(found.map(_.state), Some("active"))

  test("facility and doctor arrays round-trip correctly"):
    val repo = MonitorRepo(xa)
    val ownerId = anOwner()
    val accountId = anAccount(ownerId)
    val monitorId = repo.reserveId()
    val row = aMonitor(accountId, monitorId).copy(
      facilityIds = List(10L, 20L, 30L, 40L),
      facilityNames = List("Facility, A", "Facility \"B\"", "Facility\\C", "")
    )

    repo.insert(row)
    val found = repo.findOwned(MonitorId(monitorId), ownerId).get
    assertEquals(found.facilityIds, List(10L, 20L, 30L, 40L))
    assertEquals(
      found.facilityNames,
      List("Facility, A", "Facility \"B\"", "Facility\\C", "")
    )
    assertEquals(found.doctorIds, List(30L, 40L))
    assertEquals(found.doctorNames, List("Dr Smith", "Dr Jones"))

  test("listOwned returns all monitors for an owner"):
    val repo = MonitorRepo(xa)
    val ownerId = anOwner()
    val accountId = anAccount(ownerId)
    val m1 = repo.reserveId()
    val m2 = repo.reserveId()
    repo.insert(aMonitor(accountId, m1, "active"))
    repo.insert(aMonitor(accountId, m2, "paused"))

    assertEquals(repo.listOwned(ownerId).size, 2)

  test("monitors are isolated by owner"):
    val repo = MonitorRepo(xa)
    val o1 = anOwner()
    val o2 = anOwner()
    val acc1 = anAccount(o1)
    val mid = repo.reserveId()
    repo.insert(aMonitor(acc1, mid))

    assertEquals(repo.findOwned(MonitorId(mid), o2), None)
    assertEquals(repo.listOwned(o2).size, 0)

  test("updateOwned modifies fields and returns updated row"):
    val repo = MonitorRepo(xa)
    val ownerId = anOwner()
    val accountId = anAccount(ownerId)
    val monitorId = repo.reserveId()
    repo.insert(aMonitor(accountId, monitorId, "active"))

    val updated =
      aMonitor(accountId, monitorId, "paused").copy(name = "Updated Monitor")
    val result = repo.updateOwned(updated, ownerId)
    assertEquals(result.map(_.name), Some("Updated Monitor"))
    assertEquals(result.map(_.state), Some("paused"))

  test("updateOwned returns None when monitor does not belong to owner"):
    val repo = MonitorRepo(xa)
    val o1 = anOwner()
    val o2 = anOwner()
    val acc1 = anAccount(o1)
    val mid = repo.reserveId()
    repo.insert(aMonitor(acc1, mid))

    val result = repo.updateOwned(aMonitor(acc1, mid, "paused"), o2)
    assertEquals(result, None)

  test("transitionOwned changes state when current state matches"):
    val repo = MonitorRepo(xa)
    val ownerId = anOwner()
    val accountId = anAccount(ownerId)
    val mid = repo.reserveId()
    repo.insert(aMonitor(accountId, mid, "active"))

    val result = repo.transitionOwned(
      MonitorId(mid),
      AccountId(accountId),
      ownerId,
      List(MonitorState.Active, MonitorState.Paused),
      MonitorState.Completed
    )
    assertEquals(result.map(_.state), Some("completed"))

  test("transitionOwned returns None when current state not in expected set"):
    val repo = MonitorRepo(xa)
    val ownerId = anOwner()
    val accountId = anAccount(ownerId)
    val mid = repo.reserveId()
    repo.insert(aMonitor(accountId, mid, "failed"))

    val result = repo.transitionOwned(
      MonitorId(mid),
      AccountId(accountId),
      ownerId,
      List(MonitorState.Active, MonitorState.Paused),
      MonitorState.Completed
    )
    assertEquals(result, None)

  test("transitionOwned respects owner isolation"):
    val repo = MonitorRepo(xa)
    val o1 = anOwner()
    val o2 = anOwner()
    val acc1 = anAccount(o1)
    val mid = repo.reserveId()
    repo.insert(aMonitor(acc1, mid, "active"))

    val result =
      repo.transitionOwned(
        MonitorId(mid),
        AccountId(acc1),
        o2,
        List(MonitorState.Active),
        MonitorState.Completed
      )
    assertEquals(result, None)

  test("deleteOwned removes the row and returns true"):
    val repo = MonitorRepo(xa)
    val ownerId = anOwner()
    val accountId = anAccount(ownerId)
    val mid = repo.reserveId()
    repo.insert(aMonitor(accountId, mid))

    assert(repo.deleteOwned(MonitorId(mid), ownerId))
    assertEquals(repo.findOwned(MonitorId(mid), ownerId), None)

  test("deleteOwned returns false when not owned"):
    val repo = MonitorRepo(xa)
    val o1 = anOwner()
    val o2 = anOwner()
    val acc1 = anAccount(o1)
    val mid = repo.reserveId()
    repo.insert(aMonitor(acc1, mid))

    assert(!repo.deleteOwned(MonitorId(mid), o2))
    assert(repo.findOwned(MonitorId(mid), o1).isDefined)

  test("deleting an account cascades to its monitors"):
    val accountRepo = AccountRepo(xa)
    val monRepo = MonitorRepo(xa)
    val ownerId = anOwner()
    val accountId = anAccount(ownerId)
    val mid = monRepo.reserveId()
    monRepo.insert(aMonitor(accountId, mid))

    accountRepo.deleteOwned(AccountId(accountId), ownerId)

    assertEquals(monRepo.findOwned(MonitorId(mid), ownerId), None)

  // -- Engine operations (Plan 5) --

  test("findById finds a monitor without an owner scope"):
    val repo = MonitorRepo(xa)
    val accountId = anAccount(anOwner())
    val mid = repo.reserveId()
    repo.insert(aMonitor(accountId, mid))

    assertEquals(repo.findById(MonitorId(mid)).map(_.id), Some(mid))
    assertEquals(repo.findById(MonitorId(mid + 1)), None)

  test("listActive returns only active monitors, across owners"):
    val repo = MonitorRepo(xa)
    val a1 = anAccount(anOwner())
    val a2 = anAccount(anOwner())
    val active1 = repo.reserveId()
    val active2 = repo.reserveId()
    val paused = repo.reserveId()
    repo.insert(aMonitor(a1, active1, "active"))
    repo.insert(aMonitor(a1, paused, "paused"))
    repo.insert(aMonitor(a2, active2, "active"))

    assertEquals(repo.listActive().map(_.id).toSet, Set(active1, active2))

  test("recordCheck writes the last-check fields without touching updated_at"):
    val repo = MonitorRepo(xa)
    val accountId = anAccount(anOwner())
    val mid = repo.reserveId()
    repo.insert(aMonitor(accountId, mid))
    val before = repo.findById(MonitorId(mid)).get.updatedAt
    val checkedAt = now.plusMinutes(5).truncatedTo(ChronoUnit.MICROS)

    repo.recordCheck(MonitorId(mid), checkedAt, "Found 2 new slots")

    val after = repo.findById(MonitorId(mid)).get
    assertEquals(after.lastCheckAt.map(_.toInstant), Some(checkedAt.toInstant))
    assertEquals(after.lastCheckSummary, Some("Found 2 new slots"))
    assertEquals(after.updatedAt.toInstant, before.toInstant)

  test("complete transitions only from active"):
    val repo = MonitorRepo(xa)
    val accountId = anAccount(anOwner())
    val mid = repo.reserveId()
    repo.insert(aMonitor(accountId, mid, "active"))

    assertEquals(repo.complete(MonitorId(mid)).map(_.state), Some("completed"))
    assertEquals(repo.complete(MonitorId(mid)), None)

  test("fail transitions only from active"):
    val repo = MonitorRepo(xa)
    val accountId = anAccount(anOwner())
    val active = repo.reserveId()
    val alreadyFailed = repo.reserveId()
    repo.insert(aMonitor(accountId, active, "active"))
    repo.insert(aMonitor(accountId, alreadyFailed, "failed"))

    assertEquals(repo.fail(MonitorId(active)).map(_.state), Some("failed"))
    assertEquals(repo.fail(MonitorId(active)), None)
    assertEquals(repo.fail(MonitorId(alreadyFailed)), None)

  test("pauseAllForAccount pauses exactly that account's active monitors"):
    val repo = MonitorRepo(xa)
    val a1 = anAccount(anOwner())
    val a2 = anAccount(anOwner())
    val m1 = repo.reserveId()
    val m2 = repo.reserveId()
    val alreadyPaused = repo.reserveId()
    val other = repo.reserveId()
    repo.insert(aMonitor(a1, m1, "active"))
    repo.insert(aMonitor(a1, m2, "active"))
    repo.insert(aMonitor(a1, alreadyPaused, "paused"))
    repo.insert(aMonitor(a2, other, "active"))

    val affected = repo.pauseAllForAccount(AccountId(a1))

    assertEquals(affected.map(_.id).toSet, Set(m1, m2))
    assertEquals(repo.findById(MonitorId(m1)).map(_.state), Some("paused"))
    assertEquals(repo.findById(MonitorId(m2)).map(_.state), Some("paused"))
    assertEquals(repo.findById(MonitorId(other)).map(_.state), Some("active"))
