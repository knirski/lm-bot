package lmbot.backend.luxmed

import java.time.{Duration, OffsetDateTime}
import java.util.{Base64, UUID}

import lmbot.backend.config.MasterKey
import lmbot.backend.config.{AppVersion, Secret}
import lmbot.backend.crypto.AesGcm
import lmbot.backend.db.{AccountRepo, LuxmedAccountRow, UserRepo}
import lmbot.backend.luxmed.model.Credentials
import lmbot.backend.luxmed.support.{
  FakeTime,
  GearsTest,
  LuxmedResponseScripts,
  StubLuxmedBackend
}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.{AccountId, Role, UserId}
import sttp.model.Uri

class PostgresSessionStoreClientTest extends PostgresSuite with GearsTest:

  private val config = LuxmedConfig(
    oldApi = Uri.unsafeParse("https://old.example/api"),
    newApi = Uri.unsafeParse("https://new.example"),
    appVersion = AppVersion.unsafeFromString("4.44.0"),
    deviceUuid = UUID.fromString("12345678-54b1-4c07-ba09-a3db8daea24b")
  )

  private def account(): (UserId, Long) =
    val owner = UserId(
      UserRepo(xa)
        .insert("client-owner", "Client owner", "hash", Role.Admin)
        .id
    )
    val accountId = AccountRepo(xa).reserveId()
    val now = OffsetDateTime.now()
    AccountRepo(xa).insert(
      LuxmedAccountRow(
        accountId.value,
        owner.value,
        "Client account",
        "user@example.com",
        "encrypted-password",
        "encrypted-device",
        None,
        "active",
        None,
        None,
        now,
        now
      )
    )
    (owner, accountId.value)

  private def enqueue(
      stub: StubLuxmedBackend,
      responses: List[LuxmedResponseScripts.Response]
  ): Unit =
    responses.foreach(response =>
      stub.enqueue(response.status, response.headers, response.body)
    )

  test("a new client refreshes a persisted session without a password grant"):
    val (owner, accountId) = account()
    val fake = FakeTime()
    val crypto = AesGcm(
      MasterKey
        .fromBase64(Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7)))
        .toOption
        .get
    )
    val credentials = Credentials("user@example.com", Secret("password123"))

    val firstStub = StubLuxmedBackend()
    enqueue(firstStub, LuxmedResponseScripts.realisticAuthFlow(expiresIn = 600))
    val firstClient = LuxmedClient(
      LuxmedTransport.withBackend(config, firstStub.backend),
      credentials,
      AccountGate(Duration.ZERO, () => fake.now(), fake.sleeper),
      PostgresSessionStore(xa, owner, AccountId(accountId), crypto),
      now = () => fake.now()
    )
    assert(runAsync(firstClient.authenticate()).isRight)

    val secondStub = StubLuxmedBackend()
    enqueue(
      secondStub,
      LuxmedResponseScripts.oauthPasswordGrant() ::
        LuxmedResponseScripts.realisticBootstrapFlow(jwtToken = "JWT_TOKEN_2")
    )
    val secondClient = LuxmedClient(
      LuxmedTransport.withBackend(config, secondStub.backend),
      credentials,
      AccountGate(Duration.ZERO, () => fake.now(), fake.sleeper),
      PostgresSessionStore(xa, owner, AccountId(accountId), crypto),
      now = () => fake.now().plusSeconds(301)
    )

    val refreshed = runAsync:
      secondClient.withSession((_, session) =>
        Right(session.refreshToken.value)
      )
    assertEquals(refreshed, Right("RT1"))
    val requestBodies = secondStub.requests.map(secondStub.bodyString)
    assert(!requestBodies.exists(_.contains("grant_type=password")))
    assert(requestBodies.exists(_.contains("grant_type=refresh_token")))
    assertEquals(
      PostgresSessionStore(xa, owner, AccountId(accountId), crypto)
        .load()
        .map(_.map(_.refreshToken.value)),
      Right(Some("RT1"))
    )
