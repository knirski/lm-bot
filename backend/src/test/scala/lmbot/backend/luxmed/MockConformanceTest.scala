package lmbot.backend.luxmed

import java.time.Duration
import java.time.LocalDate
import java.util.UUID

import scala.io.{Codec, Source}

import lmbot.backend.config.{AppVersion, Secret}
import lmbot.backend.luxmed.model.*
import lmbot.backend.luxmed.support.{FakeTime, GearsTest, MockLuxmedServer}
import sttp.model.Uri

class MockConformanceTest extends munit.FunSuite with GearsTest:

  private val testUuid = UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")
  private val processUuid =
    UUID.fromString("00000000-0000-0000-0000-000000000123")

  private def fixture(name: String): String =
    val path = s"/luxmed/$name"
    val is = Option(getClass.getResourceAsStream(path))
      .getOrElse(throw IllegalArgumentException(s"Missing fixture: $path"))
    try Source.fromInputStream(is)(using Codec.UTF8).mkString
    finally is.close()

  test("full mock conformance flow produces expected ten-step fingerprint"):
    val mock = MockLuxmedServer()
    try
      val config = LuxmedConfig(
        oldApi = Uri.unsafeParse(s"${mock.baseUri}/PatientPortalMobileAPI/api"),
        newApi = Uri.unsafeParse(s"${mock.baseUri}/PatientPortal"),
        appVersion = AppVersion.unsafeFromString("4.44.0"),
        deviceUuid = testUuid
      )
      val fingerprints = Vector.newBuilder[WireFingerprint]
      val testObserver = new WireObserver:
        def observed(fp: WireFingerprint): Unit =
          fingerprints += fp
      val transport = LuxmedTransport(config, observer = testObserver)
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

      // Step 1: password grant
      mock.enqueue(
        status = 200,
        body = fixture("auth-password-success.json")
      )
      // Step 2: LogInToApp
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Set-Cookie" -> "ASP.NET_SessionId=sess1")
      )
      // Step 3: reservation page
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map(
          "Set-Cookie" -> "jwt=JWT1",
          "Authorization-Token" -> "Bearer JWT_TOKEN_1"
        )
      )

      val authResult = runAsync:
        client.authenticate()
      assert(authResult.isRight, s"auth failed: $authResult")

      // Step 4: refresh grant
      mock.enqueue(
        status = 200,
        body = fixture("auth-refresh-success.json")
      )
      // Step 5: refreshed LogInToApp
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map("Set-Cookie" -> "ASP.NET_SessionId=sess2")
      )
      // Step 6: refreshed reservation page
      mock.enqueue(
        status = 200,
        body = "",
        headers = Map(
          "Set-Cookie" -> "jwt=JWT2",
          "Authorization-Token" -> "Bearer JWT_TOKEN_2"
        )
      )

      fake.advance(java.time.Duration.ofSeconds(301))
      val refreshResult = runAsync:
        client.refreshNowForConformance()
      assert(refreshResult.isRight, s"refresh failed: $refreshResult")

      // Step 7: cities
      mock.enqueue(status = 200, body = fixture("cities.json"))
      val citiesResult = runAsync:
        client.cities()
      assert(citiesResult.isRight, s"cities failed: $citiesResult")

      // Step 8: service variants
      mock.enqueue(
        status = 200,
        body = fixture("service-variants.json")
      )
      val svResult = runAsync:
        client.serviceVariants()
      assert(svResult.isRight, s"serviceVariants failed: $svResult")

      // Step 9: terms search
      val termsQuery = TermsQuery(
        cityId = CityId(70),
        serviceVariantId = ServiceVariantId(4502),
        searchDateFrom = LocalDate.parse("2026-08-03"),
        searchDateTo = LocalDate.parse("2026-08-10"),
        processId = processUuid,
        facilityIds = Some(FacilityId(78)),
        doctorIds = Some(DoctorId(111111))
      )
      mock.enqueue(
        status = 200,
        body = fixture("terms-dual-datetime.json")
      )
      val termsResult = runAsync:
        client.searchTerms(termsQuery)
      assert(termsResult.isRight, s"terms search failed: $termsResult")

      // Step 10: XSRF token
      mock.enqueue(
        status = 200,
        body = fixture("forgery-token.json")
      )
      val xsrfResult = runAsync:
        client.getXsrfToken()
      assert(xsrfResult.isRight, s"XSRF failed: $xsrfResult")

      val allFingerprints = fingerprints.result()
      assertEquals(
        allFingerprints.size,
        10,
        s"expected 10 fingerprints, got ${allFingerprints.size}"
      )

      // Verify all request paths
      val requests = mock.requests
      assertEquals(requests.size, 10)
      assertEquals(
        requests(0).path,
        "/PatientPortalMobileAPI/api/token"
      ) // password grant
      assertEquals(
        requests(1).path,
        "/PatientPortal/Account/LogInToApp"
      ) // LogInToApp
      assertEquals(
        requests(2).path,
        "/PatientPortal/NewPortal/Page/Reservation"
      ) // reservation page
      assertEquals(
        requests(3).path,
        "/PatientPortalMobileAPI/api/token"
      ) // refresh grant
      assertEquals(
        requests(4).path,
        "/PatientPortal/Account/LogInToApp"
      ) // refreshed LogInToApp
      assertEquals(
        requests(5).path,
        "/PatientPortal/NewPortal/Page/Reservation"
      ) // refreshed reservation
      assertEquals(
        requests(6).path,
        "/PatientPortal/NewPortal/Dictionary/cities"
      ) // cities
      assertEquals(
        requests(7).path,
        "/PatientPortal/NewPortal/Dictionary/serviceVariantsGroups"
      ) // service variants
      assertEquals(
        requests(8).path,
        "/PatientPortal/NewPortal/terms/index"
      ) // terms search
      assertEquals(
        requests(9).path,
        "/PatientPortal/security/getforgerytoken"
      ) // XSRF token

      // Verify auth header on authenticated requests
      // Bootstrap requests (LogInToApp, ReservationPage) use Authorization (OAuth access token)
      // All other authenticated requests use Authorization-Token (JWT)
      val bootstrapPaths = Set(
        "/PatientPortal/Account/LogInToApp",
        "/PatientPortal/NewPortal/Page/Reservation"
      )
      (1 to 9).foreach: i =>
        val req = requests(i)
        if req.path != "/PatientPortalMobileAPI/api/token" then
          if bootstrapPaths.contains(req.path) then
            // Bootstrap uses Authorization header with raw OAuth access token (no "Bearer " prefix)
            val authHeader = req.headers
              .collectFirst:
                case (k, vs) if k.equalsIgnoreCase("Authorization") =>
                  vs.head
              .getOrElse("")
            assert(
              authHeader.startsWith("ACCESS_"),
              s"request $i (${req.path}) missing Authorization header"
            )
          else
            val authHeader = req.headers
              .collectFirst:
                case (k, vs) if k.equalsIgnoreCase("Authorization-Token") =>
                  vs.head
              .getOrElse("")
            assert(
              authHeader.startsWith("Bearer "),
              s"request $i (${req.path}) missing Authorization-Token header"
            )
    finally mock.close()
