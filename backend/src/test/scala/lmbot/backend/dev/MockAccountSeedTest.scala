package lmbot.backend.dev

import java.time.Instant
import java.util.Base64
import java.util.concurrent.{Executors, TimeUnit}

import lmbot.backend.auth.Passwords
import lmbot.backend.config.MasterKey
import lmbot.backend.crypto.AesGcm
import lmbot.backend.db.{AccountRepo, UserRepo}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.{Role, UserId}

class MockAccountSeedTest extends PostgresSuite:

  private val crypto = AesGcm(
    MasterKey
      .fromBase64(
        Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7))
      )
      .toOption
      .get
  )
  private val now = () => Instant.parse("2026-08-02T10:00:00Z")

  test("seeding is encrypted and idempotent"):
    val owner = UserId(
      UserRepo(xa)
        .insert(
          "mock-owner",
          "Mock owner",
          Passwords.hash("password"),
          Role.User
        )
        .id
    )
    val accounts = AccountRepo(xa)

    MockAccountSeed.ensure(owner, accounts, crypto, now)
    val first = accounts.listOwned(owner).toList
    MockAccountSeed.ensure(owner, accounts, crypto, now)
    val second = accounts.listOwned(owner).toList

    assertEquals(first.size, 1)
    assertEquals(second, first)
    assert(first.head.encryptedUsername.startsWith("v1."))
    assert(first.head.encryptedPassword.startsWith("v1."))
    assert(first.head.encryptedDeviceUuid.startsWith("v1."))
    assert(first.head.encryptedSession.exists(_.startsWith("v1.")))

  test("concurrent seeding creates one mock account"):
    val owner = UserId(
      UserRepo(xa)
        .insert(
          "concurrent-owner",
          "Concurrent owner",
          Passwords.hash("password"),
          Role.User
        )
        .id
    )
    val accounts = AccountRepo(xa)
    val executor = Executors.newFixedThreadPool(8)
    try
      val tasks = List.fill(8):
        executor.submit(new Runnable:
          override def run(): Unit =
            MockAccountSeed.ensure(owner, accounts, crypto, now))
      tasks.foreach(_.get(10, TimeUnit.SECONDS))
    finally executor.shutdownNow()

    assertEquals(
      accounts.listOwned(owner).count(_.label == MockAccountSeed.label),
      1
    )
