package lmbot.backend

import java.sql.{Date as SqlDate, Time as SqlTime}
import java.time.{LocalDateTime, OffsetDateTime}

import lmbot.backend.db.{
  AccountRepo,
  LuxmedAccountRow,
  MonitorEventRepo,
  MonitorRepo,
  MonitorRow,
  UserRepo
}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.{
  AccountId,
  FoundSlot,
  MonitorEventKind,
  MonitorId,
  Role,
  UserId
}

class MonitorEventRepoTest extends PostgresSuite:

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

  private def aMonitor(accountId: Long): MonitorId =
    val repo = MonitorRepo(xa)
    val id = repo.reserveId()
    val now = OffsetDateTime.now()
    repo.insert(
      MonitorRow(
        id = id,
        luxmedAccountId = accountId,
        name = "Test Monitor",
        cityId = 100L,
        cityName = "Warsaw",
        serviceId = 200L,
        serviceName = "Konsultacja",
        facilityIds = List.empty,
        facilityNames = List.empty,
        doctorIds = List.empty,
        doctorNames = List.empty,
        dateFrom = SqlDate.valueOf(java.time.LocalDate.parse("2026-08-01")),
        dateTo = SqlDate.valueOf(java.time.LocalDate.parse("2026-08-31")),
        timeFrom = SqlTime.valueOf(java.time.LocalTime.parse("08:00")),
        timeTo = SqlTime.valueOf(java.time.LocalTime.parse("16:00")),
        daysOfWeek = 0x7f.toShort,
        autoBook = false,
        intervalMinutes = 10,
        state = "active",
        createdAt = now,
        updatedAt = now
      )
    )
    MonitorId(id)

  private def aSlot(key: String = "400:2026-08-10T09:00"): FoundSlot =
    FoundSlot(
      key = key,
      clinicId = 100L,
      clinicName = Some("Mock Clinic"),
      doctorId = 200L,
      doctorName = "dr Mock Doctor",
      from = LocalDateTime.parse("2026-08-10T09:00"),
      to = LocalDateTime.parse("2026-08-10T09:15"),
      telemedicine = false
    )

  private val now = OffsetDateTime.parse("2026-08-10T07:00:00Z")

  test("recordSlotFound returns true once and false afterwards"):
    val monitorId = aMonitor(anAccount(anOwner()))
    val events = MonitorEventRepo(xa)

    assert(events.recordSlotFound(monitorId, aSlot(), now))
    assert(!events.recordSlotFound(monitorId, aSlot(), now))
    // The database, not an in-memory set, enforces dedup.
    assert(!MonitorEventRepo(xa).recordSlotFound(monitorId, aSlot(), now))

  test("two monitors record the same slot independently"):
    val ownerId = anOwner()
    val accountId = anAccount(ownerId)
    val first = aMonitor(accountId)
    val second = aMonitor(accountId)
    val events = MonitorEventRepo(xa)

    assert(events.recordSlotFound(first, aSlot(), now))
    assert(events.recordSlotFound(second, aSlot(), now))

  test("a recorded slot round-trips with its Warsaw-local details"):
    val monitorId = aMonitor(anAccount(anOwner()))
    val events = MonitorEventRepo(xa)
    events.recordSlotFound(monitorId, aSlot(), now)

    val stored = events.listRecent(monitorId, 10).head
    assertEquals(stored.kind, "slot_found")
    assertEquals(stored.slotKey, Some("400:2026-08-10T09:00"))
    assertEquals(stored.slotClinicId, Some(100L))
    assertEquals(stored.slotClinicName, Some("Mock Clinic"))
    assertEquals(stored.slotDoctorId, Some(200L))
    assertEquals(stored.slotDoctorName, Some("dr Mock Doctor"))
    assertEquals(
      stored.slotFrom,
      Some(LocalDateTime.parse("2026-08-10T09:00"))
    )
    assertEquals(stored.slotTo, Some(LocalDateTime.parse("2026-08-10T09:15")))
    assertEquals(stored.slotTelemedicine, Some(false))

  test("append records a non-slot event with a null slot"):
    val monitorId = aMonitor(anAccount(anOwner()))
    val events = MonitorEventRepo(xa)

    val id =
      events.append(
        monitorId,
        MonitorEventKind.Error,
        slot = None,
        detail = Some("Malformed JSON response"),
        at = now
      )

    val stored = events.listRecent(monitorId, 10).head
    assertEquals(stored.id, id.value)
    assertEquals(stored.kind, "error")
    assertEquals(stored.slotKey, None)
    assertEquals(stored.detail, Some("Malformed JSON response"))

  test("append can carry the slot for a notification event"):
    val monitorId = aMonitor(anAccount(anOwner()))
    val events = MonitorEventRepo(xa)

    events.append(
      monitorId,
      MonitorEventKind.NotificationSent,
      slot = Some(aSlot()),
      detail = None,
      at = now
    )

    val stored = events.listRecent(monitorId, 10).head
    assertEquals(stored.kind, "notification_sent")
    assertEquals(stored.slotKey, Some("400:2026-08-10T09:00"))

  test("listRecent returns newest first and honours the limit"):
    val monitorId = aMonitor(anAccount(anOwner()))
    val events = MonitorEventRepo(xa)
    val base = OffsetDateTime.parse("2026-08-10T07:00:00Z")
    events.append(
      monitorId,
      MonitorEventKind.Error,
      None,
      Some("first"),
      base
    )
    events.append(
      monitorId,
      MonitorEventKind.Error,
      None,
      Some("second"),
      base.plusMinutes(1)
    )
    events.append(
      monitorId,
      MonitorEventKind.Error,
      None,
      Some("third"),
      base.plusMinutes(2)
    )

    assertEquals(
      events.listRecent(monitorId, 10).map(_.detail),
      Seq(Some("third"), Some("second"), Some("first"))
    )
    assertEquals(
      events.listRecent(monitorId, 2).map(_.detail),
      Seq(Some("third"), Some("second"))
    )

  test("deleting the monitor cascades its events"):
    val ownerId = anOwner()
    val accountId = anAccount(ownerId)
    val monitorId = aMonitor(accountId)
    val events = MonitorEventRepo(xa)
    events.recordSlotFound(monitorId, aSlot(), now)

    AccountRepo(xa).deleteOwned(AccountId(accountId), ownerId)

    assertEquals(events.listRecent(monitorId, 10), Seq.empty)

  // -- Delivery retries (Plan 5 review) --

  test("a failed delivery is a slot awaiting retry"):
    val monitorId = aMonitor(anAccount(anOwner()))
    val events = MonitorEventRepo(xa)
    val slot = aSlot()
    events.recordSlotFound(monitorId, slot, now)
    events.append(
      monitorId,
      MonitorEventKind.NotificationFailed,
      Some(slot),
      Some("down"),
      now.plusMinutes(1)
    )

    assertEquals(events.slotsAwaitingDelivery(monitorId, 3), Seq(slot))

  test("a delivered slot is not retried"):
    val monitorId = aMonitor(anAccount(anOwner()))
    val events = MonitorEventRepo(xa)
    val slot = aSlot()
    events.recordSlotFound(monitorId, slot, now)
    events.append(
      monitorId,
      MonitorEventKind.NotificationFailed,
      Some(slot),
      Some("down"),
      now.plusMinutes(1)
    )
    events.append(
      monitorId,
      MonitorEventKind.NotificationSent,
      Some(slot),
      None,
      now.plusMinutes(2)
    )

    assertEquals(events.slotsAwaitingDelivery(monitorId, 3), Seq.empty)

  test("a slot at the attempt cap is not retried"):
    val monitorId = aMonitor(anAccount(anOwner()))
    val events = MonitorEventRepo(xa)
    val slot = aSlot()
    events.recordSlotFound(monitorId, slot, now)
    (1 to 3).foreach: attempt =>
      events.append(
        monitorId,
        MonitorEventKind.NotificationFailed,
        Some(slot),
        Some("down"),
        now.plusMinutes(attempt.toLong)
      )

    assertEquals(events.slotsAwaitingDelivery(monitorId, 3), Seq.empty)

  test("a slot with no delivery attempt at all is not retried"):
    val monitorId = aMonitor(anAccount(anOwner()))
    val events = MonitorEventRepo(xa)
    events.recordSlotFound(monitorId, aSlot(), now)

    assertEquals(events.slotsAwaitingDelivery(monitorId, 3), Seq.empty)
