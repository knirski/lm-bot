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
      """{"version":1,"accessToken":"access","tokenType":"bearer","refreshToken":"refresh","expiresAt":"2026-08-01T10:00:00Z","jwtToken":"jwt","cookies":[{"name":"SESSION","value":"def"},{"name":"WAF","value":"abc"}]}"""
    )

  test("cookie order is deterministic past the point a Map would stop helping"):
    val manyCookies = session.copy(
      cookies = CookieJar(
        "WAF" -> Secret("w"),
        "SESSION" -> Secret("s"),
        "XSRF" -> Secret("x"),
        "AB_TEST" -> Secret("a"),
        "CONSENT" -> Secret("c")
      )
    )
    assertEquals(
      SessionCodec.encode(manyCookies),
      SessionCodec.encode(manyCookies),
      "encoding must be stable across calls, not incidental to Map size"
    )
    assert(
      SessionCodec
        .encode(manyCookies)
        .contains(
          """"cookies":[{"name":"AB_TEST","value":"a"},{"name":"CONSENT","value":"c"},{"name":"SESSION","value":"s"},{"name":"WAF","value":"w"},{"name":"XSRF","value":"x"}]"""
        )
    )
    assertEquals(
      SessionCodec.decode(SessionCodec.encode(manyCookies)),
      Right(manyCookies)
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
