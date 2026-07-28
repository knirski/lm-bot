package lmbot.backend.luxmed

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import lmbot.backend.config.Secret
import lmbot.backend.luxmed.CookieJar
import lmbot.backend.luxmed.model.*
import lmbot.backend.luxmed.model.WireCodecs.given
import java.time.Instant

class WireCodecTest extends munit.FunSuite:

  // jsoniter does not auto-derive list codecs in Scala 3; provide locally.
  private given listCityCodec: JsonValueCodec[List[City]] = JsonCodecMaker.make

  private def fixture(name: String): String =
    val is = getClass.getResourceAsStream(s"/luxmed/$name")
    try scala.io.Source.fromInputStream(is).mkString
    finally is.close()

  private val sampleSession = LuxmedSession(
    accessToken = Secret("ACCESS_1"),
    tokenType = "bearer",
    refreshToken = Secret("REFRESH_1"),
    expiresAt = Instant.parse("2026-08-03T12:00:00Z"),
    jwtToken = Secret("JWT_1"),
    cookies = CookieJar("session" -> Secret("sess_1"))
  )

  test("OAuth fields decode from their measured snake_case wire names"):
    val value =
      readFromString[OAuthTokens](fixture("auth-password-success.json"))
    assertEquals(value.expiresIn, 599)
    assertEquals(value.tokenType, "bearer")
    assertEquals(value.refreshToken.value, "REFRESH_1")

  test("OAuth refresh tokens also decode correctly"):
    val value =
      readFromString[OAuthTokens](fixture("auth-refresh-success.json"))
    assertEquals(value.expiresIn, 600)
    assertEquals(value.refreshToken.value, "REFRESH_2")

  test("both datetime forms normalize to Europe/Warsaw"):
    val response =
      readFromString[TermsResponse](fixture("terms-dual-datetime.json"))
    val starts = response.termsForService.termsForDays
      .flatMap(_.terms)
      .map(_.dateTimeFrom.value)
    assertEquals(starts.map(_.getZone.getId).distinct, List("Europe/Warsaw"))
    assertEquals(starts.map(_.toLocalTime.toString), List("09:00", "10:00"))

  test(
    "new cookies replace old cookies by name without dropping unrelated cookies"
  ):
    val merged = CookieJar("A" -> Secret("old"), "B" -> Secret("keep"))
      .merge(List("A" -> Secret("new"), "C" -> Secret("added")))
    assertEquals(merged.get("A").map(_.value), Some("new"))
    assertEquals(merged.names, Set("a", "b", "c"))

  test("cookie jar is case-insensitive for name lookup"):
    val jar = CookieJar("SessionId" -> Secret("abc"))
    assertEquals(jar.get("sessionid").map(_.value), Some("abc"))
    assertEquals(jar.get("SESSIONID").map(_.value), Some("abc"))

  test("session rendering never reveals bearer credentials"):
    val rendered = sampleSession.toString
    List("ACCESS_1", "REFRESH_1", "JWT_1").foreach(secret =>
      assert(!rendered.contains(secret), s"$secret leaked into toString")
    )

  test("cities decode from JSON array"):
    val cities = readFromString[List[City]](fixture("cities.json"))
    assert(cities.exists(_.name == "Białystok"), "cities contain Białystok")
    assertEquals(cities.find(_.id == 70).map(_.name), Some("Białystok"))

  test("facilities-and-doctors decode correctly"):
    val result = readFromString[FacilitiesAndDoctors](
      fixture("facilities-and-doctors.json")
    )
    assertEquals(result.doctors.size, 2)
    assertEquals(result.facilities.size, 2)
    assert(
      result.doctors.exists(_.lastName.contains("SHEVCHENKO")),
      "doctor exists"
    )

  test("forgery token decodes"):
    val token = readFromString[XsrfToken](fixture("forgery-token.json"))
    assert(token.token.value.nonEmpty, "token is not empty")

  test("lock success decodes"):
    val response =
      readFromString[LockTermResponse](fixture("lock-success.json"))
    assertEquals(response.value.temporaryReservationId, 222222L)
    assert(response.value.valuations.nonEmpty, "valuations not empty")
    assertEquals(response.value.valuations.head.valuationType, 1L)

  test("confirm success decodes"):
    val response =
      readFromString[ConfirmResponse](fixture("confirm-success.json"))
    assertEquals(response.value.reservationId, 2222222L)
    assertEquals(response.value.canSelfConfirm, false)
