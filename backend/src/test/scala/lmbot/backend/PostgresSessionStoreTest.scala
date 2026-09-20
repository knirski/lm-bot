package lmbot.backend

import java.time.{Instant, OffsetDateTime}
import java.util.Base64
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import scala.concurrent.duration.{Duration, DurationInt}

import com.augustnagro.magnum.{sql, transact}
import lmbot.backend.config.{MasterKey, Secret}
import lmbot.backend.crypto.AesGcm
import lmbot.backend.db.{AccountRepo, LuxmedAccountRow, UserRepo}
import lmbot.backend.luxmed.model.{LuxmedSession, TokenType}
import lmbot.backend.luxmed.{CookieJar, PostgresSessionStore, SessionStoreError}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.{AccountId, Role, UserId}

class PostgresSessionStoreTest extends PostgresSuite:

  // The concurrent CAS test rendezvous on latches. With every suite running
  // in parallel — each with its own embedded PostgreSQL — a thread can be
  // starved past munit's 30 s default, so the suite gets headroom while the
  // test body still bounds each wait with an explicit assertion.
  override def munitTimeout: Duration = 2.minutes

  private val key = MasterKey
    .fromBase64(Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7)))
    .toOption
    .get
  private val crypto = AesGcm(key)
  private var nextUser = 0

  private def owner(): UserId =
    nextUser += 1
    UserId(
      UserRepo(xa)
        .insert(s"session-owner$nextUser", "Session owner", "hash", Role.Admin)
        .id
    )

  private def account(ownerId: UserId): Long =
    val repo = AccountRepo(xa)
    val id = repo.reserveId()
    val now = OffsetDateTime.now()
    repo.insert(
      LuxmedAccountRow(
        id.value,
        ownerId.value,
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

  private def store(
      ownerId: UserId,
      accountId: Long,
      afterRead: () => Unit = () => ()
  ): PostgresSessionStore =
    PostgresSessionStore(xa, ownerId, AccountId(accountId), crypto, afterRead)

  /** Writes an unparsable value directly into `encrypted_session`, bypassing
    * the store, to simulate ciphertext that has been corrupted or bound to a
    * different context.
    */
  private def corruptSession(accountId: Long, garbage: String): Unit =
    transact(xa):
      sql"update luxmed_accounts set encrypted_session = $garbage where id = $accountId".update
        .run()
      ()

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

    // Both attempts read the stored row, then park here until the test thread
    // releases them, so they race the compare-and-set against the same value.
    // The workers' waits are deliberately untimed: only the test thread can
    // release them, and a short timeout here previously turned pure
    // scheduling delay into a spurious failure. `shutdownNow` interrupts a
    // worker if the test aborts before releasing them.
    val arrived = new CountDownLatch(2)
    val release = new CountDownLatch(1)
    val barrier = () =>
      arrived.countDown()
      release.await()

    val executor = Executors.newFixedThreadPool(2)
    try
      val attempts = List(second, third).map: updated =>
        updated -> executor.submit(() =>
          store(ownerId, accountId, barrier)
            .replace(Some(first.refreshToken), updated)
        )
      assert(
        arrived.await(30, TimeUnit.SECONDS),
        "both CAS attempts should reach the barrier"
      )
      release.countDown()
      val results = attempts.map: (updated, future) =>
        updated -> future.get(30, TimeUnit.SECONDS)
      assertEquals(results.count(_._2 == Right(())), 1)
      assertEquals(
        results.count(_._2 == Left(SessionStoreError.ConcurrentModification)),
        1
      )
      val winner = results.collectFirst:
        case (updated, Right(())) => updated
      assert(winner.isDefined)
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

  test(
    "a store scoped to another owner cannot read, replace, or clear the session"
  ):
    val firstOwner = owner()
    val accountId = account(firstOwner)
    val intruder = owner()
    val current = session("refresh-1")
    assertEquals(store(firstOwner, accountId).replace(None, current), Right(()))

    // No owned row exists for `intruder` at this id — distinct from "owned,
    // no session yet" (see the R15 fix in PostgresSessionStore.load).
    assertEquals(
      store(intruder, accountId).load(),
      Left(SessionStoreError.Unavailable("session persistence failed"))
    )
    assertEquals(
      store(intruder, accountId)
        .replace(Some(Secret("refresh-1")), session("refresh-2")),
      Left(SessionStoreError.ConcurrentModification)
    )
    assert(store(intruder, accountId).clear().isLeft)
    assertEquals(store(firstOwner, accountId).load(), Right(Some(current)))

  test(
    "an undecryptable stored session reports Unavailable without leaking ciphertext"
  ):
    val ownerId = owner()
    val accountId = account(ownerId)
    corruptSession(accountId, "not a valid envelope")

    // The exact-message assertion below also proves the raw ciphertext never
    // leaks into the error: the message is a fixed literal, not derived from
    // the corrupted value.
    assertEquals(
      store(ownerId, accountId).load(),
      Left(SessionStoreError.Unavailable("session persistence failed"))
    )

    val replaced =
      store(ownerId, accountId).replace(None, session("refresh-1"))
    assertEquals(
      replaced,
      Left(SessionStoreError.Unavailable("session persistence failed"))
    )
