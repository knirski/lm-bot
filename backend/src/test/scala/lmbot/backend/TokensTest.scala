package lmbot.backend

import lmbot.backend.auth.Tokens

class TokensTest extends munit.FunSuite:

  test("generated tokens are long, URL-safe and unique"):
    val tokens = List.fill(100)(Tokens.generate())
    assertEquals(tokens.distinct.size, 100)
    tokens.foreach: t =>
      assert(t.length >= 40, s"suspiciously short token: $t")
      assert(t.matches("^[A-Za-z0-9_-]+$"), s"not URL-safe: $t")

  test("hashing is deterministic"):
    val token = Tokens.generate()
    assertEquals(Tokens.hash(token), Tokens.hash(token))

  test(
    "the hash differs from the token, so a database leak reveals no live session"
  ):
    val token = Tokens.generate()
    assertNotEquals(Tokens.hash(token), token)

  test("different tokens hash differently"):
    assertNotEquals(
      Tokens.hash(Tokens.generate()),
      Tokens.hash(Tokens.generate())
    )
