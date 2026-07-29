package lmbot.backend.luxmed

import lmbot.backend.config.Secret
import lmbot.backend.luxmed.model.*
import java.time.Instant

class SessionStoreTest extends munit.FunSuite:

  private val session1 = LuxmedSession(
    accessToken = Secret("AT1"),
    tokenType = TokenType.Bearer,
    refreshToken = Secret("RT1"),
    expiresAt = Instant.parse("2026-08-03T13:00:00Z"),
    jwtToken = Secret("JWT1"),
    cookies = CookieJar.empty
  )

  private val session2 = LuxmedSession(
    accessToken = Secret("AT2"),
    tokenType = TokenType.Bearer,
    refreshToken = Secret("RT2"),
    expiresAt = Instant.parse("2026-08-03T14:00:00Z"),
    jwtToken = Secret("JWT2"),
    cookies = CookieJar.empty
  )

  test("initial load returns None"):
    val store = InMemorySessionStore()
    assertEquals(store.load(), Right(None))

  test("replace with None inserts the first session"):
    val store = InMemorySessionStore()
    assertEquals(store.replace(None, session1), Right(()))
    assertEquals(store.load(), Right(Some(session1)))

  test("replace succeeds when refresh token matches"):
    val store = InMemorySessionStore()
    assertEquals(store.replace(None, session1), Right(()))
    assertEquals(
      store.replace(Some(Secret("RT1")), session2),
      Right(())
    )
    assertEquals(store.load(), Right(Some(session2)))

  test("replace fails on stale refresh token"):
    val store = InMemorySessionStore()
    assertEquals(store.replace(None, session1), Right(()))
    assertEquals(
      store.replace(Some(Secret("wrong")), session2),
      Left(SessionStoreError.ConcurrentModification)
    )
    assertEquals(store.load(), Right(Some(session1)))

  test("replace fails when store is empty but expected token is Some"):
    val store = InMemorySessionStore()
    assertEquals(
      store.replace(Some(Secret("RT1")), session1),
      Left(SessionStoreError.ConcurrentModification)
    )

  test("clear removes the stored session"):
    val store = InMemorySessionStore()
    assertEquals(store.replace(None, session1), Right(()))
    assertEquals(store.clear(), Right(()))
    assertEquals(store.load(), Right(None))

  test("a matching refresh token replaces the current session"):
    val store = InMemorySessionStore()
    assertEquals(store.replace(None, session1), Right(()))
    assertEquals(store.replace(Some(Secret("RT1")), session2), Right(()))
    assertEquals(store.load(), Right(Some(session2)))
