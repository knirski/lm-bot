package lmbot.backend

import lmbot.backend.auth.Passwords

class PasswordsTest extends munit.FunSuite:

  test("a hashed password verifies against its plaintext"):
    val hash = Passwords.hash("correct horse battery staple")
    assert(Passwords.verify(hash, "correct horse battery staple"))

  test("a wrong password does not verify"):
    val hash = Passwords.hash("correct horse battery staple")
    assert(!Passwords.verify(hash, "Correct horse battery staple"))
    assert(!Passwords.verify(hash, ""))

  test("the hash is Argon2id and does not contain the plaintext"):
    val hash = Passwords.hash("s3cret")
    assert(hash.startsWith("$argon2id$"), s"not argon2id: $hash")
    assert(!hash.contains("s3cret"))

  test(
    "hashing the same password twice yields different hashes (unique salts)"
  ):
    assertNotEquals(Passwords.hash("same"), Passwords.hash("same"))

  test("verify returns false for a malformed hash instead of throwing"):
    assert(!Passwords.verify("not-a-hash", "whatever"))
