package lmbot.backend.luxmed

import java.time.Instant

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonValueCodec,
  readFromString,
  writeToString
}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import lmbot.backend.config.Secret
import lmbot.backend.luxmed.model.{LuxmedSession, TokenType}

/** Backend-only plaintext representation used inside the encrypted session
  * envelope. It is never returned by an HTTP endpoint.
  */
object SessionCodec:

  final private case class PersistedCookieV1(name: String, value: String)

  final private case class PersistedSessionV1(
      version: Int,
      accessToken: String,
      tokenType: String,
      refreshToken: String,
      expiresAt: String,
      jwtToken: String,
      cookies: List[PersistedCookieV1]
  )

  private given JsonValueCodec[PersistedSessionV1] = JsonCodecMaker.make

  def encode(session: LuxmedSession): String =
    writeToString(
      PersistedSessionV1(
        version = 1,
        accessToken = session.accessToken.value,
        tokenType = session.tokenType.wireValue,
        refreshToken = session.refreshToken.value,
        expiresAt = session.expiresAt.toString,
        jwtToken = session.jwtToken.value,
        // Sorted by name so the persisted order is real, not an accident of
        // how few cookies happen to fit in a small immutable Map.
        cookies = session.cookies.toList
          .sortBy(_._1)
          .map((name, value) => PersistedCookieV1(name, value.value))
      )
    )

  def decode(json: String): Either[String, LuxmedSession] =
    try
      val persisted = readFromString[PersistedSessionV1](json)
      if persisted.version != 1 then Left("unsupported session version")
      else if persisted.tokenType != TokenType.Bearer.wireValue then
        Left("unsupported token type")
      else
        Right(
          LuxmedSession(
            accessToken = Secret(persisted.accessToken),
            tokenType = TokenType.Bearer,
            refreshToken = Secret(persisted.refreshToken),
            expiresAt = Instant.parse(persisted.expiresAt),
            jwtToken = Secret(persisted.jwtToken),
            cookies = CookieJar(
              persisted.cookies.map(c => c.name -> Secret(c.value))*
            )
          )
        )
    catch case _: Exception => Left("invalid persisted session")
