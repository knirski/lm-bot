package lmbot.backend.luxmed

import java.sql.{Date as SqlDate, Time as SqlTime}
import java.time.{Duration, LocalDate, LocalTime, OffsetDateTime}
import java.util.UUID

import scala.io.{Codec, Source}

import lmbot.backend.config.{AppVersion, Secret}
import lmbot.backend.db.MonitorRow
import lmbot.backend.luxmed.model.Credentials
import lmbot.backend.luxmed.support.{
  FakeTime,
  GearsTest,
  LuxmedResponseScripts,
  StubLuxmedBackend
}
import lmbot.backend.monitor.{CheckFailure, LuxmedSlotSearch}
import lmbot.shared.api.ApiError
import sttp.model.Uri

/** End-to-end proof that `LuxmedSlotSearch` drives an authenticated client
  * through a real request shape: the monitor's criteria become the query
  * parameters, and the response becomes the engine's slots.
  */
class LuxmedSlotSearchTest extends munit.FunSuite with GearsTest:

  private val testUuid = UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")

  private val testConfig = LuxmedConfig(
    oldApi = Uri.unsafeParse("http://localhost:1/PatientPortalMobileAPI/api"),
    newApi = Uri.unsafeParse("http://localhost:1/PatientPortal"),
    appVersion = AppVersion.unsafeFromString("5.8.0"),
    deviceUuid = testUuid
  )

  private def fixture(name: String): String =
    val path = s"/luxmed/$name"
    val is = Option(getClass.getResourceAsStream(path))
      .getOrElse(throw IllegalArgumentException(s"Missing fixture: $path"))
    try Source.fromInputStream(is)(using Codec.UTF8).mkString
    finally is.close()

  private def withAuthenticatedClient[T](
      body: (LuxmedClient, StubLuxmedBackend) => T
  ): T =
    val stub = StubLuxmedBackend()
    val transport = LuxmedTransport.withBackend(testConfig, stub.backend)
    val credentials = Credentials("user@example.com", Secret("password123"))
    val fake = FakeTime()
    val gate = AccountGate(Duration.ZERO, () => fake.now(), fake.sleeper)
    val client = LuxmedClient(
      transport,
      credentials,
      gate,
      InMemorySessionStore(),
      now = () => fake.now()
    )
    LuxmedResponseScripts
      .realisticAuthFlow(
        accessToken = "ACCESS_1",
        refreshToken = "REFRESH_1",
        jwtToken = "JWT_TOKEN_1",
        expiresIn = 599
      )
      .foreach: response =>
        stub.enqueue(response.status, response.headers, response.body)
    runAsync:
      client.authenticate()
    body(client, stub)

  private def aMonitor(): MonitorRow =
    val now = OffsetDateTime.parse("2026-08-01T00:00:00Z")
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
      doctorIds = List(111111L),
      doctorNames = List("Dr A"),
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

  test("search sends the monitor criteria and maps the response to slots"):
    withAuthenticatedClient: (client, stub) =>
      stub.enqueue(status = 200, body = fixture("terms-dual-datetime.json"))
      val search = LuxmedSlotSearch(_ => Right(client))

      val result = runAsync(search.search(aMonitor()))

      assert(result.isRight, s"expected slots, got $result")
      val slots = result.toOption.get
      assertEquals(slots.size, 2)
      assertEquals(slots.head.key, "40:2026-08-03T09:00")

      val request = stub.requests.last
      assertEquals(
        "/" + request.uri.path.mkString("/"),
        "/PatientPortal/NewPortal/terms/index"
      )
      val params = request.uri.params.toSeq
      assert(params.contains("searchPlace.id" -> "70"))
      assert(params.contains("serviceVariantId" -> "4502"))
      assert(params.contains("searchDateFrom" -> "2026-08-03"))
      assert(params.contains("searchDateTo" -> "2026-08-10"))
      assert(params.contains("facilitiesIds" -> "78"))
      assert(params.contains("facilitiesIds" -> "79"))
      assert(params.contains("doctorsIds" -> "111111"))

  test("a missing account client is a persistent failure, not a crash"):
    val search = LuxmedSlotSearch(_ => Left(ApiError.NotFound))

    val result = runAsync(search.search(aMonitor()))

    assertEquals(
      result,
      Left(
        CheckFailure.Persistent("The Luxmed account is no longer available.")
      )
    )
