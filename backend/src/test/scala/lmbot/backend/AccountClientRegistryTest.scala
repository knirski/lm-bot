package lmbot.backend

import java.time.{Duration, Instant}
import java.util.{Base64, UUID}

import lmbot.backend.account.{
  AccountClientFactory,
  AccountClientRegistry,
  AccountService
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
import lmbot.shared.domain.{AccountId, LinkAccountRequest, Role, UserId}
import sttp.model.Uri

/** Issue #35: before this registry, every request built its own Luxmed client
  * and `AccountGate`, so the per-account rate limiter only paced calls within a
  * single request. These tests pin client identity (and therefore gate
  * identity) and prove the pacing survives across calls.
  */
class AccountClientRegistryTest extends PostgresSuite with GearsTest:

  private val fixedDeviceUuid =
    UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")
  private val fixedInstant = Instant.parse("2026-07-30T08:00:00Z")
  private val key = MasterKey
    .fromBase64(Base64.getEncoder.encodeToString(Array.fill[Byte](32)(3)))
    .toOption
    .get
  private val crypto = AesGcm(key)

  private var nextUser = 0

  private def owner(prefix: String = "owner"): UserId =
    nextUser += 1
    UserId(
      UserRepo(xa)
        .insert(s"$prefix-$nextUser", s"Owner $nextUser", "hash", Role.Admin)
        .id
    )

  private def config(server: RealHttpLuxmedServer): LuxmedConfig =
    LuxmedConfig(
      oldApi = Uri.unsafeParse(s"${server.baseUri}/PatientPortalMobileAPI/api"),
      newApi = Uri.unsafeParse(s"${server.baseUri}/PatientPortal"),
      appVersion = AppVersion.unsafeFromString("5.8.0"),
      deviceUuid = UUID.fromString("00000000-0000-4000-8000-000000000003")
    )

  private def services(
      baseConfig: LuxmedConfig,
      minimumSpacing: Duration = Duration.ZERO,
      now: () => Instant = () => fixedInstant
  ): (AccountService, AccountClientRegistry) =
    val accounts = AccountRepo(xa)
    val factory = AccountClientFactory.production(
      xa = xa,
      accounts = accounts,
      baseConfig = baseConfig,
      crypto = crypto,
      minimumSpacing = minimumSpacing,
      now = now
    )
    val accountService = AccountService(
      accounts = accounts,
      clients = factory,
      crypto = crypto,
      uuidGenerator = () => fixedDeviceUuid,
      now = now
    )
    (
      accountService,
      AccountClientRegistry.production(accounts, factory)
    )

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
      ownerId: UserId
  ): AccountId =
    enqueue(server, LuxmedResponseScripts.realisticAuthFlow())
    val linked = runAsync:
      accountService.link(
        ownerId,
        LinkAccountRequest("Main", "user@example.com", "password123")
      )
    assert(linked.isRight, s"expected link success, got $linked")
    linked.toOption.get.id

  test("the same client is reused for the same account"):
    withServer: server =>
      val ownerId = owner()
      val (accountService, registry) = services(config(server))
      val accountId = linkedAccount(server, accountService, ownerId)

      val first = registry.forAccount(accountId)
      val second = registry.forAccount(accountId)

      assert(first.isRight, s"expected a client, got $first")
      assert(
        first.toOption.get eq second.toOption.get,
        "the registry must return one client per account"
      )

  test(
    "forOwnedAccount rejects a non-owner while the engine lookup still works"
  ):
    withServer: server =>
      val ownerId = owner()
      val other = owner("intruder")
      val (accountService, registry) = services(config(server))
      val accountId = linkedAccount(server, accountService, ownerId)

      assertEquals(
        registry.forOwnedAccount(other, accountId),
        Left(ApiError.NotFound)
      )
      assert(registry.forAccount(accountId).isRight)

  test("an unknown account is NotFound for both lookups"):
    withServer: server =>
      val (_, registry) = services(config(server))
      val missing = AccountId(999999L)

      assertEquals(registry.forAccount(missing), Left(ApiError.NotFound))
      assertEquals(
        registry.forOwnedAccount(owner(), missing),
        Left(ApiError.NotFound)
      )

  test("forget drops the cached client"):
    withServer: server =>
      val ownerId = owner()
      val (accountService, registry) = services(config(server))
      val accountId = linkedAccount(server, accountService, ownerId)
      val first = registry.forAccount(accountId).toOption.get

      registry.forget(accountId)

      val second = registry.forAccount(accountId).toOption.get
      assert(!(first eq second), "forget must drop the cached client")

  test("a successful refresh updates the account's last successful login"):
    withServer: server =>
      val linkedAt = Instant.parse("2026-07-30T08:00:00Z")
      val ownerId = owner()
      val (linking, _) = services(config(server), now = () => linkedAt)
      val accountId = linkedAccount(server, linking, ownerId)
      val (_, later) =
        services(config(server), now = () => linkedAt.plusSeconds(400))
      val client = later.forAccount(accountId).toOption.get
      // The stored session has 200 s left, so this call refreshes it.
      enqueue(
        server,
        List(LuxmedResponseScripts.oauthPasswordGrant(refreshToken = "RT2"))
      )
      enqueue(server, LuxmedResponseScripts.realisticBootstrapFlow())
      enqueueFixture(server, "cities.json")

      val result = runAsync(client.cities())

      assert(result.isRight, s"expected success, got $result")
      assertEquals(
        AccountRepo(xa)
          .findById(accountId)
          .flatMap(_.lastSuccessfulLogin)
          .map(_.toInstant),
        Some(linkedAt.plusSeconds(400))
      )

  test("two calls for one account are paced by the shared gate"):
    withServer: server =>
      val ownerId = owner()
      val (accountService, registry) = services(
        config(server),
        minimumSpacing = Duration.ofSeconds(1),
        now = () => Instant.now()
      )
      val accountId = linkedAccount(server, accountService, ownerId)
      val client = registry.forAccount(accountId).toOption.get
      enqueueFixture(server, "cities.json")
      enqueueFixture(server, "cities.json")

      val started = System.nanoTime()
      val first = runAsync(client.cities())
      val second = runAsync(client.cities())
      val elapsedMillis = (System.nanoTime() - started) / 1000000L

      assert(first.isRight, s"first call failed: $first")
      assert(second.isRight, s"second call failed: $second")
      assert(
        elapsedMillis >= 900L,
        s"expected the shared gate to pace the second call, took ${elapsedMillis}ms"
      )
