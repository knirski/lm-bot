package lmbot.backend.db

import java.sql.{Date => SqlDate, Time => SqlTime}
import java.time.{LocalDateTime, OffsetDateTime}

import com.augustnagro.magnum.{
  DbCodec,
  Id,
  PostgresDbType,
  SqlNameMapper,
  Table
}

/** Persistence shapes. `role` is a `String` rather than the shared `Role` enum
  * on purpose: deriving a Magnum `DbCodec` for `Role` would drag a JVM-only
  * dependency into the cross-compiled `shared` module. Conversion happens at
  * the repository boundary instead.
  */
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class UserRow(
    @Id id: Long,
    username: String,
    displayName: String,
    passwordHash: String,
    role: String,
    telegramChatId: Option[Long],
    disabled: Boolean,
    createdAt: OffsetDateTime,
    updatedAt: OffsetDateTime,
    telegramLinkCodeHash: Option[String] = None,
    telegramLinkCodeExpiresAt: Option[OffsetDateTime] = None
) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class SessionRow(
    @Id tokenHash: String,
    userId: Long,
    expiresAt: OffsetDateTime,
    createdAt: OffsetDateTime
) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class LuxmedAccountRow(
    @Id id: Long,
    ownerUserId: Long,
    label: String,
    encryptedUsername: String,
    encryptedPassword: String,
    encryptedDeviceUuid: String,
    encryptedSession: Option[String],
    status: String,
    statusReason: Option[String],
    lastSuccessfulLogin: Option[OffsetDateTime],
    createdAt: OffsetDateTime,
    updatedAt: OffsetDateTime
) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class MonitorRow(
    @Id id: Long,
    luxmedAccountId: Long,
    name: String,
    cityId: Long,
    cityName: String,
    serviceId: Long,
    serviceName: String,
    facilityIds: List[Long],
    facilityNames: List[String],
    doctorIds: List[Long],
    doctorNames: List[String],
    dateFrom: SqlDate,
    dateTo: SqlDate,
    timeFrom: SqlTime,
    timeTo: SqlTime,
    daysOfWeek: Short,
    autoBook: Boolean,
    intervalMinutes: Int,
    state: String,
    createdAt: OffsetDateTime,
    updatedAt: OffsetDateTime,
    lastCheckAt: Option[OffsetDateTime] = None,
    lastCheckSummary: Option[String] = None
) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class MonitorEventRow(
    @Id id: Long,
    monitorId: Long,
    kind: String,
    slotKey: Option[String],
    slotClinicId: Option[Long],
    slotClinicName: Option[String],
    slotDoctorId: Option[Long],
    slotDoctorName: Option[String],
    slotFrom: Option[LocalDateTime],
    slotTo: Option[LocalDateTime],
    slotTelemedicine: Option[Boolean],
    detail: Option[String],
    createdAt: OffsetDateTime
) derives DbCodec
