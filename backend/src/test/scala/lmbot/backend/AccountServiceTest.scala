package lmbot.backend

import java.sql.{Date as SqlDate, Time as SqlTime}
import java.time.{Duration, Instant, LocalDate, LocalTime, OffsetDateTime}
import java.util.{Base64, UUID}

import lmbot.backend.account.{AccountClientFactory, AccountService}
import lmbot.backend.config.{AppVersion, MasterKey, Secret}
import lmbot.backend.crypto.{
  AesGcm,
  EncryptedEnvelope,
  EncryptionContext,
  EncryptionPurpose
}
import lmbot.backend.db.{
  AccountRepo,
  LuxmedAccountRow,
  MonitorRepo,
  MonitorRow,
  UserRepo
}
import lmbot.backend.luxmed.support.{
  GearsTest,
  LuxmedResponseScripts,
  MockResponse,
  RealHttpLuxmedServer
}
import lmbot.backend.luxmed.{LuxmedConfig, PostgresSessionStore, SessionCodec}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.api.ApiError
import lmbot.shared.domain.{
  AccountId,
  AccountStatus,
  LinkAccountRequest,
  MonitorId,
  Role
}
import sttp.model.Uri

class AccountServiceTest extends PostgresSuite with GearsTest:

  private val fixedDeviceUuid =
    UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")
  private val fixedInstant = Instant.parse("2026-07-30T08:00:00Z")
  private val fixedOffset = OffsetDateTime.parse("2026-07-30T10:00:00+02:00")
  private val key = MasterKey
    .fromBase64(Base64.getEncoder.encodeToString(Array.fill[Byte](32)(9)))
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
      oldApi = Uri.unsafeParse(
        s"${server.baseUri}/PatientPortalMobileAPI/api"
      ),
      newApi = Uri.unsafeParse(s"${server.baseUri}/PatientPortal"),
      appVersion = AppVersion.unsafeFromString("4.44.0"),
      deviceUuid = UUID.fromString("00000000-0000-4000-8000-000000000001")
    )

  private def service(
      baseConfig: LuxmedConfig
  ): (AccountService, AccountClientFactory) =
    val accounts = AccountRepo(xa)
    val factory = AccountClientFactory.production(
      xa = xa,
      accounts = accounts,
      baseConfig = baseConfig,
      crypto = crypto,
      minimumSpacing = Duration.ZERO,
      now = () => fixedInstant
    )
    val service = AccountService(
      accounts = accounts,
      clients = factory,
      crypto = crypto,
      uuidGenerator = () => fixedDeviceUuid,
      now = () => fixedInstant
    )
    (service, factory)

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

  private def enqueueAuth(
      server: RealHttpLuxmedServer,
      refreshToken: String = "RT1",
      accessToken: String = "AT1",
      jwtToken: String = "JWT_TOKEN_1"
  ): Unit =
    enqueue(
      server,
      LuxmedResponseScripts.realisticAuthFlow(
        accessToken = accessToken,
        refreshToken = refreshToken,
        jwtToken = jwtToken
      )
    )

  private def linkRequest(
      label: String = "Main",
      username: String = "user@example.com",
      password: String = "password123"
  ): LinkAccountRequest =
    LinkAccountRequest(label, username, password)

  private def passwordGrantCount(server: RealHttpLuxmedServer): Int =
    server.requests.count(_.body.contains("grant_type=password"))

  private def accountRows(ownerId: Long): Seq[LuxmedAccountRow] =
    AccountRepo(xa).listOwned(ownerId)

  private def insertAccount(
      ownerId: Long,
      label: String,
      username: String = "stored@example.com",
      status: String = "active",
      statusReason: Option[String] = None
  ): Long =
    val repo = AccountRepo(xa)
    val id = repo.reserveId()
    repo.insert(
      LuxmedAccountRow(
        id.value,
        ownerId,
        label,
        crypto
          .encrypt(
            username,
            EncryptionContext(ownerId, id, EncryptionPurpose.Username)
          )
          .render,
        s"encrypted-password-$label",
        s"encrypted-device-$label",
        None,
        status,
        statusReason,
        None,
        fixedOffset,
        fixedOffset
      )
    )
    id.value

  private def decrypt(
      row: LuxmedAccountRow,
      encrypted: String,
      purpose: EncryptionPurpose
  ): Secret =
    val envelope = EncryptedEnvelope.parse(encrypted).toOption.get
    crypto
      .decrypt(
        envelope,
        EncryptionContext(row.ownerUserId, AccountId(row.id), purpose)
      )
      .toOption
      .get

  private def insertMonitor(ownerId: Long, accountId: Long): Long =
    val repo = MonitorRepo(xa)
    val monitorId = repo.reserveId()
    repo.insert(
      MonitorRow(
        monitorId,
        accountId,
        "Dermatologist",
        3L,
        "Warsaw",
        42L,
        "Dermatology",
        List(9L),
        List("Puławska"),
        Nil,
        Nil,
        SqlDate.valueOf(LocalDate.parse("2026-08-01")),
        SqlDate.valueOf(LocalDate.parse("2026-08-31")),
        SqlTime.valueOf(LocalTime.parse("08:00")),
        SqlTime.valueOf(LocalTime.parse("16:00")),
        0x7f.toShort,
        false,
        10,
        "active",
        fixedOffset,
        fixedOffset
      )
    )
    assert(MonitorRepo(xa).findOwned(MonitorId(monitorId), ownerId).isDefined)
    monitorId

  test("link rejects blank and oversized labels/usernames before Luxmed"):
    withServer: server =>
      val ownerId = owner()
      val (accounts, _) = service(config(server))
      val cases = List(
        linkRequest(label = "   ") ->
          ApiError.Validation("Account label is required."),
        linkRequest(label = "a" * 81) ->
          ApiError.Validation("Account label must be at most 80 characters."),
        linkRequest(username = "   ") ->
          ApiError.Validation("Luxmed username is required."),
        linkRequest(username = "u" * 255) ->
          ApiError.Validation(
            "Luxmed username must be at most 254 characters."
          )
      )

      cases.foreach: (request, expected) =>
        assertEquals(runAsync(accounts.link(ownerId, request)), Left(expected))

      assertEquals(server.requests, Nil)
      assertEquals(accountRows(ownerId), Nil)

  test("duplicate label returns conflict without contacting Luxmed"):
    withServer: server =>
      val ownerId = owner()
      insertAccount(ownerId, "Main")
      val (accounts, _) = service(config(server))

      val result = runAsync:
        accounts.link(ownerId, linkRequest(label = "Main"))

      assertEquals(
        result,
        Left(ApiError.Conflict("An account with this label already exists."))
      )
      assertEquals(accountRows(ownerId).map(_.label).toList, List("Main"))
      assertEquals(passwordGrantCount(server), 0)
      assertEquals(server.requests, Nil)

  test("successful link authenticates once and returns a secret-free view"):
    withServer: server =>
      val ownerId = owner()
      enqueueAuth(server)
      val (accounts, _) = service(config(server))

      val result = runAsync:
        accounts.link(
          ownerId,
          linkRequest(label = " Main ", username = " user@example.com ")
        )

      assert(result.isRight, s"expected link success, got $result")
      val view = result.toOption.get
      assertEquals(view.label, "Main")
      assertEquals(view.username, "user@example.com")
      assertEquals(view.status, AccountStatus.Active)
      assertEquals(view.statusReason, None)
      assertEquals(view.lastSuccessfulLogin, Some(fixedInstant))
      assertEquals(accountRows(ownerId).size, 1)
      assertEquals(passwordGrantCount(server), 1)
      assertEquals(server.requests.size, 3)

  test("credential rejection returns validation and creates no row"):
    withServer: server =>
      val ownerId = owner()
      server.enqueue(
        MockResponse(
          409,
          Map("Content-Type" -> List("application/json")),
          """{"Message":"invalid login or password"}"""
        )
      )
      val (accounts, _) = service(config(server))

      val result = runAsync:
        accounts.link(ownerId, linkRequest())

      assertEquals(
        result,
        Left(ApiError.Validation("Luxmed rejected these credentials."))
      )
      assertEquals(accountRows(ownerId), Nil)
      assertEquals(passwordGrantCount(server), 1)

  test("challenge-shaped auth response returns conflict and creates no row"):
    withServer: server =>
      val ownerId = owner()
      server.enqueue(
        MockResponse(
          200,
          Map("Content-Type" -> List("application/json")),
          """{"challengeId":"sms-1","delivery":"sms"}"""
        )
      )
      val (accounts, _) = service(config(server))

      val result = runAsync:
        accounts.link(ownerId, linkRequest())

      assertEquals(
        result,
        Left(
          ApiError.Conflict(
            "Luxmed requested an unexpected authentication step."
          )
        )
      )
      assertEquals(accountRows(ownerId), Nil)
      assertEquals(passwordGrantCount(server), 1)

  test("version rejection returns conflict and creates no row"):
    withServer: server =>
      val ownerId = owner()
      server.enqueue(
        MockResponse(
          409,
          Map("Content-Type" -> List("application/json")),
          """{"ErrorCode":301,"Message":"Obecnie zainstalowana wersja aplikacji nie jest wspierana przez nowy system Portalu Pacjenta. Zaktualizuj aplikację do najnowszej wersji, aby móc z niej korzystać."}"""
        )
      )
      val (accounts, _) = service(config(server))

      val result = runAsync:
        accounts.link(ownerId, linkRequest())

      assertEquals(
        result,
        Left(
          ApiError.Conflict(
            "The configured Luxmed app version is no longer accepted."
          )
        )
      )
      assertEquals(accountRows(ownerId), Nil)
      assertEquals(passwordGrantCount(server), 1)

  test("network failure returns a safe unavailable error and creates no row"):
    val ownerId = owner()
    val closedServer = RealHttpLuxmedServer()
    val unreachableConfig = config(closedServer)
    closedServer.close()
    val (accounts, _) = service(unreachableConfig)

    val result = runAsync:
      accounts.link(ownerId, linkRequest())

    assertEquals(
      result,
      Left(ApiError.Unexpected("Luxmed is temporarily unavailable."))
    )
    assertEquals(accountRows(ownerId), Nil)

  test(
    "persistence failure after auth returns safe unexpected error with no row"
  ):
    withServer: server =>
      val missingOwner = 99999999L
      enqueueAuth(server)
      val (accounts, _) = service(config(server))

      val result = runAsync:
        accounts.link(missingOwner, linkRequest())

      assertEquals(
        result,
        Left(ApiError.Unexpected("The Luxmed account could not be linked."))
      )
      assertEquals(accountRows(missingOwner), Nil)
      assertEquals(passwordGrantCount(server), 1)

  test(
    "linked account stores username password device UUID and session encrypted"
  ):
    withServer: server =>
      val ownerId = owner()
      enqueueAuth(server)
      val (accounts, _) = service(config(server))

      val linked = runAsync:
        accounts.link(ownerId, linkRequest(password = "password-secret"))
      assert(linked.isRight, s"expected link success, got $linked")

      val row = accountRows(ownerId).head
      assert(row.encryptedUsername.startsWith("v1."))
      assert(row.encryptedPassword.startsWith("v1."))
      assert(row.encryptedDeviceUuid.startsWith("v1."))
      assert(row.encryptedSession.exists(_.startsWith("v1.")))
      assert(!row.encryptedUsername.contains("user@example.com"))
      assert(!row.encryptedPassword.contains("password-secret"))
      assert(!row.encryptedDeviceUuid.contains(fixedDeviceUuid.toString))
      assert(!row.encryptedSession.get.contains("RT1"))
      assert(!row.encryptedSession.get.contains("AT1"))
      assert(!row.encryptedSession.get.contains("JWT_TOKEN_1"))

      assertEquals(
        decrypt(row, row.encryptedUsername, EncryptionPurpose.Username).value,
        "user@example.com"
      )
      assertEquals(
        decrypt(row, row.encryptedPassword, EncryptionPurpose.Password).value,
        "password-secret"
      )
      assertEquals(
        decrypt(row, row.encryptedDeviceUuid, EncryptionPurpose.DeviceId).value,
        fixedDeviceUuid.toString
      )
      val session = SessionCodec
        .decode(
          decrypt(
            row,
            row.encryptedSession.get,
            EncryptionPurpose.Session
          ).value
        )
        .toOption
        .get
      assertEquals(session.refreshToken.value, "RT1")
      assertEquals(session.jwtToken.value, "JWT_TOKEN_1")
      assert(
        crypto
          .decrypt(
            EncryptedEnvelope.parse(row.encryptedPassword).toOption.get,
            EncryptionContext(
              ownerId,
              AccountId(row.id),
              EncryptionPurpose.Session
            )
          )
          .isLeft
      )

  test("list is isolated by owner and maps persisted statuses"):
    val firstOwner = owner("first")
    val secondOwner = owner("second")
    insertAccount(
      firstOwner,
      "Needs attention",
      status = "auth_failed",
      statusReason = Some("Luxmed requested an unexpected authentication step.")
    )
    insertAccount(secondOwner, "Other")
    val (accounts, _) = service(
      LuxmedConfig.production(
        AppVersion.unsafeFromString("4.44.0"),
        fixedDeviceUuid
      )
    )

    val result = accounts.list(firstOwner)

    assertEquals(
      result.map(_.map(_.label).toList),
      Right(List("Needs attention"))
    )
    assertEquals(
      result.map(_.head.status),
      Right(AccountStatus.AuthFailed)
    )
    assertEquals(
      result.map(_.head.statusReason),
      Right(Some("Luxmed requested an unexpected authentication step."))
    )

  test("delete is owner-scoped and cascades owned monitors"):
    val firstOwner = owner("first")
    val secondOwner = owner("second")
    val accountId = insertAccount(firstOwner, "Main")
    val monitorId = insertMonitor(firstOwner, accountId)
    val (accounts, _) = service(
      LuxmedConfig.production(
        AppVersion.unsafeFromString("4.44.0"),
        fixedDeviceUuid
      )
    )

    assertEquals(
      accounts.delete(secondOwner, AccountId(accountId)),
      Left(ApiError.NotFound)
    )
    assert(
      MonitorRepo(xa).findOwned(MonitorId(monitorId), firstOwner).isDefined
    )

    assertEquals(accounts.delete(firstOwner, AccountId(accountId)), Right(()))
    assertEquals(
      AccountRepo(xa).findOwned(AccountId(accountId), firstOwner),
      None
    )
    assertEquals(
      MonitorRepo(xa).findOwned(MonitorId(monitorId), firstOwner),
      None
    )

  test("forStored decrypts credentials and device UUID for a scoped client"):
    withServer: server =>
      val ownerId = owner()
      enqueueAuth(server, refreshToken = "RT_LINKED")
      val (accounts, factory) = service(config(server))
      val linked = runAsync:
        accounts.link(
          ownerId,
          linkRequest(username = "stored@example.com", password = "stored-pass")
        )
      assert(linked.isRight, s"expected link success, got $linked")
      val accountId = linked.toOption.get.id
      assertEquals(
        PostgresSessionStore(xa, ownerId, accountId, crypto).clear(),
        Right(())
      )

      enqueueAuth(
        server,
        accessToken = "AT_STORED",
        refreshToken = "RT_STORED",
        jwtToken = "JWT_STORED"
      )
      val client = factory.forStored(ownerId, accountId).toOption.get
      val authenticated = runAsync(client.authenticate())

      assert(
        authenticated.isRight,
        s"expected stored client auth, got $authenticated"
      )
      val storedRequests = server.requests.drop(3)
      assert(storedRequests.head.body.contains("username=stored%40example.com"))
      assert(storedRequests.head.body.contains("password=stored-pass"))
      assert(
        storedRequests.head.headers
          .getOrElse("Custom-user-agent", Nil)
          .exists(_.contains(fixedDeviceUuid.toString))
      )
      assertEquals(
        PostgresSessionStore(xa, ownerId, accountId, crypto)
          .load()
          .map(_.map(_.refreshToken.value)),
        Right(Some("RT_STORED"))
      )

  test("forStored rejects a cross-owner account"):
    val firstOwner = owner("first")
    val secondOwner = owner("second")
    val accountId = insertAccount(firstOwner, "Main")
    val (_, factory) = service(
      LuxmedConfig.production(
        AppVersion.unsafeFromString("4.44.0"),
        fixedDeviceUuid
      )
    )

    assertEquals(
      factory.forStored(secondOwner, AccountId(accountId)),
      Left(ApiError.NotFound)
    )
