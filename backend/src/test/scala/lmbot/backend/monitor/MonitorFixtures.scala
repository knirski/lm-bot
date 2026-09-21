package lmbot.backend.monitor

import java.sql.{Date as SqlDate, Time as SqlTime}
import java.time.{LocalDate, LocalDateTime, OffsetDateTime}

import scala.collection.mutable

import gears.async.Async
import lmbot.backend.db.{
  AccountRepo,
  LuxmedAccountRow,
  MonitorEventRepo,
  MonitorRepo,
  MonitorRow,
  UserRepo
}
import lmbot.backend.luxmed.support.GearsTest
import lmbot.backend.notify.{
  NotificationChannel,
  NotificationError,
  NotificationService
}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.{FoundSlot, Role, UserId}

/** Shared fixtures for the engine tests: a real database, a recording
  * notification channel, and rows that satisfy every foreign key.
  */
abstract class MonitorFixtures extends PostgresSuite with GearsTest:

  protected val fixedNow: OffsetDateTime =
    OffsetDateTime.parse("2026-08-10T07:00:00Z")

  final protected class RecordingChannel(
      result: Either[NotificationError, Unit] = Right(())
  ) extends NotificationChannel:
    val sent = mutable.ListBuffer.empty[(Long, String)]
    def send(chatId: Long, text: String)(using
        Async
    ): Either[NotificationError, Unit] =
      sent += ((chatId, text))
      result

  private var nextUser = 0

  protected def anOwner(
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

  protected def anAccount(
      ownerId: UserId,
      status: String = "active"
  ): Long =
    val repo = AccountRepo(xa)
    val accountId = repo.reserveId()
    repo.insert(
      LuxmedAccountRow(
        id = accountId.value,
        ownerUserId = ownerId.value,
        label = "Main",
        encryptedUsername = "user@example.com",
        encryptedPassword = "enc-pass",
        encryptedDeviceUuid = "enc-device",
        encryptedSession = None,
        status = status,
        statusReason = None,
        lastSuccessfulLogin = None,
        createdAt = fixedNow,
        updatedAt = fixedNow
      )
    )
    accountId.value

  protected def aMonitor(
      accountId: Long,
      dateFrom: LocalDate = LocalDate.parse("2026-08-10"),
      dateTo: LocalDate = LocalDate.parse("2026-08-31"),
      intervalMinutes: Int = 10,
      facilityIds: List[Long] = List(10L),
      doctorIds: List[Long] = List(20L),
      daysOfWeek: Short = 0b0000101, // Monday, Wednesday
      state: String = "active"
  ): MonitorRow =
    val repo = MonitorRepo(xa)
    val id = repo.reserveId()
    repo.insert(
      MonitorRow(
        id = id,
        luxmedAccountId = accountId,
        name = "Test Monitor",
        cityId = 100L,
        cityName = "Warsaw",
        serviceId = 200L,
        serviceName = "Konsultacja",
        facilityIds = facilityIds,
        facilityNames = facilityIds.map(id => s"Clinic $id"),
        doctorIds = doctorIds,
        doctorNames = doctorIds.map(id => s"Dr $id"),
        dateFrom = SqlDate.valueOf(dateFrom),
        dateTo = SqlDate.valueOf(dateTo),
        timeFrom = SqlTime.valueOf(java.time.LocalTime.parse("08:00")),
        timeTo = SqlTime.valueOf(java.time.LocalTime.parse("12:00")),
        daysOfWeek = daysOfWeek,
        autoBook = false,
        intervalMinutes = intervalMinutes,
        state = state,
        createdAt = fixedNow,
        updatedAt = fixedNow
      )
    )

  protected def aSlot(
      from: String = "2026-08-10T09:00",
      clinicId: Long = 10L,
      doctorId: Long = 20L
  ): FoundSlot =
    val start = LocalDateTime.parse(from)
    FoundSlot(
      key = s"40:$from",
      clinicId = clinicId,
      clinicName = Some(s"Clinic $clinicId"),
      doctorId = doctorId,
      doctorName = s"Dr $doctorId",
      from = start,
      to = start.plusMinutes(15),
      telemedicine = false
    )

  protected def notifier(
      channel: Option[NotificationChannel]
  ): NotificationService =
    NotificationService(
      UserRepo(xa),
      channel,
      MonitorEventRepo(xa),
      () => fixedNow
    )

  protected def events(monitorId: Long): Seq[String] =
    MonitorEventRepo(xa)
      .listRecent(lmbot.shared.domain.MonitorId(monitorId), 20)
      .map(_.kind)
