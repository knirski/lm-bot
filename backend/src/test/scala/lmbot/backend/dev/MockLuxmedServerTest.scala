package lmbot.backend.luxmed

import java.time.{Duration, LocalDate}
import java.util.UUID

import lmbot.backend.config.{AppVersion, Secret}
import lmbot.backend.dev.MockLuxmedServer
import lmbot.backend.luxmed.model.*
import lmbot.backend.luxmed.support.{FakeTime, GearsTest}
import sttp.client3.HttpClientSyncBackend
import sttp.client3.basicRequest
import sttp.model.StatusCode

/** HTTP boundary coverage for the development mock server. */
class MockLuxmedServerTest extends munit.FunSuite with GearsTest:

  private val http = HttpClientSyncBackend()

  test("routes auth and dictionaries by path in any request order"):
    val mock = MockLuxmedServer.start()
    try
      val client = clientFor(mock)
      val result = runAsync:
        for
          services <- client.serviceVariants()
          cities <- client.cities()
          facilities <- client.facilitiesAndDoctors(
            CityId(1),
            ServiceVariantId(4502)
          )
        yield (cities, services, facilities)

      result match
        case Left(error) => fail(s"expected mock dictionaries, got $error")
        case Right((cities, services, facilities)) =>
          assert(cities.exists(_.name == "Warsaw"))
          assert(
            services.flatMap(_.flatten).exists(_.name == "General practitioner")
          )
          assert(facilities.facilities.exists(_.id.value == 100))
          assert(facilities.doctors.exists(_.id.value == 200))
    finally mock.close()

  test("returns not found for an unmodeled Luxmed path"):
    val mock = MockLuxmedServer.start()
    try
      val response =
        basicRequest.get(mock.newApi.addPath(Seq("unknown"))).send(http)
      assertEquals(response.code, StatusCode.NotFound)
    finally mock.close()

  test("serves a TermsResponse through LuxmedClient"):
    val mock = MockLuxmedServer.start()
    try
      val client = clientFor(mock)
      val result = runAsync:
        client.searchTerms(
          TermsQuery(
            cityId = CityId(1),
            serviceVariantId = ServiceVariantId(4502),
            searchDateFrom = LocalDate.parse("2026-08-03"),
            searchDateTo = LocalDate.parse("2026-08-10")
          )
        )
      assert(result.isRight, s"expected terms response, got $result")
      assert(result.toOption.exists(_.termsForService.termsForDays.nonEmpty))
    finally mock.close()

  private def clientFor(mock: MockLuxmedServer): LuxmedClient =
    val config = LuxmedConfig(
      oldApi = mock.oldApi,
      newApi = mock.newApi,
      appVersion = AppVersion.unsafeFromString("4.44.0"),
      deviceUuid = UUID.fromString("00000000-0000-4000-8000-000000000008")
    )
    val transport = LuxmedTransport.withBackend(config, http)
    val fakeTime = FakeTime()
    LuxmedClient(
      transport,
      Credentials("mock@example.test", Secret("password")),
      AccountGate(Duration.ZERO, fakeTime.now, fakeTime.sleeper),
      InMemorySessionStore(),
      now = fakeTime.now
    )
