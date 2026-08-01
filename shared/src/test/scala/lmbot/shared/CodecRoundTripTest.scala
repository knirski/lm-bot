package lmbot.shared

import java.time.{DayOfWeek, Instant, LocalDate, LocalTime}

import com.github.plokhotnyuk.jsoniter_scala.core.*
import lmbot.shared.api.*
import lmbot.shared.api.Codecs.given
import lmbot.shared.domain.*
import sttp.tapir.{Schema, Validator}

class CodecRoundTripTest extends munit.FunSuite:

  private def advertisedValues[A](schema: Schema[A]): List[String] =
    schema.validator match
      case Validator.Enumeration(possibleValues, Some(encode), _) =>
        possibleValues.flatMap(encode(_)).map(_.toString)
      case other =>
        fail(s"expected an enumeration validator, got $other")

  test("LoginRequest round-trips"):
    val original = LoginRequest("krzysiek", "correct horse battery staple")
    val json = writeToString(original)
    assertEquals(readFromString[LoginRequest](json), original)

  test("UserView round-trips for both roles"):
    List(Role.Admin, Role.User).foreach: role =>
      val original =
        UserView(UserId(7L), "mom", "Mom", role, telegramLinked = true)
      assertEquals(readFromString[UserView](writeToString(original)), original)
      // Round-tripping alone would not catch UserId drifting onto a
      // discriminated-object encoding while the Tapir Schema below still
      // advertises a bare number — the same gap Role's own two tests guard.
      assert(
        writeToString(original).contains("\"id\":7"),
        s"expected UserId(7L) to serialise as a bare JSON number: ${writeToString(original)}"
      )

  test("ErrorBody round-trips"):
    val original = ErrorBody("conflict", "username taken")
    assertEquals(readFromString[ErrorBody](writeToString(original)), original)

  // Round-tripping alone cannot catch codec/schema drift: a discriminated object
  // round-trips perfectly while the Tapir Schema advertises a string. These two
  // tests pin the actual bytes, so the declared contract and the wire cannot
  // diverge unnoticed.
  test(
    "Role serialises as a bare JSON string, matching its string-based Schema"
  ):
    assertEquals(writeToString(Role.Admin), "\"Admin\"")
    assertEquals(writeToString(Role.User), "\"User\"")

  test("UserView carries role as a string, not a discriminated object"):
    val json = writeToString(
      UserView(UserId(1L), "admin", "admin", Role.Admin, telegramLinked = false)
    )
    assert(
      json.contains("\"role\":\"Admin\""),
      s"unexpected role encoding: $json"
    )
    assert(!json.contains("\"type\""), s"role leaked a discriminator: $json")

  test(
    "LinkAccountRequest does not serialise its password into the log-friendly toString"
  ):
    val req = LinkAccountRequest("home", "user@example.com", "s3cret")
    assert(
      !req.toString.contains("s3cret"),
      s"password leaked in toString: ${req.toString}"
    )

  test(
    "a login request does not serialise its password into the log-friendly toString"
  ):
    // The wire format must carry the password; the *rendering* must not.
    val req = LoginRequest("krzysiek", "s3cret")
    assert(
      !req.toString.contains("s3cret"),
      s"password leaked in toString: ${req.toString}"
    )

  test("endpoints are described with the expected methods and paths"):
    assertEquals(AuthEndpoints.login.showPathTemplate(), "/api/auth/login")
    assertEquals(AuthEndpoints.me.showPathTemplate(), "/api/auth/me")
    assertEquals(AuthEndpoints.logout.showPathTemplate(), "/api/auth/logout")
    assertEquals(AuthEndpoints.sessionCookieName, "lmbot_session")

  // --- Account / Monitor domain tests ---

  test("AccountStatus serialises as snake_case strings"):
    assertEquals(writeToString(AccountStatus.Active), "\"active\"")
    assertEquals(writeToString(AccountStatus.AuthFailed), "\"auth_failed\"")
    assertEquals(writeToString(AccountStatus.Disabled), "\"disabled\"")
    assertEquals(
      readFromString[AccountStatus]("\"active\""),
      AccountStatus.Active
    )
    assertEquals(
      readFromString[AccountStatus]("\"auth_failed\""),
      AccountStatus.AuthFailed
    )
    assertEquals(
      readFromString[AccountStatus]("\"disabled\""),
      AccountStatus.Disabled
    )

  test("AccountStatus schema advertises exactly the values the codec writes"):
    assertEquals(
      advertisedValues(summon[Schema[AccountStatus]]).toSet,
      Set("active", "auth_failed", "disabled")
    )

  test("MonitorState schema advertises exactly the values the codec writes"):
    assertEquals(
      advertisedValues(summon[Schema[MonitorState]]).toSet,
      Set("active", "paused", "completed", "failed")
    )

  test("MonitorState serialises as lowercase strings"):
    assertEquals(writeToString(MonitorState.Active), "\"active\"")
    assertEquals(writeToString(MonitorState.Paused), "\"paused\"")
    assertEquals(writeToString(MonitorState.Completed), "\"completed\"")
    assertEquals(writeToString(MonitorState.Failed), "\"failed\"")
    assertEquals(
      readFromString[MonitorState]("\"active\""),
      MonitorState.Active
    )
    assertEquals(
      readFromString[MonitorState]("\"paused\""),
      MonitorState.Paused
    )
    assertEquals(
      readFromString[MonitorState]("\"completed\""),
      MonitorState.Completed
    )
    assertEquals(
      readFromString[MonitorState]("\"failed\""),
      MonitorState.Failed
    )

  test("MonitorDraft round-trips with exact wire format"):
    val monitorJson =
      """{"accountId":7,"name":"Dermatologist","city":{"id":3,"name":"Warsaw"},"service":{"id":42,"name":"Dermatology"},"facilities":[{"id":9,"name":"Puławska"}],"doctors":[],"dateFrom":"2026-08-01","dateTo":"2026-08-31","timeFrom":"08:00","timeTo":"16:00","daysOfWeek":["Monday","Wednesday"],"autoBook":false,"intervalMinutes":10}"""
    val decoded = readFromString[MonitorDraft](monitorJson)
    assertEquals(writeToString(decoded), monitorJson)

  test("AccountId serialises as a JSON number"):
    assertEquals(writeToString(AccountId(7L)), "7")
    assertEquals(readFromString[AccountId]("7"), AccountId(7L))

  test("MonitorId serialises as a JSON number"):
    assertEquals(writeToString(MonitorId(1L)), "1")
    assertEquals(readFromString[MonitorId]("1"), MonitorId(1L))

  test("AccountView round-trips with optional fields"):
    val withReason = AccountView(
      AccountId(1L),
      "home",
      "user@example.com",
      AccountStatus.AuthFailed,
      statusReason = Some("bad password"),
      lastSuccessfulLogin = None
    )
    val withReasonJson = writeToString(withReason)
    assertEquals(
      withReasonJson,
      """{"id":1,"label":"home","username":"user@example.com","status":"auth_failed","statusReason":"bad password"}"""
    )
    assertEquals(
      readFromString[AccountView](withReasonJson),
      withReason
    )

    val withLogin = AccountView(
      AccountId(2L),
      "work",
      "admin",
      AccountStatus.Active,
      statusReason = None,
      lastSuccessfulLogin = Some(Instant.parse("2026-07-01T00:00:00Z"))
    )
    val withLoginJson = writeToString(withLogin)
    assertEquals(
      withLoginJson,
      """{"id":2,"label":"work","username":"admin","status":"active","lastSuccessfulLogin":"2026-07-01T00:00:00Z"}"""
    )
    assertEquals(
      readFromString[AccountView](withLoginJson),
      withLogin
    )

  test("AccountView with auth_failed status serialises as snake_case"):
    val view = AccountView(
      AccountId(1L),
      "test",
      "u",
      AccountStatus.AuthFailed,
      statusReason = None,
      lastSuccessfulLogin = None
    )
    val json = writeToString(view)
    assert(
      json.contains("\"status\":\"auth_failed\""),
      s"status should be snake_case: $json"
    )

  test("NamedId round-trips"):
    val original = NamedId(3L, "Warsaw")
    val json = writeToString(original)
    assertEquals(json, """{"id":3,"name":"Warsaw"}""")
    assertEquals(readFromString[NamedId](json), original)

  test("MonitorDraft with empty facilities round-trips"):
    val draft = MonitorDraft(
      accountId = AccountId(1L),
      name = "Test",
      city = NamedId(1L, "City"),
      service = NamedId(2L, "Service"),
      facilities = List.empty,
      doctors = List.empty,
      dateFrom = LocalDate.parse("2026-08-01"),
      dateTo = LocalDate.parse("2026-08-31"),
      timeFrom = LocalTime.parse("08:00"),
      timeTo = LocalTime.parse("16:00"),
      daysOfWeek = List(DayOfWeek.MONDAY),
      autoBook = false
    )
    val json = writeToString(draft)
    assertEquals(
      json,
      """{"accountId":1,"name":"Test","city":{"id":1,"name":"City"},"service":{"id":2,"name":"Service"},"facilities":[],"doctors":[],"dateFrom":"2026-08-01","dateTo":"2026-08-31","timeFrom":"08:00","timeTo":"16:00","daysOfWeek":["Monday"],"autoBook":false,"intervalMinutes":10}"""
    )
    assertEquals(readFromString[MonitorDraft](json), draft)

  test("MonitorView round-trips"):
    val now = Instant.parse("2026-07-30T12:00:00Z")
    val view = MonitorView(
      id = MonitorId(1L),
      accountId = AccountId(7L),
      name = "Dermatologist",
      state = MonitorState.Active,
      city = NamedId(3L, "Warsaw"),
      service = NamedId(42L, "Dermatology"),
      facilities = List(NamedId(9L, "Puławska")),
      doctors = List.empty,
      dateFrom = LocalDate.parse("2026-08-01"),
      dateTo = LocalDate.parse("2026-08-31"),
      timeFrom = LocalTime.parse("08:00"),
      timeTo = LocalTime.parse("16:00"),
      daysOfWeek = List(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
      autoBook = false,
      intervalMinutes = 10,
      createdAt = now,
      updatedAt = now
    )
    val json = writeToString(view)
    assertEquals(
      json,
      """{"id":1,"accountId":7,"name":"Dermatologist","state":"active","city":{"id":3,"name":"Warsaw"},"service":{"id":42,"name":"Dermatology"},"facilities":[{"id":9,"name":"Puławska"}],"doctors":[],"dateFrom":"2026-08-01","dateTo":"2026-08-31","timeFrom":"08:00","timeTo":"16:00","daysOfWeek":["Monday","Wednesday"],"autoBook":false,"intervalMinutes":10,"createdAt":"2026-07-30T12:00:00Z","updatedAt":"2026-07-30T12:00:00Z"}"""
    )
    assertEquals(readFromString[MonitorView](json), view)

  test("LinkAccountRequest round-trips"):
    val original = LinkAccountRequest("home", "user@example.com", "p4ss")
    val json = writeToString(original)
    assertEquals(
      json,
      """{"label":"home","username":"user@example.com","password":"p4ss"}"""
    )
    assertEquals(
      readFromString[LinkAccountRequest](json),
      original
    )

  test("Dictionary types round-trip"):
    val city = DictionaryCity(1L, "Warsaw")
    assertEquals(writeToString(city), """{"id":1,"name":"Warsaw"}""")
    assertEquals(readFromString[DictionaryCity](writeToString(city)), city)
    val svc = DictionaryService(42L, "Dermatology")
    assertEquals(
      writeToString(svc),
      """{"id":42,"name":"Dermatology"}"""
    )
    assertEquals(readFromString[DictionaryService](writeToString(svc)), svc)
    val fac = DictionaryFacility(9L, "Puławska")
    assertEquals(writeToString(fac), """{"id":9,"name":"Puławska"}""")
    assertEquals(readFromString[DictionaryFacility](writeToString(fac)), fac)
    val doc = DictionaryDoctor(101L, "Dr. House")
    assertEquals(
      writeToString(doc),
      """{"id":101,"name":"Dr. House"}"""
    )
    assertEquals(readFromString[DictionaryDoctor](writeToString(doc)), doc)

  test("FacilitiesDoctorsResponse round-trips"):
    val resp = FacilitiesDoctorsResponse(
      facilities = List(DictionaryFacility(9L, "Puławska")),
      doctors = List(DictionaryDoctor(101L, "Dr. House"))
    )
    val json = writeToString(resp)
    assertEquals(
      json,
      """{"facilities":[{"id":9,"name":"Puławska"}],"doctors":[{"id":101,"name":"Dr. House"}]}"""
    )
    assertEquals(
      readFromString[FacilitiesDoctorsResponse](json),
      resp
    )

  // --- Endpoint path template tests ---

  test("account endpoints are described with the expected methods and paths"):
    assertEquals(
      AccountEndpoints.create.showPathTemplate(),
      "/api/accounts"
    )
    assertEquals(AccountEndpoints.list.showPathTemplate(), "/api/accounts")
    assertEquals(
      AccountEndpoints.delete.showPathTemplate(),
      "/api/accounts/{accountId}"
    )

  test(
    "dictionary endpoints are described with the expected methods and paths"
  ):
    assertEquals(
      DictionaryEndpoints.cities.showPathTemplate(),
      "/api/accounts/{accountId}/dictionaries/cities"
    )
    assertEquals(
      DictionaryEndpoints.services.showPathTemplate(),
      "/api/accounts/{accountId}/dictionaries/services"
    )
    assertEquals(
      DictionaryEndpoints.facilitiesDoctors.showPathTemplate(),
      "/api/accounts/{accountId}/dictionaries/facilities-doctors?cityId={cityId}&serviceId={serviceId}"
    )

  test("monitor endpoints are described with the expected methods and paths"):
    assertEquals(MonitorEndpoints.create.showPathTemplate(), "/api/monitors")
    assertEquals(MonitorEndpoints.list.showPathTemplate(), "/api/monitors")
    assertEquals(
      MonitorEndpoints.get.showPathTemplate(),
      "/api/monitors/{monitorId}"
    )
    assertEquals(
      MonitorEndpoints.update.showPathTemplate(),
      "/api/monitors/{monitorId}"
    )
    assertEquals(
      MonitorEndpoints.pause.showPathTemplate(),
      "/api/monitors/{monitorId}/pause"
    )
    assertEquals(
      MonitorEndpoints.resume.showPathTemplate(),
      "/api/monitors/{monitorId}/resume"
    )
    assertEquals(
      MonitorEndpoints.delete.showPathTemplate(),
      "/api/monitors/{monitorId}"
    )
