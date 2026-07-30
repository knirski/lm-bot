package lmbot.backend.luxmed

import java.time.{Duration, LocalDate}
import java.util.UUID

import scala.io.{Codec, Source}

import lmbot.backend.config.{AppVersion, Secret}
import lmbot.backend.luxmed.model.*
import lmbot.backend.luxmed.support.{
  FakeTime,
  GearsTest,
  LuxmedResponseScripts,
  StubLuxmedBackend
}
import sttp.client3.Request
import sttp.model.Uri

class DictionaryAndTermsTest extends munit.FunSuite with GearsTest:

  private val testUuid = UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")

  private val testConfig = LuxmedConfig(
    oldApi = Uri.unsafeParse("http://localhost:1/PatientPortalMobileAPI/api"),
    newApi = Uri.unsafeParse("http://localhost:1/PatientPortal"),
    appVersion = AppVersion.unsafeFromString("4.44.0"),
    deviceUuid = testUuid
  )

  private def requestPath(request: Request[?, ?]): String =
    "/" + request.uri.path.mkString("/")

  private def requestQuery(request: Request[?, ?]): String =
    request.uri.paramsMap.toList
      .map { case (k, v) => s"$k=$v" }
      .sorted
      .mkString("&")

  private def requestHeader(
      request: Request[?, ?],
      name: String
  ): Option[String] =
    request.headers.find(_.name.equalsIgnoreCase(name)).map(_.value)

  private def fixture(name: String): String =
    val path = s"/luxmed/$name"
    val is = Option(getClass.getResourceAsStream(path))
      .getOrElse(throw IllegalArgumentException(s"Missing fixture: $path"))
    try Source.fromInputStream(is)(using Codec.UTF8).mkString
    finally is.close()

  private def withClient[T](
      body: (LuxmedClient, StubLuxmedBackend, FakeTime) => T
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
    body(client, stub, fake)

  /** Authenticate and return the client + stub for further operations. */
  private def withAuthenticatedClient[T](
      body: (LuxmedClient, StubLuxmedBackend, FakeTime) => T
  ): T =
    withClient: (client, stub, fake) =>
      LuxmedResponseScripts
        .realisticAuthFlow(
          accessToken = "ACCESS_1",
          refreshToken = "REFRESH_1",
          jwtToken = "JWT_TOKEN_1",
          expiresIn = 599
        )
        .foreach: (status, headers, body) =>
          stub.enqueue(status, headers, body)
      runAsync:
        client.authenticate()
      body(client, stub, fake)

  // -- Dictionary tests --

  test("cities hits the correct path and uses JWT auth"):
    withAuthenticatedClient: (client, stub, _) =>
      stub.enqueue(
        status = 200,
        body = fixture("cities.json")
      )
      val result = runAsync:
        client.cities()
      assert(result.isRight, s"expected success, got $result")
      assertEquals(
        requestPath(stub.requests.last),
        "/PatientPortal/NewPortal/Dictionary/cities"
      )
      assertEquals(
        requestHeader(stub.requests.last, "Authorization-Token"),
        Some("Bearer JWT_TOKEN_1")
      )

  test("serviceVariants hits the correct path"):
    withAuthenticatedClient: (client, stub, _) =>
      stub.enqueue(
        status = 200,
        body = fixture("service-variants.json")
      )
      val result = runAsync:
        client.serviceVariants()
      assert(result.isRight, s"expected success, got $result")
      assertEquals(
        requestPath(stub.requests.last),
        "/PatientPortal/NewPortal/Dictionary/serviceVariantsGroups"
      )

  test("facilitiesAndDoctors passes cityId and serviceVariantId"):
    withAuthenticatedClient: (client, stub, _) =>
      stub.enqueue(
        status = 200,
        body = fixture("facilities-and-doctors.json")
      )
      val result = runAsync:
        client.facilitiesAndDoctors(
          cityId = CityId(70),
          serviceVariantId = ServiceVariantId(4502)
        )
      assert(result.isRight, s"expected success, got $result")
      assertEquals(
        requestPath(stub.requests.last),
        "/PatientPortal/NewPortal/Dictionary/facilitiesAndDoctors"
      )
      val query = requestQuery(stub.requests.last)
      assertEquals(query, "cityId=70&serviceVariantId=4502")

  // -- Terms search tests --

  test("full terms search query contains expected parameters"):
    withAuthenticatedClient: (client, stub, _) =>
      val query = TermsQuery(
        cityId = CityId(70),
        serviceVariantId = ServiceVariantId(4502),
        searchDateFrom = LocalDate.parse("2026-08-03"),
        searchDateTo = LocalDate.parse("2026-08-10"),
        processId = UUID.fromString("00000000-0000-0000-0000-000000000123"),
        facilityIds = Some(FacilityId(78)),
        doctorIds = Some(DoctorId(111111))
      )
      stub.enqueue(
        status = 200,
        body = fixture("terms-dual-datetime.json")
      )
      val result = runAsync:
        client.searchTerms(query)
      assert(result.isRight, s"expected success, got $result")
      assertEquals(
        requestPath(stub.requests.last),
        "/PatientPortal/NewPortal/terms/index"
      )
      val params = stub.requests.last.uri.paramsMap
      assertEquals(params.get("searchPlace.id"), Some("70"))
      assertEquals(params.get("searchPlace.type"), Some("0"))
      assertEquals(params.get("serviceVariantId"), Some("4502"))
      assertEquals(params.get("languageId"), Some("10"))
      assertEquals(params.get("searchDateFrom"), Some("2026-08-03"))
      assertEquals(params.get("searchDateTo"), Some("2026-08-10"))
      assertEquals(params.get("searchDatePreset"), Some("14"))
      assertEquals(
        params.get("processId"),
        Some("00000000-0000-0000-0000-000000000123")
      )
      assertEquals(params.get("serviceVariantSource"), Some("0"))
      assertEquals(params.get("facilitiesIds"), Some("78"))
      assertEquals(params.get("doctorsIds"), Some("111111"))
      assertEquals(params.get("nextSearch"), Some("false"))
      assertEquals(
        params.get("searchByMedicalSpecialist"),
        Some("false")
      )
      assertEquals(params.get("delocalized"), Some("false"))

  test(
    "optional facility and doctor parameters are omitted when absent"
  ):
    withAuthenticatedClient: (client, stub, _) =>
      val query = TermsQuery(
        cityId = CityId(70),
        serviceVariantId = ServiceVariantId(4502),
        searchDateFrom = LocalDate.parse("2026-08-03"),
        searchDateTo = LocalDate.parse("2026-08-10"),
        processId = UUID.fromString("00000000-0000-0000-0000-000000000123"),
        facilityIds = None,
        doctorIds = None
      )
      stub.enqueue(
        status = 200,
        body = fixture("terms-dual-datetime.json")
      )
      val result = runAsync:
        client.searchTerms(query)
      assert(result.isRight, s"expected success, got $result")
      val queryParams = stub.requests.last.uri.paramsMap
      assert(!queryParams.contains("facilitiesIds"))
      assert(!queryParams.contains("doctorsIds"))
