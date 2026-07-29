package lmbot.backend.luxmed

import java.time.{Duration, LocalDate}
import java.util.UUID

import scala.io.{Codec, Source}

import lmbot.backend.config.{AppVersion, Secret}
import lmbot.backend.luxmed.model.*
import lmbot.backend.luxmed.support.{
  FakeTime,
  GearsTest,
  MockLuxmedServer,
  RecordedRequest
}
import sttp.model.Uri

class DictionaryAndTermsTest extends munit.FunSuite with GearsTest:

  private val testUuid = UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")

  private def header(
      request: RecordedRequest,
      name: String
  ): Option[String] =
    request.headers.collectFirst:
      case (key, values) if key.equalsIgnoreCase(name) => values.head

  private def fixture(name: String): String =
    val path = s"/luxmed/$name"
    val is = Option(getClass.getResourceAsStream(path))
      .getOrElse(throw IllegalArgumentException(s"Missing fixture: $path"))
    try Source.fromInputStream(is)(using Codec.UTF8).mkString
    finally is.close()

  private def withClient[T](
      body: (LuxmedClient, MockLuxmedServer, FakeTime) => T
  ): T =
    val mock = MockLuxmedServer()
    try
      val config = LuxmedConfig(
        oldApi = Uri.unsafeParse(s"${mock.baseUri}/PatientPortalMobileAPI/api"),
        newApi = Uri.unsafeParse(s"${mock.baseUri}/PatientPortal"),
        appVersion = AppVersion.unsafeFromString("4.44.0"),
        deviceUuid = testUuid
      )
      val transport = LuxmedTransport(config)
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
      body(client, mock, fake)
    finally mock.close()

  /** Authenticate and return the client + mock for further operations. */
  private def withAuthenticatedClient[T](
      body: (LuxmedClient, MockLuxmedServer, FakeTime) => T
  ): T =
    withClient: (client, mock, fake) =>
      mock.enqueue(
        status = 200,
        body = fixture("auth-password-success.json")
      )
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Set-Cookie" -> "ASP.NET_SessionId=sess1")
      )
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map(
          "Set-Cookie" -> "jwt=JWT1",
          "Authorization-Token" -> "Bearer JWT_TOKEN_1"
        )
      )
      runAsync:
        client.authenticate()
      body(client, mock, fake)

  // -- Dictionary tests --

  test("cities hits the correct path and uses JWT auth"):
    withAuthenticatedClient: (client, mock, _) =>
      mock.enqueue(
        status = 200,
        body = fixture("cities.json")
      )
      val result = runAsync:
        client.cities()
      assert(result.isRight, s"expected success, got $result")
      assertEquals(
        mock.requests.last.path,
        "/PatientPortal/NewPortal/Dictionary/cities"
      )
      assertEquals(
        header(mock.requests.last, "Authorization-Token"),
        Some("Bearer JWT_TOKEN_1")
      )

  test("serviceVariants hits the correct path"):
    withAuthenticatedClient: (client, mock, _) =>
      mock.enqueue(
        status = 200,
        body = fixture("service-variants.json")
      )
      val result = runAsync:
        client.serviceVariants()
      assert(result.isRight, s"expected success, got $result")
      assertEquals(
        mock.requests.last.path,
        "/PatientPortal/NewPortal/Dictionary/serviceVariantsGroups"
      )

  test("facilitiesAndDoctors passes cityId and serviceVariantId"):
    withAuthenticatedClient: (client, mock, _) =>
      mock.enqueue(
        status = 200,
        body = fixture("facilities-and-doctors.json")
      )
      val result = runAsync:
        client.facilitiesAndDoctors(
          cityId = CityId(70),
          serviceVariantId = ServiceVariantId(4502)
        )
      assert(result.isRight, s"expected success, got $result")
      val req = mock.requests.last
      assertEquals(
        req.path,
        "/PatientPortal/NewPortal/Dictionary/facilitiesAndDoctors"
      )
      assertEquals(req.rawQuery, Some("cityId=70&serviceVariantId=4502"))

  // -- Terms search tests --

  test("full terms search query contains expected parameters"):
    withAuthenticatedClient: (client, mock, _) =>
      val query = TermsQuery(
        cityId = CityId(70),
        serviceVariantId = ServiceVariantId(4502),
        searchDateFrom = LocalDate.parse("2026-08-03"),
        searchDateTo = LocalDate.parse("2026-08-10"),
        processId = UUID.fromString("00000000-0000-0000-0000-000000000123"),
        facilityIds = Some(FacilityId(78)),
        doctorIds = Some(DoctorId(111111))
      )
      mock.enqueue(
        status = 200,
        body = fixture("terms-dual-datetime.json")
      )
      val result = runAsync:
        client.searchTerms(query)
      assert(result.isRight, s"expected success, got $result")
      assertEquals(
        mock.requests.last.path,
        "/PatientPortal/NewPortal/terms/index"
      )
      val rawQuery = mock.requests.last.rawQuery.getOrElse("")
      // Check each required parameter is present
      assert(rawQuery.contains("searchPlace.id=70"))
      assert(rawQuery.contains("searchPlace.type=0"))
      assert(rawQuery.contains("serviceVariantId=4502"))
      assert(rawQuery.contains("languageId=10"))
      assert(rawQuery.contains("searchDateFrom=2026-08-03"))
      assert(rawQuery.contains("searchDateTo=2026-08-10"))
      assert(rawQuery.contains("searchDatePreset=14"))
      assert(
        rawQuery.contains(
          "processId=00000000-0000-0000-0000-000000000123"
        )
      )
      assert(rawQuery.contains("serviceVariantSource=0"))
      assert(rawQuery.contains("facilitiesIds=78"))
      assert(rawQuery.contains("doctorsIds=111111"))
      assert(rawQuery.contains("nextSearch=false"))
      assert(rawQuery.contains("searchByMedicalSpecialist=false"))
      assert(rawQuery.contains("delocalized=false"))

  test(
    "optional facility and doctor parameters are omitted when absent"
  ):
    withAuthenticatedClient: (client, mock, _) =>
      val query = TermsQuery(
        cityId = CityId(70),
        serviceVariantId = ServiceVariantId(4502),
        searchDateFrom = LocalDate.parse("2026-08-03"),
        searchDateTo = LocalDate.parse("2026-08-10"),
        processId = UUID.fromString("00000000-0000-0000-0000-000000000123"),
        facilityIds = None,
        doctorIds = None
      )
      mock.enqueue(
        status = 200,
        body = fixture("terms-dual-datetime.json")
      )
      val result = runAsync:
        client.searchTerms(query)
      assert(result.isRight, s"expected success, got $result")
      val rawQuery = mock.requests.last.rawQuery.getOrElse("")
      assert(!rawQuery.contains("facilitiesIds"))
      assert(!rawQuery.contains("doctorsIds"))
