package lmbot.backend.monitor

import java.sql.{Date as SqlDate, Time as SqlTime}
import java.time.{LocalDate, LocalTime}

import scala.io.{Codec, Source}

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import lmbot.backend.config.SafeDiagnostic
import lmbot.backend.db.MonitorRow
import lmbot.backend.luxmed.LuxmedError
import lmbot.backend.luxmed.model.WireCodecs.given
import lmbot.backend.luxmed.model.{TermsResponse, WireCodecs}

/** The pure half of slot search: monitor → query, term → slot, error → engine
  * failure. No HTTP, no database.
  */
class LuxmedSlotMappingTest extends munit.FunSuite:

  private def fixture(name: String): String =
    val path = s"/luxmed/$name"
    val is = Option(getClass.getResourceAsStream(path))
      .getOrElse(throw IllegalArgumentException(s"Missing fixture: $path"))
    try Source.fromInputStream(is)(using Codec.UTF8).mkString
    finally is.close()

  private def aMonitor(): MonitorRow =
    val now = java.time.OffsetDateTime.parse("2026-08-01T00:00:00Z")
    MonitorRow(
      id = 1L,
      luxmedAccountId = 2L,
      name = "Dermatologist",
      cityId = 70L,
      cityName = "Białystok",
      serviceId = 4502L,
      serviceName = "Dermatology",
      facilityIds = List(78L, 79L),
      facilityNames = List("Clinic A", "Clinic B"),
      doctorIds = List(111111L, 222222L),
      doctorNames = List("Dr A", "Dr B"),
      dateFrom = SqlDate.valueOf(LocalDate.parse("2026-08-03")),
      dateTo = SqlDate.valueOf(LocalDate.parse("2026-08-10")),
      timeFrom = SqlTime.valueOf(LocalTime.parse("08:00")),
      timeTo = SqlTime.valueOf(LocalTime.parse("16:00")),
      daysOfWeek = 0x7f.toShort,
      autoBook = false,
      intervalMinutes = 10,
      state = "active",
      createdAt = now,
      updatedAt = now
    )

  test("a monitor maps to a terms query with every facility and doctor id"):
    val query = LuxmedSlotMapping.query(aMonitor())

    assertEquals(query.cityId.value, 70L)
    assertEquals(query.serviceVariantId.value, 4502L)
    assertEquals(query.searchDateFrom, LocalDate.parse("2026-08-03"))
    assertEquals(query.searchDateTo, LocalDate.parse("2026-08-10"))
    assertEquals(query.facilityIds.map(_.value), List(78L, 79L))
    assertEquals(query.doctorIds.map(_.value), List(111111L, 222222L))

  test("both wire datetime formats normalise to Warsaw-local slots"):
    val response =
      readFromString[TermsResponse](fixture("terms-dual-datetime.json"))
    val slots =
      response.termsForService.termsForDays
        .flatMap(_.terms)
        .map(
          LuxmedSlotMapping.toFoundSlot
        )

    assertEquals(slots.size, 2)
    val first = slots.head
    assertEquals(first.key, "40:2026-08-03T09:00")
    assertEquals(first.clinicId, 10L)
    assertEquals(first.clinicName, Some("LX Warszawa"))
    assertEquals(first.doctorId, 20L)
    assertEquals(first.doctorName, "lek. Anna Nowak")
    assertEquals(first.from, java.time.LocalDateTime.parse("2026-08-03T09:00"))
    assertEquals(first.to, java.time.LocalDateTime.parse("2026-08-03T09:15"))
    assertEquals(first.telemedicine, false)

    val second = slots(1)
    assertEquals(second.key, "41:2026-08-03T10:00")
    assertEquals(second.doctorName, "lek. Jan Kowalski")

  test("every LuxmedError classifies into the engine's failure vocabulary"):
    val detail = SafeDiagnostic("safe detail")

    assertEquals(
      LuxmedSlotMapping.classify(LuxmedError.AuthFailed),
      CheckFailure.AuthRejected
    )
    assertEquals(
      LuxmedSlotMapping.classify(LuxmedError.UnexpectedAuthResponse(detail)),
      CheckFailure.Challenge
    )
    assertEquals(
      LuxmedSlotMapping.classify(LuxmedError.RateLimited),
      CheckFailure.RateLimited
    )
    // The client already retried once with a fresh session; a second expiry
    // is a transient hiccup, not a reason to pause the account.
    assertEquals(
      LuxmedSlotMapping.classify(LuxmedError.SessionExpired),
      CheckFailure.Transient("The Luxmed session expired.")
    )
    assertEquals(
      LuxmedSlotMapping.classify(LuxmedError.VersionRejected(detail)),
      CheckFailure.VersionRejected
    )
    assertEquals(
      LuxmedSlotMapping.classify(LuxmedError.NetworkFailure(detail)),
      CheckFailure.Transient("safe detail")
    )
    assertEquals(
      LuxmedSlotMapping.classify(LuxmedError.Transient(503)),
      CheckFailure.Transient("Luxmed returned HTTP 503")
    )
    assertEquals(
      LuxmedSlotMapping.classify(LuxmedError.PersistenceFailed(detail)),
      CheckFailure.Transient("safe detail")
    )
    assertEquals(
      LuxmedSlotMapping.classify(LuxmedError.DecodeFailed(detail)),
      CheckFailure.Persistent("safe detail")
    )
    assertEquals(
      LuxmedSlotMapping.classify(LuxmedError.ProtocolViolation(detail)),
      CheckFailure.Persistent("safe detail")
    )
    assertEquals(
      LuxmedSlotMapping.classify(LuxmedError.ApiRejected(detail)),
      CheckFailure.Persistent("safe detail")
    )
    assertEquals(
      LuxmedSlotMapping.classify(LuxmedError.SlotGone),
      CheckFailure.Persistent("The slot is no longer available.")
    )
