package lmbot.backend

import java.time.{Duration, Instant}
import java.util.{Base64, UUID}

import lmbot.backend.account.{
  AccountClientFactory,
  AccountService,
  DictionaryService
}
import lmbot.backend.config.{AppVersion, MasterKey}
import lmbot.backend.crypto.AesGcm
import lmbot.backend.db.{AccountRepo, UserRepo}
import lmbot.backend.luxmed.LuxmedConfig
import lmbot.backend.luxmed.support.{
  GearsTest,
  LuxmedResponseScripts,
  MockResponse,
  RealHttpLuxmedServer
}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.api.ApiError
import lmbot.shared.domain.{
  AccountId,
  DictionaryCity,
  DictionaryDoctor,
  DictionaryFacility,
  DictionaryService as DictionaryServiceItem,
  FacilitiesDoctorsResponse,
  LinkAccountRequest,
  Role
}
import sttp.model.Uri

/** Exercises `DictionaryService`'s mapping of Luxmed wire models into shared
  * DTOs, and its account-ownership check, over a real linked account and a real
  * loopback Luxmed server (modelled on `AccountServiceTest`).
  */
class DictionaryServiceTest extends PostgresSuite with GearsTest:

  private val fixedDeviceUuid =
    UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")
  private val fixedInstant = Instant.parse("2026-07-30T08:00:00Z")
  private val key = MasterKey
    .fromBase64(Base64.getEncoder.encodeToString(Array.fill[Byte](32)(3)))
    .toOption
    .get
  private val crypto = AesGcm(key)

  private var nextUser = 0

  private def owner(prefix: String = "owner"): Long =
    nextUser += 1
    UserRepo(xa)
      .insert(s"$prefix-$nextUser", s"Owner $nextUser", "hash", Role.Admin)
      .id

  private def config(server: RealHttpLuxmedServer): LuxmedConfig =
    LuxmedConfig(
      oldApi = Uri.unsafeParse(s"${server.baseUri}/PatientPortalMobileAPI/api"),
      newApi = Uri.unsafeParse(s"${server.baseUri}/PatientPortal"),
      appVersion = AppVersion.unsafeFromString("4.44.0"),
      deviceUuid = UUID.fromString("00000000-0000-4000-8000-000000000002")
    )

  private def services(
      baseConfig: LuxmedConfig
  ): (AccountService, DictionaryService) =
    val accounts = AccountRepo(xa)
    val factory = AccountClientFactory.production(
      xa = xa,
      accounts = accounts,
      baseConfig = baseConfig,
      crypto = crypto,
      minimumSpacing = Duration.ZERO,
      now = () => fixedInstant
    )
    val accountService = AccountService(
      accounts = accounts,
      clients = factory,
      crypto = crypto,
      uuidGenerator = () => fixedDeviceUuid,
      now = () => fixedInstant
    )
    (accountService, DictionaryService(factory))

  private def withServer[A](body: RealHttpLuxmedServer => A): A =
    val server = RealHttpLuxmedServer()
    try body(server)
    finally server.close()

  private def enqueue(
      server: RealHttpLuxmedServer,
      responses: List[LuxmedResponseScripts.Response]
  ): Unit =
    responses.foreach: response =>
      server.enqueue(
        MockResponse(
          response.status,
          response.headers.groupMap(_._1)(_._2),
          response.body
        )
      )

  private def enqueueFixture(server: RealHttpLuxmedServer, name: String): Unit =
    server.enqueue(
      MockResponse(
        200,
        Map("Content-Type" -> List("application/json")),
        fixture(name)
      )
    )

  private def fixture(name: String): String =
    val path = s"/luxmed/$name"
    val is = Option(getClass.getResourceAsStream(path))
      .getOrElse(throw IllegalArgumentException(s"Missing fixture: $path"))
    try scala.io.Source.fromInputStream(is)(using scala.io.Codec.UTF8).mkString
    finally is.close()

  private def linkedAccount(
      server: RealHttpLuxmedServer,
      accountService: AccountService,
      ownerId: Long
  ): AccountId =
    enqueue(server, LuxmedResponseScripts.realisticAuthFlow())
    val linked = runAsync:
      accountService.link(
        ownerId,
        LinkAccountRequest("Main", "user@example.com", "password123")
      )
    assert(linked.isRight, s"expected link success, got $linked")
    linked.toOption.get.id

  test("cities maps Luxmed cities to shared DictionaryCity values"):
    withServer: server =>
      val ownerId = owner()
      val (accountService, dictionaries) = services(config(server))
      val accountId = linkedAccount(server, accountService, ownerId)
      enqueueFixture(server, "cities.json")

      val result = runAsync(dictionaries.cities(ownerId, accountId))

      assertEquals(
        result,
        Right(
          List(
            DictionaryCity(70, "Białystok"),
            DictionaryCity(12, "Bielsk Podlaski"),
            DictionaryCity(100, "Bielsko-Biała")
          )
        )
      )

  test(
    "services flattens the recursive tree, retaining parent context in labels"
  ):
    withServer: server =>
      val ownerId = owner()
      val (accountService, dictionaries) = services(config(server))
      val accountId = linkedAccount(server, accountService, ownerId)
      enqueueFixture(server, "service-variants.json")

      val result = runAsync(dictionaries.services(ownerId, accountId))

      assertEquals(
        result,
        Right(
          List(
            DictionaryServiceItem(2, "Most popular"),
            DictionaryServiceItem(
              4502,
              "Most popular > Consultation with a general practitioner"
            ),
            DictionaryServiceItem(
              4480,
              "Most popular > Gynaecological consultation"
            ),
            DictionaryServiceItem(1, "On-site consultations"),
            DictionaryServiceItem(
              4387,
              "On-site consultations > Allergologist consultation"
            )
          )
        )
      )

  test(
    "two variants sharing a leaf name under different parents stay distinguishable"
  ):
    withServer: server =>
      val ownerId = owner()
      val (accountService, dictionaries) = services(config(server))
      val accountId = linkedAccount(server, accountService, ownerId)
      server.enqueue(
        MockResponse(
          200,
          Map("Content-Type" -> List("application/json")),
          """[{"id":1,"name":"Dermatologia","expanded":true,"isTelemedicine":false,"paymentType":0,"children":[{"id":10,"name":"Konsultacja pierwsza","expanded":false,"isTelemedicine":false,"paymentType":0,"children":[]}]},{"id":2,"name":"Kardiologia","expanded":true,"isTelemedicine":false,"paymentType":0,"children":[{"id":20,"name":"Konsultacja pierwsza","expanded":false,"isTelemedicine":false,"paymentType":0,"children":[]}]}]"""
        )
      )

      val result = runAsync(dictionaries.services(ownerId, accountId))
      val labels = result.toOption.get.map(_.name)

      assertEquals(
        labels,
        List(
          "Dermatologia",
          "Dermatologia > Konsultacja pierwsza",
          "Kardiologia",
          "Kardiologia > Konsultacja pierwsza"
        )
      )
      assertEquals(labels.distinct.size, labels.size)

  test("facilitiesDoctors maps facilities and doctors to shared named types"):
    withServer: server =>
      val ownerId = owner()
      val (accountService, dictionaries) = services(config(server))
      val accountId = linkedAccount(server, accountService, ownerId)
      enqueueFixture(server, "facilities-and-doctors.json")

      val result = runAsync:
        dictionaries.facilitiesDoctors(ownerId, accountId, 70L, 4502L)

      assertEquals(
        result,
        Right(
          FacilitiesDoctorsResponse(
            facilities = List(
              DictionaryFacility(78, "ul. Fabryczna 6"),
              DictionaryFacility(127, "ul. Kwidzyńska 6")
            ),
            doctors = List(
              DictionaryDoctor(111111, "dr n. med. TARAS SHEVCHENKO"),
              DictionaryDoctor(22222, "lek. med. VLADIMIR ZELENSKIY")
            )
          )
        )
      )
      val query = server.requests.last.rawQuery.getOrElse("")
      assert(query.contains("cityId=70"))
      assert(query.contains("serviceVariantId=4502"))

  test("cross-owner account returns NotFound and never proxies to Luxmed"):
    withServer: server =>
      val firstOwner = owner("first")
      val secondOwner = owner("second")
      val (accountService, dictionaries) = services(config(server))
      val accountId = linkedAccount(server, accountService, firstOwner)
      val requestsAfterLink = server.requests.size

      val result = runAsync(dictionaries.cities(secondOwner, accountId))

      assertEquals(result, Left(ApiError.NotFound))
      assertEquals(server.requests.size, requestsAfterLink)
