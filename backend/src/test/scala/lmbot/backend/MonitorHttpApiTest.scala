package lmbot.backend

import java.sql.{Date as SqlDate, Time as SqlTime}
import java.time.{Duration, Instant, LocalDate, LocalTime, OffsetDateTime}

import scala.compiletime.uninitialized

import com.sun.net.httpserver.HttpServer
import lmbot.backend.auth.{AuthService, Passwords}
import lmbot.backend.db.{
  AccountRepo,
  LuxmedAccountRow,
  MonitorRepo,
  MonitorRow,
  SessionRepo,
  UserRepo
}
import lmbot.backend.http.{AuthRoutes, HealthRoutes, MonitorRoutes, Server}
import lmbot.backend.monitor.MonitorService
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.Role
import sttp.client3.*
import sttp.model.{StatusCode, Uri}

/** Drives the real server over real HTTP against real Postgres (modelled on
  * `AccountHttpApiTest`). `MonitorRoutes` never touches Luxmed, so no loopback
  * Luxmed server is needed here.
  */
class MonitorHttpApiTest extends PostgresSuite:

  private val ttl = Duration.ofDays(7)
  private val fixedInstant = Instant.parse("2026-07-30T08:00:00Z")
  private val fixedOffset = OffsetDateTime.parse("2026-07-30T10:00:00+02:00")

  private var server: HttpServer = uninitialized
  private var baseUri: Uri = uninitialized
  private val http = HttpClientSyncBackend()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    val auth =
      AuthService(
        UserRepo(xa),
        SessionRepo(xa),
        ttl,
        () => OffsetDateTime.now()
      )
    val authRoutes = AuthRoutes(auth, cookieSecure = false, sessionTtl = ttl)
    val monitorService =
      MonitorService(MonitorRepo(xa), AccountRepo(xa), () => fixedInstant)
    val monitorRoutes = MonitorRoutes(auth, monitorService)
    // Port 0 lets the OS choose, so tests never collide.
    server = Server.start(
      "127.0.0.1",
      0,
      HealthRoutes.endpoints ++ authRoutes.endpoints ++ monitorRoutes.endpoints
    )
    baseUri = uri"http://127.0.0.1:${server.getAddress.getPort}"

  override def afterEach(context: AfterEach): Unit =
    if server != null then server.stop(0)
    super.afterEach(context)

  private def aUser(
      username: String,
      password: String,
      role: Role = Role.User
  ): Long =
    UserRepo(xa)
      .insert(username, "Krzysiek", Passwords.hash(password), role)
      .id

  private def login(username: String, password: String) =
    basicRequest
      .post(uri"$baseUri/api/auth/login")
      .body(s"""{"username":"$username","password":"$password"}""")
      .contentType("application/json")
      .send(http)

  private def sessionCookieValue(
      response: Response[Either[String, String]]
  ): String =
    response
      .headers("Set-Cookie")
      .flatMap(_.split(";").headOption)
      .collectFirst {
        case kv if kv.startsWith("lmbot_session=") =>
          kv.drop("lmbot_session=".length)
      }
      .getOrElse(fail("no session cookie in login response"))

  private def loggedIn(
      username: String = "krzysiek",
      password: String = "s3cret",
      role: Role = Role.User
  ): (Long, String) =
    val id = aUser(username, password, role)
    val token = sessionCookieValue(login(username, password))
    (id, token)

  private def insertAccount(ownerId: Long, label: String = "Main"): Long =
    val repo = AccountRepo(xa)
    val id = repo.reserveId()
    repo.insert(
      LuxmedAccountRow(
        id.value,
        ownerId,
        label,
        "encrypted-username",
        "encrypted-password",
        "encrypted-device",
        None,
        "active",
        None,
        None,
        fixedOffset,
        fixedOffset
      )
    )
    id.value

  private def insertMonitorRow(accountId: Long, state: String): Long =
    val repo = MonitorRepo(xa)
    val id = repo.reserveId()
    repo.insert(
      MonitorRow(
        id,
        accountId,
        "Seed monitor",
        3L,
        "Warsaw",
        42L,
        "Dermatology",
        List(9L),
        List("Central"),
        Nil,
        Nil,
        SqlDate.valueOf(LocalDate.parse("2026-08-01")),
        SqlDate.valueOf(LocalDate.parse("2026-08-31")),
        SqlTime.valueOf(LocalTime.parse("08:00")),
        SqlTime.valueOf(LocalTime.parse("16:00")),
        0x15.toShort,
        false,
        10,
        state,
        fixedOffset,
        fixedOffset
      )
    )
    id

  private def draftBody(
      accountId: Long,
      name: String = "Dermatologist",
      intervalMinutes: Int = 10,
      dateFrom: String = "2026-08-01",
      dateTo: String = "2026-08-31"
  ): String =
    s"""{"accountId":$accountId,"name":"$name","city":{"id":3,"name":"Warsaw"},"service":{"id":42,"name":"Dermatology"},"facilities":[{"id":9,"name":"Central"}],"doctors":[],"dateFrom":"$dateFrom","dateTo":"$dateTo","timeFrom":"08:00","timeTo":"16:00","daysOfWeek":["Monday","Wednesday","Friday"],"autoBook":false,"intervalMinutes":$intervalMinutes}"""

  private def viewJson(
      id: Long,
      accountId: Long,
      name: String,
      state: String,
      intervalMinutes: Int = 10,
      dateFrom: String = "2026-08-01",
      dateTo: String = "2026-08-31"
  ): String =
    s"""{"id":$id,"accountId":$accountId,"name":"$name","state":"$state","city":{"id":3,"name":"Warsaw"},"service":{"id":42,"name":"Dermatology"},"facilities":[{"id":9,"name":"Central"}],"doctors":[],"dateFrom":"$dateFrom","dateTo":"$dateTo","timeFrom":"08:00","timeTo":"16:00","daysOfWeek":["Monday","Wednesday","Friday"],"autoBook":false,"intervalMinutes":$intervalMinutes,"createdAt":"2026-07-30T08:00:00Z","updatedAt":"2026-07-30T08:00:00Z"}"""

  /** `updateOwned`/`transitionOwned` stamp `updated_at` with the database's own
    * `now()` (see `MonitorRepo`), not the service's injected clock, so a
    * response that went through an update or a state transition carries a real
    * wall-clock timestamp that cannot be pinned literally. Normalizing it out
    * keeps every other byte of the response asserted exactly.
    */
  private def normalizeUpdatedAt(body: String): String =
    """"updatedAt":"[^"]*"""".r
      .replaceAllIn(body, """"updatedAt":"<TS>"""")

  private def createMonitor(
      token: String,
      accountId: Long,
      name: String = "Dermatologist",
      intervalMinutes: Int = 10,
      dateFrom: String = "2026-08-01",
      dateTo: String = "2026-08-31"
  ) =
    basicRequest
      .post(uri"$baseUri/api/monitors")
      .cookie("lmbot_session", token)
      .body(draftBody(accountId, name, intervalMinutes, dateFrom, dateTo))
      .contentType("application/json")
      .send(http)

  // -- create --------------------------------------------------------------

  test("create is 401 without a session cookie"):
    val (ownerId, _) = loggedIn()
    val accountId = insertAccount(ownerId)
    val r = basicRequest
      .post(uri"$baseUri/api/monitors")
      .body(draftBody(accountId))
      .contentType("application/json")
      .send(http)

    assertEquals(r.code, StatusCode.Unauthorized)
    assertEquals(
      r.body,
      Left("""{"code":"unauthorized","message":"Not authenticated"}""")
    )

  test("create succeeds and returns the literal monitor view JSON"):
    val (ownerId, token) = loggedIn()
    val accountId = insertAccount(ownerId)

    val r = createMonitor(token, accountId)

    assertEquals(r.code, StatusCode.Ok)
    assertEquals(
      r.body,
      Right(viewJson(1L, accountId, "Dermatologist", "active"))
    )

  test("create with interval 4 is 422"):
    val (ownerId, token) = loggedIn()
    val accountId = insertAccount(ownerId)

    val r = createMonitor(token, accountId, intervalMinutes = 4)

    assertEquals(r.code, StatusCode.UnprocessableEntity)
    assertEquals(
      r.body,
      Left(
        """{"code":"validation","message":"Interval must be at least 5 minutes."}"""
      )
    )

  test("create with dateFrom after dateTo is 422"):
    val (ownerId, token) = loggedIn()
    val accountId = insertAccount(ownerId)

    val r = createMonitor(
      token,
      accountId,
      dateFrom = "2026-08-31",
      dateTo = "2026-08-01"
    )

    assertEquals(r.code, StatusCode.UnprocessableEntity)
    assertEquals(
      r.body,
      Left(
        """{"code":"validation","message":"dateFrom must not be after dateTo."}"""
      )
    )

  // -- list ------------------------------------------------------------------

  test("list returns the literal JSON array of only the caller's own monitors"):
    val (ownerId, token) = loggedIn()
    val otherOwnerId = aUser("other", "pw-other")
    val accountId = insertAccount(ownerId)
    val otherAccountId = insertAccount(otherOwnerId, "Other")
    createMonitor(token, accountId)
    insertMonitorRow(otherAccountId, "active")

    val r = basicRequest
      .get(uri"$baseUri/api/monitors")
      .cookie("lmbot_session", token)
      .send(http)

    assertEquals(r.code, StatusCode.Ok)
    assertEquals(
      r.body,
      Right(s"[${viewJson(1L, accountId, "Dermatologist", "active")}]")
    )

  // -- get -------------------------------------------------------------------

  test("get returns the literal monitor view JSON"):
    val (ownerId, token) = loggedIn()
    val accountId = insertAccount(ownerId)
    createMonitor(token, accountId)

    val r = basicRequest
      .get(uri"$baseUri/api/monitors/1")
      .cookie("lmbot_session", token)
      .send(http)

    assertEquals(r.code, StatusCode.Ok)
    assertEquals(
      r.body,
      Right(viewJson(1L, accountId, "Dermatologist", "active"))
    )

  test("get is 404 for another owner's monitor"):
    val firstOwner = aUser("first", "pw-first")
    val (_, secondToken) = loggedIn("second", "pw-second")
    val accountId = insertAccount(firstOwner)
    val monitorId = insertMonitorRow(accountId, "active")

    val r = basicRequest
      .get(uri"$baseUri/api/monitors/$monitorId")
      .cookie("lmbot_session", secondToken)
      .send(http)

    assertEquals(r.code, StatusCode.NotFound)
    assertEquals(
      r.body,
      Left("""{"code":"not_found","message":"Not found"}""")
    )

  test("an authenticated admin cannot access another user's monitors"):
    val firstOwner = aUser("first", "pw-first")
    val (_, adminToken) = loggedIn("admin", "pw-admin", role = Role.Admin)
    val accountId = insertAccount(firstOwner)
    val monitorId = insertMonitorRow(accountId, "active")

    val r = basicRequest
      .get(uri"$baseUri/api/monitors/$monitorId")
      .cookie("lmbot_session", adminToken)
      .send(http)

    assertEquals(r.code, StatusCode.NotFound)

  // -- update ------------------------------------------------------------

  test("update succeeds and returns the literal updated monitor view JSON"):
    val (ownerId, token) = loggedIn()
    val accountId = insertAccount(ownerId)
    createMonitor(token, accountId)

    val r = basicRequest
      .put(uri"$baseUri/api/monitors/1")
      .cookie("lmbot_session", token)
      .body(draftBody(accountId, name = "Renamed"))
      .contentType("application/json")
      .send(http)

    assertEquals(r.code, StatusCode.Ok)
    assertEquals(
      r.body.map(normalizeUpdatedAt),
      Right(normalizeUpdatedAt(viewJson(1L, accountId, "Renamed", "active")))
    )

  test("update is 404 for another owner's monitor"):
    val firstOwner = aUser("first", "pw-first")
    val (secondOwner, secondToken) = loggedIn("second", "pw-second")
    val firstAccountId = insertAccount(firstOwner)
    val secondAccountId = insertAccount(secondOwner, "SecondMain")
    val monitorId = insertMonitorRow(firstAccountId, "active")

    val r = basicRequest
      .put(uri"$baseUri/api/monitors/$monitorId")
      .cookie("lmbot_session", secondToken)
      .body(draftBody(secondAccountId))
      .contentType("application/json")
      .send(http)

    assertEquals(r.code, StatusCode.NotFound)

  // -- pause / resume ------------------------------------------------------

  test("pause then resume round-trips through active/paused and back"):
    val (ownerId, token) = loggedIn()
    val accountId = insertAccount(ownerId)
    createMonitor(token, accountId)

    val pauseResponse = basicRequest
      .post(uri"$baseUri/api/monitors/1/pause")
      .cookie("lmbot_session", token)
      .send(http)
    assertEquals(pauseResponse.code, StatusCode.Ok)
    assertEquals(pauseResponse.body, Right(""))

    val afterPause = basicRequest
      .get(uri"$baseUri/api/monitors/1")
      .cookie("lmbot_session", token)
      .send(http)
    assertEquals(
      afterPause.body.map(normalizeUpdatedAt),
      Right(
        normalizeUpdatedAt(
          viewJson(1L, accountId, "Dermatologist", "paused")
        )
      )
    )

    val resumeResponse = basicRequest
      .post(uri"$baseUri/api/monitors/1/resume")
      .cookie("lmbot_session", token)
      .send(http)
    assertEquals(resumeResponse.code, StatusCode.Ok)
    assertEquals(resumeResponse.body, Right(""))

    val afterResume = basicRequest
      .get(uri"$baseUri/api/monitors/1")
      .cookie("lmbot_session", token)
      .send(http)
    assertEquals(
      afterResume.body.map(normalizeUpdatedAt),
      Right(
        normalizeUpdatedAt(viewJson(1L, accountId, "Dermatologist", "active"))
      )
    )

  test("resuming a completed monitor is 409"):
    val (ownerId, token) = loggedIn()
    val accountId = insertAccount(ownerId)
    val monitorId = insertMonitorRow(accountId, "completed")

    val r = basicRequest
      .post(uri"$baseUri/api/monitors/$monitorId/resume")
      .cookie("lmbot_session", token)
      .send(http)

    assertEquals(r.code, StatusCode.Conflict)
    assertEquals(
      r.body,
      Left(
        """{"code":"conflict","message":"The monitor cannot be resumed from its current state."}"""
      )
    )

  test("resuming a failed monitor is 409"):
    val (ownerId, token) = loggedIn()
    val accountId = insertAccount(ownerId)
    val monitorId = insertMonitorRow(accountId, "failed")

    val r = basicRequest
      .post(uri"$baseUri/api/monitors/$monitorId/resume")
      .cookie("lmbot_session", token)
      .send(http)

    assertEquals(r.code, StatusCode.Conflict)
    assertEquals(
      r.body,
      Left(
        """{"code":"conflict","message":"The monitor cannot be resumed from its current state."}"""
      )
    )

  // -- delete ---------------------------------------------------------------

  test("delete removes the monitor, which is then absent"):
    val (ownerId, token) = loggedIn()
    val accountId = insertAccount(ownerId)
    createMonitor(token, accountId)

    val deleteResponse = basicRequest
      .delete(uri"$baseUri/api/monitors/1")
      .cookie("lmbot_session", token)
      .send(http)
    assertEquals(deleteResponse.code, StatusCode.Ok)
    assertEquals(deleteResponse.body, Right(""))

    val getResponse = basicRequest
      .get(uri"$baseUri/api/monitors/1")
      .cookie("lmbot_session", token)
      .send(http)
    assertEquals(getResponse.code, StatusCode.NotFound)

  test("delete is 404 for another owner's monitor"):
    val firstOwner = aUser("first", "pw-first")
    val (_, secondToken) = loggedIn("second", "pw-second")
    val accountId = insertAccount(firstOwner)
    val monitorId = insertMonitorRow(accountId, "active")

    val r = basicRequest
      .delete(uri"$baseUri/api/monitors/$monitorId")
      .cookie("lmbot_session", secondToken)
      .send(http)

    assertEquals(r.code, StatusCode.NotFound)
    assert(
      MonitorRepo(xa)
        .findOwned(
          lmbot.shared.domain.MonitorId(monitorId),
          firstOwner
        )
        .isDefined
    )
