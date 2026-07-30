package lmbot.backend.luxmed

import java.time.Instant

import lmbot.backend.config.Secret
import lmbot.backend.luxmed.model.{LuxmedSession, TokenType}

class SessionCodecTest extends munit.FunSuite:

  private val session = LuxmedSession(
    accessToken = Secret("access"),
    tokenType = TokenType.Bearer,
    refreshToken = Secret("refresh"),
    expiresAt = Instant.parse("2026-08-01T10:00:00Z"),
    jwtToken = Secret("jwt"),
    cookies = CookieJar(
      "WAF" -> Secret("abc"),
      "SESSION" -> Secret("def")
    )
  )

  test("pins the persisted session JSON format"):
    assertEquals(
      SessionCodec.encode(session),
      """{"version":1,"accessToken":"access","tokenType":"bearer","refreshToken":"refresh","expiresAt":"2026-08-01T10:00:00Z","jwtToken":"jwt","cookies":{"WAF":"abc","SESSION":"def"}}"""
    )

  test("decodes every persisted session component"):
    val decoded = SessionCodec.decode(SessionCodec.encode(session))
    assertEquals(decoded, Right(session))

  test("rejects an unsupported payload version"):
    val json =
      SessionCodec.encode(session).replace("\"version\":1", "\"version\":2")
    assert(SessionCodec.decode(json).isLeft)

  test("rejects an unsupported token type"):
    val json = SessionCodec.encode(session).replace("\"bearer\"", "\"basic\"")
    assert(SessionCodec.decode(json).isLeft)
