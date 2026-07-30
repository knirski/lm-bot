package lmbot.backend

import java.time.{Instant, OffsetDateTime}
import java.util.Base64
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import lmbot.backend.config.{MasterKey, Secret}
import lmbot.backend.crypto.AesGcm
import lmbot.backend.db.{AccountRepo, LuxmedAccountRow, UserRepo}
import lmbot.backend.luxmed.model.{LuxmedSession, TokenType}
import lmbot.backend.luxmed.{CookieJar, PostgresSessionStore, SessionStoreError}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.{AccountId, Role}

class PostgresSessionStoreTest extends PostgresSuite:

  private val key = MasterKey
    .fromBase64(Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7)))
    .toOption
    .get
  private val crypto = AesGcm(key)
  private var nextUser = 0

  private def owner(): Long =
    nextUser += 1
    UserRepo(xa)
      .insert(s"session-owner$nextUser", "Session owner", "hash", Role.Admin)
      .id

  private def account(ownerId: Long): Long =
    val repo = AccountRepo(xa)
    val id = repo.reserveId()
    val now = OffsetDateTime.now()
    repo.insert(
      LuxmedAccountRow(
        id.value,
        ownerId,
        "Session account",
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
    id.value

  private def session(refresh: String): LuxmedSession =
    LuxmedSession(
      Secret(s"access-$refresh"),
      TokenType.Bearer,
      Secret(refresh),
      Instant.parse("2026-08-01T10:00:00Z"),
      Secret(s"jwt-$refresh"),
      CookieJar("SESSION" -> Secret(s"cookie-$refresh"))
    )

  private def store(ownerId: Long, accountId: Long): PostgresSessionStore =
    PostgresSessionStore(xa, ownerId, AccountId(accountId), crypto)

  test("load returns None before the first session"):
    val ownerId = owner()
    val accountId = account(ownerId)
    assertEquals(store(ownerId, accountId).load(), Right(None))

  test("initial replace persists a complete session"):
    val ownerId = owner()
    val accountId = account(ownerId)
    val current = session("refresh-1")
    val saved = store(ownerId, accountId).replace(None, current)
    assertEquals(saved, Right(()))
    assertEquals(store(ownerId, accountId).load(), Right(Some(current)))

  test("replacement uses the expected refresh token"):
    val ownerId = owner()
    val accountId = account(ownerId)
    val first = session("refresh-1")
    val second = session("refresh-2")
    val current = store(ownerId, accountId)
    assertEquals(current.replace(None, first), Right(()))
    assertEquals(
      current.replace(Some(Secret("stale")), second),
      Left(SessionStoreError.ConcurrentModification)
    )
    assertEquals(current.load(), Right(Some(first)))

  test("a new store loads a rotated session after restart"):
    val ownerId = owner()
    val accountId = account(ownerId)
    val first = session("refresh-1")
    val second = session("refresh-2")
    assertEquals(store(ownerId, accountId).replace(None, first), Right(()))
    assertEquals(
      store(ownerId, accountId).replace(Some(first.refreshToken), second),
      Right(())
    )
    assertEquals(store(ownerId, accountId).load(), Right(Some(second)))

  test("clear removes the persisted session"):
    val ownerId = owner()
    val accountId = account(ownerId)
    val current = store(ownerId, accountId)
    assertEquals(current.replace(None, session("refresh-1")), Right(()))
    assertEquals(current.clear(), Right(()))
    assertEquals(current.load(), Right(None))

  test("a stale store cannot overwrite a rotated session"):
    val ownerId = owner()
    val accountId = account(ownerId)
    val first = session("refresh-1")
    val second = session("refresh-2")
    val third = session("refresh-3")
    val firstStore = store(ownerId, accountId)
    assertEquals(firstStore.replace(None, first), Right(()))
    assertEquals(
      store(ownerId, accountId).replace(Some(first.refreshToken), second),
      Right(())
    )
    assertEquals(
      store(ownerId, accountId).replace(Some(first.refreshToken), third),
      Left(SessionStoreError.ConcurrentModification)
    )
    assertEquals(store(ownerId, accountId).load(), Right(Some(second)))

  test("concurrent replacements allow exactly one CAS winner"):
    val ownerId = owner()
    val accountId = account(ownerId)
    val first = session("refresh-1")
    val second = session("refresh-2")
    val third = session("refresh-3")
    val initialStore = store(ownerId, accountId)
    assertEquals(initialStore.replace(None, first), Right(()))

    val ready = new CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try
      val attempts = List(second, third).map: updated =>
        executor.submit(() =>
          ready.await(10, TimeUnit.SECONDS)
          store(ownerId, accountId).replace(Some(first.refreshToken), updated)
        )
      ready.countDown()
      val results = attempts.map(_.get(10, TimeUnit.SECONDS)).toList
      assertEquals(results.count(_ == Right(())), 1)
      assertEquals(
        results.count(_ == Left(SessionStoreError.ConcurrentModification)),
        1
      )
      val winner = results.collectFirst:
        case Right(()) if results.head == Right(()) => second
        case Right(())                              => third
      assertEquals(store(ownerId, accountId).load(), Right(winner))
    finally executor.shutdownNow()

  test("stored ciphertext does not contain session secrets"):
    val ownerId = owner()
    val accountId = account(ownerId)
    val current = session("refresh-secret")
    assertEquals(store(ownerId, accountId).replace(None, current), Right(()))
    val encrypted =
      AccountRepo(xa)
        .findOwned(AccountId(accountId), ownerId)
        .flatMap(_.encryptedSession)
    assert(encrypted.isDefined)
    assert(!encrypted.get.contains("refresh-secret"))
    assert(!encrypted.get.contains("access-refresh-secret"))
