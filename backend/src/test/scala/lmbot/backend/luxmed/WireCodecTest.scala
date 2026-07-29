package lmbot.backend.luxmed

import java.time.Instant

import scala.io.{Codec, Source}

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import lmbot.backend.config.Secret
import lmbot.backend.luxmed.CookieJar
import lmbot.backend.luxmed.model.*
import lmbot.backend.luxmed.model.WireCodecs.given

class WireCodecTest extends munit.FunSuite:

  // jsoniter does not auto-derive list codecs in Scala 3; provide locally.
  private given listCityCodec: JsonValueCodec[List[City]] = JsonCodecMaker.make

  private def fixture(name: String): String =
    val path = s"/luxmed/$name"
    val is = Option(getClass.getResourceAsStream(path))
      .getOrElse(throw IllegalArgumentException(s"Missing fixture: $path"))
    try Source.fromInputStream(is)(using Codec.UTF8).mkString
    finally is.close()

  private val sampleSession = LuxmedSession(
    accessToken = Secret("ACCESS_1"),
    tokenType = TokenType.Bearer,
    refreshToken = Secret("REFRESH_1"),
    expiresAt = Instant.parse("2026-08-03T12:00:00Z"),
    jwtToken = Secret("JWT_1"),
    cookies = CookieJar("session" -> Secret("sess_1"))
  )

  test("OAuth fields decode from their measured snake_case wire names"):
    val value =
      readFromString[OAuthTokens](fixture("auth-password-success.json"))
    assertEquals(value.expiresIn, 599)
    assertEquals(value.tokenType, TokenType.Bearer)
    assertEquals(value.refreshToken.value, "REFRESH_1")
    assertEquals(
      writeToString(value),
      """{"access_token":"ACCESS_1","expires_in":599,"refresh_token":"REFRESH_1","token_type":"bearer"}"""
    )

  test("OAuth refresh tokens also decode correctly"):
    val value =
      readFromString[OAuthTokens](fixture("auth-refresh-success.json"))
    assertEquals(value.expiresIn, 600)
    assertEquals(value.refreshToken.value, "REFRESH_2")
    assertEquals(
      writeToString(value),
      """{"access_token":"ACCESS_2","expires_in":600,"refresh_token":"REFRESH_2","token_type":"bearer"}"""
    )

  test("missing fixture reports its resource path"):
    val error = intercept[IllegalArgumentException]:
      fixture("does-not-exist.json")
    assertEquals(
      error.getMessage,
      "Missing fixture: /luxmed/does-not-exist.json"
    )

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
    assertEquals(merged.names, Set("A", "B", "C"))

  test("cookie jar preserves names and replaces only exact matches"):
    val jar = CookieJar(
      "SID" -> Secret("upper"),
      "sid" -> Secret("lower")
    ).merge(List("SID" -> Secret("replaced")))
    assertEquals(jar.get("SID").map(_.value), Some("replaced"))
    assertEquals(jar.get("sid").map(_.value), Some("lower"))
    assertEquals(jar.names, Set("SID", "sid"))

  test("request cookie header includes cookie names and values"):
    val header = CookieJar(
      "A" -> Secret("one"),
      "B" -> Secret("two")
    ).requestCookies.head._2
    assertEquals(header.split("; ").toSet, Set("A=one", "B=two"))

  test("session rendering never reveals bearer credentials"):
    val rendered = sampleSession.toString
    List("ACCESS_1", "REFRESH_1", "JWT_1", "sess_1").foreach(secret =>
      assert(!rendered.contains(secret), s"$secret leaked into toString")
    )

  test("cities decode from JSON array"):
    val cities = readFromString[List[City]](fixture("cities.json"))
    assert(cities.exists(_.name == "Białystok"), "cities contain Białystok")
    assertEquals(cities.find(_.id == CityId(70)).map(_.name), Some("Białystok"))

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
    assertEquals(response.value.temporaryReservationId.value, 222222L)
    assert(response.value.valuations.nonEmpty, "valuations not empty")
    assertEquals(response.value.valuations.head.valuationType, 1L)

  test("confirm success decodes"):
    val response =
      readFromString[ConfirmResponse](fixture("confirm-success.json"))
    assertEquals(response.value.reservationId.value, 2222222L)
    assertEquals(response.value.canSelfConfirm, false)

  test("service variant codec handles extra fields with skip"):
    val json =
      """{"id":1,"name":"test","expanded":false,"children":[],"isTelemedicine":false,"paymentType":1,"extraField":"skip"}"""
    val result = readFromString[ServiceVariant](json)
    assertEquals(result.id.value, 1L)
    assertEquals(result.name, "test")

  test("service variant codec handles children array"):
    val json =
      """{"id":1,"name":"parent","expanded":true,"children":[{"id":2,"name":"child","expanded":false,"children":[],"isTelemedicine":false,"paymentType":1}],"isTelemedicine":false,"paymentType":0}"""
    val result = readFromString[ServiceVariant](json)
    assertEquals(result.name, "parent")
    assertEquals(result.children.size, 1)
    assertEquals(result.children.head.name, "child")
