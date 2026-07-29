package lmbot.backend.luxmed

import java.time.{Duration, LocalTime}
import java.util.UUID

import scala.io.{Codec, Source}

import lmbot.backend.config.{AppVersion, Secret}
import lmbot.backend.luxmed.model.*
import lmbot.backend.luxmed.support.{
  FakeTime,
  GearsTest,
  MockLuxmedServer,
  MockResponse,
  RecordedRequest
}
import sttp.model.Uri

class ReservationPrimitivesTest extends munit.FunSuite with GearsTest:

  private val testUuid = UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")

  private def fixture(name: String): String =
    val path = s"/luxmed/$name"
    val is = Option(getClass.getResourceAsStream(path))
      .getOrElse(throw IllegalArgumentException(s"Missing fixture: $path"))
    try Source.fromInputStream(is)(using Codec.UTF8).mkString
    finally is.close()

  private def header(
      request: RecordedRequest,
      name: String
  ): Option[String] =
    request.headers.collectFirst:
      case (key, values) if key.equalsIgnoreCase(name) => values.head

  private def withAuthenticatedClient[T](
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
    finally mock.close()

  // -- XSRF token tests --

  test("getXsrfToken returns a forgery token and merges cookies"):
    withAuthenticatedClient: (client, mock, _) =>
      mock.enqueue(
        MockResponse(
          status = 200,
          body = fixture("forgery-token.json"),
          headers = Map(
            "Set-Cookie" -> List(
              "XSRF-TOKEN=abc123",
              "ASP.NET_SessionId=new_sess"
            )
          )
        )
      )
      val result = runAsync:
        client.getXsrfToken()
      assert(result.isRight, s"expected success, got $result")
      val (token, mergedCookies) = result.toOption.get
      assert(token.token.value.nonEmpty)
      assertEquals(
        mergedCookies.get("XSRF-TOKEN").map(_.value),
        Some("abc123")
      )
      assertEquals(
        mergedCookies.get("ASP.NET_SessionId").map(_.value),
        Some("new_sess")
      )
      assertEquals(
        mock.requests.last.path,
        "/PatientPortal/security/getforgerytoken"
      )
      assertEquals(
        header(mock.requests.last, "Authorization-Token"),
        Some("Bearer JWT_TOKEN_1")
      )

  // -- Lock term tests --

  test("lockTerm sends JSON body with XSRF protection"):
    withAuthenticatedClient: (client, mock, _) =>
      val xsrfToken = XsrfToken(Secret("XSRF_1"))
      val xsrfCookies = CookieJar("XSRF-TOKEN" -> Secret("xsrf_cookie"))
      val lockRequest = LockTermRequest(
        date = "2026-08-03",
        doctorId = DoctorId(11111),
        facilityId = FacilityId(78),
        impedimentText = None,
        isAdditional = false,
        isImpediment = false,
        isPreparationRequired = false,
        isTelemedicine = false,
        roomId = 30L,
        scheduleId = ScheduleId(40L),
        serviceVariantId = ServiceVariantId(50L),
        timeFrom = "09:00",
        timeTo = "09:15"
      )
      mock.enqueue(
        status = 200,
        body = fixture("lock-success.json")
      )
      val result = runAsync:
        client.lockTerm(lockRequest, xsrfToken, xsrfCookies)
      assert(result.isRight, s"expected success, got $result")
      val req = mock.requests.last
      assertEquals(
        req.path,
        "/PatientPortal/NewPortal/reservation/lockterm"
      )
      assertEquals(
        header(req, "Authorization-Token"),
        Some("Bearer JWT_TOKEN_1")
      )
      assertEquals(header(req, "xsrf-token"), Some("XSRF_1"))
      assert(
        req.body.contains("\"serviceVariantId\":50"),
        s"body should contain serviceVariantId: ${req.body}"
      )
      assert(
        req.body.contains("\"scheduleId\":40"),
        s"body should contain scheduleId: ${req.body}"
      )
      assert(
        !req.body.contains("temporary_reservation_id"),
        "body must not contain snake_case keys"
      )
      val response = result.toOption.get
      assertEquals(response.value.temporaryReservationId.value, 222222L)

  // -- Confirm tests --

  test("confirm sends JSON body with XSRF protection"):
    withAuthenticatedClient: (client, mock, _) =>
      val xsrfToken = XsrfToken(Secret("XSRF_1"))
      val xsrfCookies = CookieJar("XSRF-TOKEN" -> Secret("xsrf_cookie"))
      val confirmRequest = ConfirmRequest(
        date = "2026-08-03",
        doctorId = DoctorId(11111),
        facilityId = FacilityId(78),
        roomId = 30L,
        scheduleId = ScheduleId(40L),
        serviceVariantId = ServiceVariantId(50L),
        temporaryReservationId = ReservationId(222222L),
        timeFrom = LocalTime.parse("09:00"),
        valuation = Valuation(
          alternativePrice = None,
          contractId = Some(333333L),
          isExternalReferralAllowed = false,
          isReferralRequired = false,
          payerId = Some(44444L),
          price = Some(0.0),
          productElementId = Some(555555L),
          productId = Some(666666L),
          productInContractId = Some(777777L),
          requireReferralForPP = false,
          valuationType = 1L
        )
      )
      mock.enqueue(
        status = 200,
        body = fixture("confirm-success.json")
      )
      val result = runAsync:
        client.confirm(confirmRequest, xsrfToken, xsrfCookies)
      assert(result.isRight, s"expected success, got $result")
      val req = mock.requests.last
      assertEquals(
        req.path,
        "/PatientPortal/NewPortal/reservation/confirm"
      )
      assertEquals(header(req, "xsrf-token"), Some("XSRF_1"))
      val response = result.toOption.get
      assertEquals(response.value.reservationId.value, 2222222L)

  // -- Release term tests --

  test("releaseTerm sends empty JSON body with reservationId as query param"):
    withAuthenticatedClient: (client, mock, _) =>
      val xsrfToken = XsrfToken(Secret("XSRF_1"))
      val xsrfCookies = CookieJar("XSRF-TOKEN" -> Secret("xsrf_cookie"))
      // Release endpoint returns 200 with empty body
      mock.enqueue(
        status = 200,
        body = ""
      )
      val result = runAsync:
        client.releaseTerm(
          ReservationId(222222L),
          xsrfToken,
          xsrfCookies
        )
      assert(result.isRight, s"expected success, got $result")
      val req = mock.requests.last
      assertEquals(
        req.path,
        "/PatientPortal/NewPortal/reservation/releaseterm"
      )
      assertEquals(req.rawQuery, Some("reservationId=222222"))
      assertEquals(req.body, "{}")
      assertEquals(header(req, "xsrf-token"), Some("XSRF_1"))
