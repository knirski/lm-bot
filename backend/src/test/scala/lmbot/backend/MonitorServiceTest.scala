package lmbot.backend

import java.time.{DayOfWeek, LocalDate, LocalTime, OffsetDateTime}

import lmbot.backend.db.{
  AccountRepo,
  LuxmedAccountRow,
  MonitorRepo,
  MonitorRow,
  UserRepo
}
import lmbot.backend.monitor.MonitorService
import lmbot.backend.support.PostgresSuite
import lmbot.shared.api.ApiError
import lmbot.shared.domain.{
  AccountId,
  MonitorDraft,
  MonitorId,
  MonitorState,
  NamedId,
  Role
}

class MonitorServiceTest extends PostgresSuite:

  private var nextUser = 0

  private def owner(prefix: String = "owner"): Long =
    nextUser += 1
    UserRepo(xa)
      .insert(s"$prefix-$nextUser", s"Owner $nextUser", "hash", Role.Admin)
      .id

  private def insertAccount(ownerId: Long, label: String = "Main"): AccountId =
    val repo = AccountRepo(xa)
    val id = repo.reserveId()
    val now = OffsetDateTime.now()
    repo.insert(
      LuxmedAccountRow(
        id.value,
        ownerId,
        label,
        "encrypted-username",
        "encrypted-password",
        "encrypted-device",
        None,
        "active",
        None,
        None,
        now,
        now
      )
    )
    id

  /** Seeds a monitor row directly (bypassing the service) so tests can put a
    * monitor into a state the service itself cannot reach yet (`completed` /
    * `failed` are set by the execution loop that Plan 5 builds).
    */
  private def insertMonitorRow(accountId: AccountId, state: String): MonitorId =
    val repo = MonitorRepo(xa)
    val id = repo.reserveId()
    val now = OffsetDateTime.now()
    repo.insert(
      MonitorRow(
        id,
        accountId.value,
        "Seed monitor",
        1L,
        "Warsaw",
        2L,
        "Dermatology",
        Nil,
        Nil,
        Nil,
        Nil,
        java.sql.Date.valueOf(LocalDate.parse("2026-08-01")),
        java.sql.Date.valueOf(LocalDate.parse("2026-08-31")),
        java.sql.Time.valueOf(LocalTime.parse("08:00")),
        java.sql.Time.valueOf(LocalTime.parse("16:00")),
        0x7f.toShort,
        false,
        10,
        state,
        now,
        now
      )
    )
    MonitorId(id)

  private def service(): MonitorService =
    MonitorService(MonitorRepo(xa), AccountRepo(xa))

  private def draft(
      accountId: AccountId,
      name: String = "Dermatologist",
      city: NamedId = NamedId(1L, "Warsaw"),
      service: NamedId = NamedId(2L, "Dermatology"),
      facilities: List[NamedId] = Nil,
      doctors: List[NamedId] = Nil,
      dateFrom: LocalDate = LocalDate.parse("2026-08-01"),
      dateTo: LocalDate = LocalDate.parse("2026-08-31"),
      timeFrom: LocalTime = LocalTime.parse("08:00"),
      timeTo: LocalTime = LocalTime.parse("16:00"),
      daysOfWeek: List[DayOfWeek] =
        List(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
      autoBook: Boolean = false,
      intervalMinutes: Int = 10
  ): MonitorDraft =
    MonitorDraft(
      accountId,
      name,
      city,
      service,
      facilities,
      doctors,
      dateFrom,
      dateTo,
      timeFrom,
      timeTo,
      daysOfWeek,
      autoBook,
      intervalMinutes
    )

  // -- validation ------------------------------------------------------

  test("default interval of 10 is accepted"):
    val ownerId = owner()
    val accountId = insertAccount(ownerId)
    val result = service().create(ownerId, draft(accountId))
    assertEquals(result.map(_.intervalMinutes), Right(10))

  test("interval of 5 (the floor) is accepted"):
    val ownerId = owner()
    val accountId = insertAccount(ownerId)
    val result =
      service().create(ownerId, draft(accountId, intervalMinutes = 5))
    assertEquals(result.map(_.intervalMinutes), Right(5))

  test("interval of 4 is rejected as a validation error"):
    val ownerId = owner()
    val accountId = insertAccount(ownerId)
    val result =
      service().create(ownerId, draft(accountId, intervalMinutes = 4))
    assertEquals(
      result,
      Left(ApiError.Validation("Interval must be at least 5 minutes."))
    )

  test("dateFrom after dateTo is rejected as a validation error"):
    val ownerId = owner()
    val accountId = insertAccount(ownerId)
    val result = service().create(
      ownerId,
      draft(
        accountId,
        dateFrom = LocalDate.parse("2026-08-31"),
        dateTo = LocalDate.parse("2026-08-01")
      )
    )
    assertEquals(
      result,
      Left(
        ApiError.Validation("dateFrom must not be after dateTo.")
      )
    )

  test("timeFrom equal to timeTo is rejected as a validation error"):
    val ownerId = owner()
    val accountId = insertAccount(ownerId)
    val result = service().create(
      ownerId,
      draft(
        accountId,
        timeFrom = LocalTime.parse("08:00"),
        timeTo = LocalTime.parse("08:00")
      )
    )
    assertEquals(
      result,
      Left(ApiError.Validation("timeFrom must be before timeTo."))
    )

  test("timeFrom after timeTo is rejected as a validation error"):
    val ownerId = owner()
    val accountId = insertAccount(ownerId)
    val result = service().create(
      ownerId,
      draft(
        accountId,
        timeFrom = LocalTime.parse("16:00"),
        timeTo = LocalTime.parse("08:00")
      )
    )
    assertEquals(
      result,
      Left(ApiError.Validation("timeFrom must be before timeTo."))
    )

  test("empty daysOfWeek is rejected as a validation error"):
    val ownerId = owner()
    val accountId = insertAccount(ownerId)
    val result =
      service().create(ownerId, draft(accountId, daysOfWeek = Nil))
    assertEquals(
      result,
      Left(ApiError.Validation("Select at least one day of the week."))
    )

  // -- ownership ---------------------------------------------------------

  test("create with a cross-owner account returns not found"):
    val ownerA = owner("a")
    val ownerB = owner("b")
    val accountB = insertAccount(ownerB)
    val result = service().create(ownerA, draft(accountB))
    assertEquals(result, Left(ApiError.NotFound))

  test("update pointing at a cross-owner account returns not found"):
    val ownerA = owner("a")
    val ownerB = owner("b")
    val accountA = insertAccount(ownerA)
    val accountB = insertAccount(ownerB)
    val svc = service()
    val created = svc.create(ownerA, draft(accountA)).toOption.get

    val result = svc.update(ownerA, created.id, draft(accountB))

    assertEquals(result, Left(ApiError.NotFound))

  test("update of a cross-owner monitor returns not found"):
    val ownerA = owner("a")
    val ownerB = owner("b")
    val accountA = insertAccount(ownerA)
    val svc = service()
    val created = svc.create(ownerA, draft(accountA)).toOption.get

    val result = svc.update(ownerB, created.id, draft(accountA))

    assertEquals(result, Left(ApiError.NotFound))

  test("delete of a cross-owner monitor returns not found and keeps the row"):
    val ownerA = owner("a")
    val ownerB = owner("b")
    val accountA = insertAccount(ownerA)
    val svc = service()
    val created = svc.create(ownerA, draft(accountA)).toOption.get

    assertEquals(svc.delete(ownerB, created.id), Left(ApiError.NotFound))
    assert(svc.get(ownerA, created.id).isRight)

  test("get of a cross-owner monitor returns not found"):
    val ownerA = owner("a")
    val ownerB = owner("b")
    val accountA = insertAccount(ownerA)
    val svc = service()
    val created = svc.create(ownerA, draft(accountA)).toOption.get

    assertEquals(svc.get(ownerB, created.id), Left(ApiError.NotFound))

  // -- state transitions ---------------------------------------------------

  test("new monitors start active"):
    val ownerId = owner()
    val accountId = insertAccount(ownerId)
    val created = service().create(ownerId, draft(accountId)).toOption.get
    assertEquals(created.state, MonitorState.Active)

  test("pause active is paused, pause paused is idempotently paused"):
    val ownerId = owner()
    val accountId = insertAccount(ownerId)
    val svc = service()
    val created = svc.create(ownerId, draft(accountId)).toOption.get

    assertEquals(
      svc.pause(ownerId, created.id).map(_.state),
      Right(MonitorState.Paused)
    )
    assertEquals(
      svc.pause(ownerId, created.id).map(_.state),
      Right(MonitorState.Paused)
    )

  test("resume paused is active, resume active is idempotently active"):
    val ownerId = owner()
    val accountId = insertAccount(ownerId)
    val svc = service()
    val created = svc.create(ownerId, draft(accountId)).toOption.get
    svc.pause(ownerId, created.id)

    assertEquals(
      svc.resume(ownerId, created.id).map(_.state),
      Right(MonitorState.Active)
    )
    assertEquals(
      svc.resume(ownerId, created.id).map(_.state),
      Right(MonitorState.Active)
    )

  test("resume of a completed monitor returns conflict"):
    val ownerId = owner()
    val accountId = insertAccount(ownerId)
    val monitorId = insertMonitorRow(accountId, "completed")

    val result = service().resume(ownerId, monitorId)

    result match
      case Left(ApiError.Conflict(_)) => ()
      case other => fail(s"expected Left(Conflict), got $other")

  test("resume of a failed monitor returns conflict"):
    val ownerId = owner()
    val accountId = insertAccount(ownerId)
    val monitorId = insertMonitorRow(accountId, "failed")

    val result = service().resume(ownerId, monitorId)

    result match
      case Left(ApiError.Conflict(_)) => ()
      case other => fail(s"expected Left(Conflict), got $other")

  test("pause/resume of a missing monitor returns not found"):
    val ownerId = owner()
    val missing = MonitorId(999999L)

    assertEquals(service().pause(ownerId, missing), Left(ApiError.NotFound))
    assertEquals(service().resume(ownerId, missing), Left(ApiError.NotFound))

  // -- persistence fidelity -----------------------------------------------

  test(
    "facility and doctor ids and names keep input ordering and cardinality"
  ):
    val ownerId = owner()
    val accountId = insertAccount(ownerId)
    val facilities =
      List(
        NamedId(11L, "Clinic A"),
        NamedId(13L, "Clinic C"),
        NamedId(12L, "Clinic B")
      )
    val doctors = List(NamedId(21L, "Dr Zielinski"), NamedId(20L, "Dr Adamski"))
    val svc = service()

    val created = svc
      .create(
        ownerId,
        draft(accountId, facilities = facilities, doctors = doctors)
      )
      .toOption
      .get
    assertEquals(created.facilities, facilities)
    assertEquals(created.doctors, doctors)

    val fetched = svc.get(ownerId, created.id).toOption.get
    assertEquals(fetched.facilities, facilities)
    assertEquals(fetched.doctors, doctors)

  // -- CRUD basics ----------------------------------------------------------

  test("list is scoped by owner"):
    val ownerA = owner("a")
    val ownerB = owner("b")
    val accountA = insertAccount(ownerA)
    val accountB = insertAccount(ownerB)
    val svc = service()
    svc.create(ownerA, draft(accountA, name = "A monitor"))
    svc.create(ownerB, draft(accountB, name = "B monitor"))

    assertEquals(svc.list(ownerA).map(_.map(_.name)), Right(List("A monitor")))

  test("update changes fields but never the state"):
    val ownerId = owner()
    val accountId = insertAccount(ownerId)
    val svc = service()
    val created = svc.create(ownerId, draft(accountId)).toOption.get
    svc.pause(ownerId, created.id)

    val updated = svc
      .update(ownerId, created.id, draft(accountId, name = "Renamed"))
      .toOption
      .get

    assertEquals(updated.name, "Renamed")
    assertEquals(updated.state, MonitorState.Paused)

  test("delete removes an owned monitor"):
    val ownerId = owner()
    val accountId = insertAccount(ownerId)
    val svc = service()
    val created = svc.create(ownerId, draft(accountId)).toOption.get

    assertEquals(svc.delete(ownerId, created.id), Right(()))
    assertEquals(svc.get(ownerId, created.id), Left(ApiError.NotFound))

  // -- daysOfWeek bitmask codec (direct unit coverage) ---------------------

  test("encodeDaysOfWeek sets Monday as bit 0"):
    assertEquals(
      MonitorService.encodeDaysOfWeek(List(DayOfWeek.MONDAY)),
      0x01.toShort
    )

  test("encodeDaysOfWeek sets Sunday as bit 6"):
    assertEquals(
      MonitorService.encodeDaysOfWeek(List(DayOfWeek.SUNDAY)),
      0x40.toShort
    )

  test("encodeDaysOfWeek and decodeDaysOfWeek round-trip Mon/Wed/Fri"):
    val days = List(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
    assertEquals(
      MonitorService.decodeDaysOfWeek(MonitorService.encodeDaysOfWeek(days)),
      days
    )

  test("encodeDaysOfWeek and decodeDaysOfWeek round-trip all seven days"):
    val days = DayOfWeek.values.toList
    assertEquals(
      MonitorService.decodeDaysOfWeek(MonitorService.encodeDaysOfWeek(days)),
      days
    )
