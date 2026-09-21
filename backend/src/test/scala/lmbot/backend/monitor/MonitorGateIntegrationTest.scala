package lmbot.backend.monitor

import java.time.{Duration, Instant}
import java.util.concurrent.CancellationException
import java.util.{Base64, UUID}

import gears.async.{Async, UnboundedChannel}
import lmbot.backend.account.{
  AccountClientFactory,
  AccountClientRegistry,
  AccountService
}
import lmbot.backend.config.{AppVersion, MasterKey}
import lmbot.backend.crypto.AesGcm
import lmbot.backend.db.{AccountRepo, MonitorEventRepo, MonitorRepo}
import lmbot.backend.luxmed.LuxmedConfig
import lmbot.backend.luxmed.support.{
  LuxmedResponseScripts,
  MockResponse,
  RealHttpLuxmedServer
}
import lmbot.backend.support.Sleeper
import lmbot.shared.domain.LinkAccountRequest
import sttp.model.Uri

/** Spec §5.5: "monitors sharing a Luxmed account queue behind its rate limiter
  * rather than running concurrently". The registry tests prove that one client
  * (and therefore one gate) is reused; this proves the queueing itself, with
  * two monitors on one account and a recording loopback server.
  */
class MonitorGateIntegrationTest extends MonitorFixtures:

  private val crypto = AesGcm(
    MasterKey
      .fromBase64(Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7)))
      .toOption
      .get
  )

  private def fixture(name: String): String =
    val path = s"/luxmed/$name"
    val stream = Option(getClass.getResourceAsStream(path))
      .getOrElse(throw IllegalArgumentException(s"Missing fixture: $path"))
    try
      scala.io.Source
        .fromInputStream(stream)(using scala.io.Codec.UTF8)
        .mkString
    finally stream.close()

  test("monitors sharing an account queue behind one gate, never concurrently"):
    val server = RealHttpLuxmedServer()
    try
      val minimumSpacing = Duration.ofMillis(300)
      val accounts = AccountRepo(xa)
      val factory = AccountClientFactory.production(
        xa = xa,
        accounts = accounts,
        baseConfig = LuxmedConfig(
          oldApi =
            Uri.unsafeParse(s"${server.baseUri}/PatientPortalMobileAPI/api"),
          newApi = Uri.unsafeParse(s"${server.baseUri}/PatientPortal"),
          appVersion = AppVersion.unsafeFromString("5.8.0"),
          deviceUuid = UUID.randomUUID()
        ),
        crypto = crypto,
        minimumSpacing = minimumSpacing,
        now = () => Instant.now()
      )
      val registry = AccountClientRegistry.production(accounts, factory)
      val accountService = AccountService(accounts, factory, crypto)
      LuxmedResponseScripts
        .realisticAuthFlow()
        .foreach(response =>
          server.enqueue(
            MockResponse(
              response.status,
              response.headers.groupMap(_._1)(_._2),
              response.body
            )
          )
        )
      val linked = runAsync(
        accountService.link(
          anOwner(),
          LinkAccountRequest("Main", "user@example.com", "password123")
        )
      )
      assert(linked.isRight, s"expected link success, got $linked")
      val accountId = linked.toOption.get.id.value

      // Two monitors on the same account, each checking immediately. Distinct
      // service ids let the assertions tell their requests apart.
      aMonitor(accountId, intervalMinutes = 5, serviceId = 201L)
      aMonitor(accountId, intervalMinutes = 5, serviceId = 202L)
      (1 to 4).foreach: _ =>
        server.enqueue(
          200,
          Map("Content-Type" -> "application/json"),
          fixture("terms-dual-datetime.json")
        )

      val stop = UnboundedChannel[Unit]()
      val notifications = notifier(None)
      val eventRepo = MonitorEventRepo(xa)
      val monitorRepo = MonitorRepo(xa)
      // The engine clock stays at the fixture time so the monitors are
      // inside their date range; the gate itself uses the factory's real
      // clock, which is what the spacing assertion measures.
      val clock = () => fixedNow
      def requested(serviceId: Long): Boolean =
        server.requests.exists: request =>
          request.path.endsWith("/terms/index") &&
            request.rawQuery.exists(_.contains(s"serviceVariantId=$serviceId"))
      val engineSleeper = new Sleeper:
        private var calls = 0
        def sleep(duration: Duration)(using Async): Unit =
          calls += 1
          if requested(201L) && requested(202L) then
            stop.close()
            throw new CancellationException()
          if calls >= 300 then
            stop.close()
            throw IllegalStateException(
              "the gate test never observed both monitors checking"
            )
          // Short waits so the loop re-checks the request count; the real
          // gate spacing is 300 ms and is asserted below.
          Sleeper.Default.sleep(Duration.ofMillis(50))
      val engine = MonitorEngine(
        monitorRepo,
        accounts,
        MonitorCheck(
          LuxmedSlotSearch(registry.forAccount),
          eventRepo,
          notifications,
          clock
        ),
        notifications,
        AccountHealth(accounts, monitorRepo, eventRepo, notifications, clock),
        eventRepo,
        engineSleeper,
        () => 0.5,
        clock
      )

      runAsync(engine.run(stop))

      val terms = server.requests.filter(_.path.endsWith("/terms/index"))
      def firstFor(
          serviceId: Long
      ): Option[lmbot.backend.luxmed.support.RecordedRequest] =
        terms.find(
          _.rawQuery.exists(_.contains(s"serviceVariantId=$serviceId"))
        )
      val first = firstFor(201L).getOrElse(fail("monitor 201 never searched"))
      val other = firstFor(202L).getOrElse(fail("monitor 202 never searched"))
      val (earlier, later) =
        if first.receivedAt.isBefore(other.receivedAt) then (first, other)
        else (other, first)
      assert(
        !later.receivedAt.isBefore(earlier.completedAt),
        s"requests overlapped: $earlier then $later"
      )
      val spacing =
        later.receivedAt.toEpochMilli - earlier.receivedAt.toEpochMilli
      assert(
        spacing >= 250L,
        s"expected at least ~300 ms between the two monitors' requests, got ${spacing}ms"
      )
    finally server.close()
