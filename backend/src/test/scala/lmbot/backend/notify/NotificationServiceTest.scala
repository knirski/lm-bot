package lmbot.backend.notify

import java.sql.{Date as SqlDate, Time as SqlTime}
import java.time.{LocalDateTime, OffsetDateTime}

import scala.collection.mutable

import gears.async.Async
import lmbot.backend.account.AccountStatusReason
import lmbot.backend.db.{
  AccountRepo,
  LuxmedAccountRow,
  MonitorEventRepo,
  MonitorRepo,
  MonitorRow,
  UserRepo
}
import lmbot.backend.luxmed.support.GearsTest
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.{FoundSlot, MonitorId, Role, UserId}

class NotificationServiceTest extends PostgresSuite with GearsTest:

  final private class RecordingChannel(
      result: Either[NotificationError, Unit] = Right(())
  ) extends NotificationChannel:
    val sent = mutable.ListBuffer.empty[(Long, String)]
    def send(chatId: Long, text: String)(using
        Async
    ): Either[NotificationError, Unit] =
      sent += ((chatId, text))
      result

  private var nextUser = 0

  private def anOwner(
      chatId: Option[Long] = None,
      role: Role = Role.User
  ): UserId =
    nextUser += 1
    val users = UserRepo(xa)
    val stored =
      users.insert(s"owner$nextUser", s"Owner $nextUser", "hash", role)
    val userId = UserId(stored.id)
    chatId.foreach(users.setTelegramChatId(userId, _))
    userId

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

  private def aMonitor(accountId: Long): MonitorRow =
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

  private def aSlot(): FoundSlot =
    FoundSlot(
      key = "40:2026-08-10T09:00",
      clinicId = 10L,
      clinicName = Some("LX Warszawa"),
      doctorId = 20L,
      doctorName = "lek. Anna Nowak",
      from = LocalDateTime.parse("2026-08-10T09:00"),
      to = LocalDateTime.parse("2026-08-10T09:15"),
      telemedicine = false
    )

  private val now = OffsetDateTime.parse("2026-08-10T07:00:00Z")

  private def service(channel: Option[NotificationChannel]) =
    NotificationService(
      UserRepo(xa),
      channel,
      MonitorEventRepo(xa),
      () => now
    )

  private def eventKinds(monitorId: MonitorId): Seq[String] =
    MonitorEventRepo(xa).listRecent(monitorId, 10).map(_.kind)

  test("a linked user gets one message and a notification_sent event"):
    val channel = RecordingChannel()
    val ownerId = anOwner(chatId = Some(555L))
    val monitor = aMonitor(anAccount(ownerId))

    runAsync(service(Some(channel)).notifySlot(ownerId, monitor, aSlot()))

    assertEquals(
      channel.sent.toList,
      List(
        555L -> "Test Monitor: new slot at LX Warszawa, 2026-08-10 09:00–09:15, lek. Anna Nowak."
      )
    )
    assertEquals(eventKinds(MonitorId(monitor.id)), Seq("notification_sent"))

  test("an unlinked user gets no message and no notification event"):
    val channel = RecordingChannel()
    val ownerId = anOwner()
    val monitor = aMonitor(anAccount(ownerId))

    runAsync(service(Some(channel)).notifySlot(ownerId, monitor, aSlot()))

    assertEquals(channel.sent.toList, Nil)
    assertEquals(eventKinds(MonitorId(monitor.id)), Nil)

  test("no configured channel degrades silently"):
    val ownerId = anOwner(chatId = Some(555L))
    val monitor = aMonitor(anAccount(ownerId))

    runAsync(service(None).notifySlot(ownerId, monitor, aSlot()))

    assertEquals(eventKinds(MonitorId(monitor.id)), Nil)

  test("a rejected delivery is recorded as notification_failed, not thrown"):
    val channel = RecordingChannel(
      Left(NotificationError.Rejected("bot was blocked by the user"))
    )
    val ownerId = anOwner(chatId = Some(555L))
    val monitor = aMonitor(anAccount(ownerId))

    runAsync(service(Some(channel)).notifySlot(ownerId, monitor, aSlot()))

    val events = MonitorEventRepo(xa).listRecent(MonitorId(monitor.id), 10)
    assertEquals(events.map(_.kind), Seq("notification_failed"))
    assertEquals(events.head.detail, Some("bot was blocked by the user"))
    assertEquals(events.head.slotKey, Some("40:2026-08-10T09:00"))

  test("a failed monitor notification reaches the owner with the reason"):
    val channel = RecordingChannel()
    val ownerId = anOwner(chatId = Some(555L))
    val monitor = aMonitor(anAccount(ownerId))

    runAsync(
      service(Some(channel))
        .notifyMonitorFailed(ownerId, monitor, "decode failures")
    )

    assertEquals(channel.sent.size, 1)
    assert(
      channel.sent.head._2.contains("decode failures"),
      s"reason missing: ${channel.sent.head._2}"
    )

  test("an account auth failure notification names the reason"):
    val channel = RecordingChannel()
    val ownerId = anOwner(chatId = Some(555L))

    runAsync(
      service(Some(channel)).notifyAccountAuthFailure(
        ownerId,
        "Home",
        AccountStatusReason.Challenge
      )
    )

    assertEquals(channel.sent.size, 1)
    assert(
      channel.sent.head._2.contains(
        "Luxmed requested an unexpected authentication step."
      ),
      s"reason missing: ${channel.sent.head._2}"
    )

  test("version rejection reaches every linked admin and nobody else"):
    val channel = RecordingChannel()
    val linkedAdmin = anOwner(chatId = Some(111L), role = Role.Admin)
    anOwner(role = Role.Admin) // an admin without a chat
    val linkedUser = anOwner(chatId = Some(222L))

    runAsync(service(Some(channel)).notifyAdminsVersionRejected("5.8.0 is old"))

    assertEquals(channel.sent.map(_._1).toList, List(111L))
    assert(linkedUser != linkedAdmin)
