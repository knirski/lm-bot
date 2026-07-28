# lm-bot Plan 3: Luxmed API Client & Mock Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a backend-internal Luxmed client that authenticates, refreshes
rotating sessions safely, reads dictionaries and appointment terms, and exposes
the XSRF-protected reservation primitives against a deterministic local mock.

**Architecture:** `LuxmedTransport` owns the wire and
`LuxmedClient` owns session policy. One capability-style `AccountGate` uses a
Gears `Semaphore` to serialize an account operation and gives that operation a
permit which spaces every underlying HTTP request. A per-account
`SessionStore` compare-and-set contract makes refresh-token handoff explicit;
Plan 3 implements it in memory and Plan 4 supplies encrypted PostgreSQL
durability.

**Tech Stack:** Scala 3.8.4, Gears 0.3.1, sttp client 3.11.0,
jsoniter-scala 2.39.1, JDK 25 `HttpClient`/`HttpServer`, MUnit 1.3.4,
sbt 2.0.4.

## Authoritative references

- Product/design authority:
  `docs/superpowers/specs/2026-07-27-lm-bot-prd-design.md`, especially
  §§3.2, 5.3, 5.4, 5.7, 7, 8, and 10.
- Measured auth facts:
  `docs/superpowers/reports/2026-07-27-luxmed-api-analysis.md`.
- Request/response shapes and hybrid NewPortal flow:
  `dyrkin/luxmed-bot` commit
  `c970447b319c9b06a06497ce972972903902491f`, particularly
  `api/src/main/scala/com/lbs/api/LuxmedApi.scala`,
  `api/src/main/scala/com/lbs/api/ApiBase.scala`, and
  `api/src/main/scala/com/lbs/api/json/model/`.

The analysis report's `/api/terms/index`, `/api/lockterm`, `/api/confirm`, and
`/api/releaseterm` table was not exercised. Do not implement those paths.
Authenticated operations use the verified `NewPortal/*` paths in the PRD and
the pinned upstream client.

## Global constraints

- Work inside the flake devShell. Temurin **25**, Node **26+**, Scala **3.8.4**,
  Gears **0.3.1**, and sbt **2.0.4** are load-bearing.
- Gears is the only async vocabulary. Production signatures may use
  `(using Async)` but must not name `scala.concurrent.Future` or JS `Promise`.
- This is direct-style functional Scala: immutable values, plain constructor
  wiring, `Either[LuxmedError, A]` for expected failures, and exhaustive
  matching over closed Scala 3 ADTs. Do not introduce `F[_]`, Cats Effect,
  effect transformers, DI, reflection, or exception-based domain control flow.
- Passwords, access tokens, refresh tokens, JWTs, and cookie values use
  `lmbot.backend.config.Secret`; they must never appear in `toString`, logs, or
  error diagnostics.
- `LUXMED_APP_VERSION` defaults to **`4.44.0`**. Access-token expiry is about
  600 seconds; proactive refresh starts with **300 seconds remaining**.
- One account operation is active at a time. Every underlying Luxmed HTTP
  request is separated from the previous request by the configured minimum
  spacing.
- All Luxmed-facing dates and times normalize to **`Europe/Warsaw`**.
- Redirects are never followed automatically. A relevant 302 is a typed
  session-expiry signal.
- Mock response fixtures are literal upstream/recorded JSON. Never generate
  expected response fixtures with the production codecs.
- Before completion, run the guided real-API conformance exploration once. Its
  required path uses one password grant, one refresh, NewPortal bootstrap,
  dictionaries, one user-selected search, and XSRF. It spaces at most 12
  requests by at least five seconds and never retries or performs a second
  password grant. An optional lock must always be paired with release; real
  confirm is forbidden in Plan 3.
- Plan 3 adds no database table, Flyway migration, lm-bot HTTP endpoint, or UI.
- Use `testFull`, never bare `test`. Format Scala with `sbt scalafmtAll` before
  every commit. Do not commit until `nix flake check` passes.

## File structure

```text
backend/src/main/scala/lmbot/backend/
├── config/Config.scala                         # add Luxmed app version
└── luxmed/
    ├── AccountGate.scala                       # semaphore + request spacing
    ├── CookieJar.scala                         # immutable merge-by-name jar
    ├── LuxmedClient.scala                      # session policy + public API
    ├── LuxmedConfig.scala                      # base URLs and client headers
    ├── LuxmedError.scala                       # closed failure ADT
    ├── LuxmedTransport.scala                   # all HTTP/wire behavior
    ├── JsonShape.scala                         # value-free conformance shape
    ├── SessionStore.scala                      # CAS contract + memory store
    └── model/
        ├── AuthModels.scala                    # OAuth/session/XSRF values
        ├── DictionaryModels.scala              # cities/services/facilities
        ├── ReservationModels.scala             # lock/confirm/release wire
        ├── TermsModels.scala                   # terms and Warsaw datetime
        └── WireCodecs.scala                    # jsoniter codecs only

backend/src/test/scala/lmbot/backend/luxmed/
├── AccountGateTest.scala
├── DictionaryAndTermsTest.scala
├── ErrorClassificationTest.scala
├── GuidedContractExplorer.scala               # manual, never a CI suite
├── LuxmedClientAuthTest.scala
├── MockConformanceTest.scala
├── ReservationPrimitivesTest.scala
├── SessionStoreTest.scala
├── WireCodecTest.scala
└── support/
    ├── FakeTime.scala
    ├── GearsTest.scala
    └── MockLuxmedServer.scala

backend/src/test/resources/luxmed/
├── auth-password-success.json
├── auth-refresh-success.json
├── cities.json
├── confirm-success.json
├── error-challenge.json
├── error-list-version.json
├── error-map-validation.json
├── error-single-session.json
├── facilities-and-doctors.json
├── forgery-token.json
├── lock-success.json
├── service-variants.json
└── terms-dual-datetime.json
```

---

### Task 1: Promote sttp and add Luxmed configuration

**Files:**

- Modify: `build.sbt`
- Modify: `backend/src/main/scala/lmbot/backend/config/Config.scala`
- Modify: `backend/src/test/scala/lmbot/backend/ConfigTest.scala`

**Interfaces:**

- Produces: `Config.luxmedAppVersion: String`
- Produces: production access to `sttp-client3 core` in `backend`

- [ ] **Step 1: Write failing configuration tests**

Add these assertions to `ConfigTest` using its existing valid environment
helper:

```scala
test("Luxmed app version defaults to the measured refresh-compatible floor"):
  val Right(config) = Config.fromEnv(validEnv): @unchecked
  assertEquals(config.luxmedAppVersion, "4.44.0")

test("Luxmed app version is configurable without changing the client"):
  val Right(config) =
    Config.fromEnv(validEnv.updated("LUXMED_APP_VERSION", "4.45.1")): @unchecked
  assertEquals(config.luxmedAppVersion, "4.45.1")

test("an empty Luxmed app version is rejected"):
  val result = Config.fromEnv(validEnv.updated("LUXMED_APP_VERSION", ""))
  assert(result.left.exists(_.contains("LUXMED_APP_VERSION must not be empty")))
```

- [ ] **Step 2: Run the focused test and verify red**

Run:

```bash
sbt "backend/testOnly lmbot.backend.ConfigTest"
```

Expected: compilation fails because `luxmedAppVersion` does not exist.

- [ ] **Step 3: Implement the configuration field**

Add `luxmedAppVersion: String` to `Config`. Parse it with an explicit
non-empty default:

```scala
val luxmedAppVersion =
  env.get("LUXMED_APP_VERSION") match
    case None                    => "4.44.0"
    case Some(value) if value.nonEmpty => value
    case Some(_) =>
      errors += "LUXMED_APP_VERSION must not be empty"
      "4.44.0"
```

Pass the value into the constructed `Config`.

- [ ] **Step 4: Promote sttp core to a production dependency**

In `backend` dependencies, change:

```scala
"com.softwaremill.sttp.client3" %% "core" % Vsttp % Test
```

to:

```scala
"com.softwaremill.sttp.client3" %% "core" % Vsttp
```

Do not add an async sttp backend: the JDK synchronous backend runs inside
Gears-managed virtual-thread direct style.

- [ ] **Step 5: Verify and commit**

Run:

```bash
sbt "backend/testOnly lmbot.backend.ConfigTest"
sbt backend/testFull
sbt scalafmtAll
nix flake check
```

Expected: all commands pass and `backend/testFull` reports non-zero tests.

Commit:

```bash
git add build.sbt backend/src/main/scala/lmbot/backend/config/Config.scala \
  backend/src/test/scala/lmbot/backend/ConfigTest.scala
git commit -m "build: prepare Luxmed client configuration"
```

---

### Task 2: Define the wire model, cookie jar, codecs, and literal fixtures

**Files:**

- Create: `backend/src/main/scala/lmbot/backend/luxmed/CookieJar.scala`
- Create: `backend/src/main/scala/lmbot/backend/luxmed/model/AuthModels.scala`
- Create: `backend/src/main/scala/lmbot/backend/luxmed/model/DictionaryModels.scala`
- Create: `backend/src/main/scala/lmbot/backend/luxmed/model/TermsModels.scala`
- Create: `backend/src/main/scala/lmbot/backend/luxmed/model/ReservationModels.scala`
- Create: `backend/src/main/scala/lmbot/backend/luxmed/model/WireCodecs.scala`
- Create: the thirteen files under `backend/src/test/resources/luxmed/`
- Create: `backend/src/test/scala/lmbot/backend/luxmed/WireCodecTest.scala`

**Interfaces:**

- Produces:
  `CookieJar.empty`, `CookieJar.merge`, `CookieJar.get`, and
  `CookieJar.requestCookies`
- Produces: `Credentials`, `OAuthTokens`, `LuxmedSession`, `XsrfToken`
- Produces: `City`, `ServiceVariant`, `Doctor`, `Facility`,
  `FacilitiesAndDoctors`
- Produces: `TermsQuery`, `TermsResponse`, `Term`, `LuxmedDateTime`
- Produces: `LockTermRequest`, `LockTermResponse`, `ConfirmRequest`,
  `ConfirmResponse`, `Valuation`
- Produces: jsoniter `JsonValueCodec` instances in `WireCodecs.given`

- [ ] **Step 1: Add literal fixture files**

Transcribe fixtures from the pinned upstream model comments/tests and the
redacted Plan 2 auth payloads. Keep upstream JSON field spelling, including
snake_case OAuth fields and camelCase NewPortal fields.

The two auth fixtures are exactly:

```json
{"access_token":"ACCESS_1","expires_in":599,"refresh_token":"REFRESH_1","token_type":"bearer"}
```

```json
{"access_token":"ACCESS_2","expires_in":600,"refresh_token":"REFRESH_2","token_type":"bearer"}
```

`terms-dual-datetime.json` must contain at least two terms in one response:

```json
{
  "correlationId":"00000000-0000-0000-0000-000000000000",
  "termsForService":{
    "additionalData":{"isPreparationRequired":false,"preparationItems":[]},
    "termsForDays":[{
      "day":"2026-08-03T00:00:00",
      "terms":[
        {
          "clinic":"LX Warszawa",
          "clinicId":10,
          "clinicGroupId":11,
          "dateTimeFrom":"2026-08-03T09:00:00",
          "dateTimeTo":"2026-08-03T09:15:00",
          "doctor":{"academicTitle":"lek.","firstName":"Anna","genderId":2,"id":20,"lastName":"Nowak"},
          "impedimentText":"",
          "isAdditional":false,
          "isImpediment":false,
          "isTelemedicine":false,
          "roomId":30,
          "scheduleId":40,
          "serviceId":50
        },
        {
          "clinic":"LX Warszawa",
          "clinicId":10,
          "clinicGroupId":11,
          "dateTimeFrom":"2026-08-03T10:00:00+02:00",
          "dateTimeTo":"2026-08-03T10:15:00+02:00",
          "doctor":{"academicTitle":"lek.","firstName":"Jan","genderId":1,"id":21,"lastName":"Kowalski"},
          "impedimentText":null,
          "isAdditional":false,
          "isImpediment":false,
          "isTelemedicine":false,
          "roomId":31,
          "scheduleId":41,
          "serviceId":50
        }
      ]
    }]
  }
}
```

Transcribe dictionaries from `DictionaryModels.scala`, terms from
`TermsForService.scala`/`TermsForDays.scala`/`Term.scala`, and reservation
payloads from `ReservationLocktermResponse.scala`,
`ReservationConfirmResponse.scala`, and their nested valuation models at the
pinned upstream commit. Preserve every upstream JSON field and its measured
camelCase spelling in the literal files; do not substitute the analysis
report's unverified mobile paths or lmassist DTOs. `error-challenge.json` is a
deliberately unknown but challenge-shaped object:

```json
{"challengeId":"challenge-1","method":"sms","message":"Additional verification required"}
```

- [ ] **Step 2: Write failing codec and cookie tests**

The tests must read classpath resources as literal bytes and assert:

```scala
test("OAuth fields decode from their measured snake_case wire names"):
  val value = readFromString[OAuthTokens](fixture("auth-password-success.json"))
  assertEquals(value.expiresIn, 599)
  assertEquals(value.tokenType, "bearer")
  assertEquals(value.refreshToken.value, "REFRESH_1")

test("both datetime forms normalize to Europe/Warsaw"):
  val response =
    readFromString[TermsResponse](fixture("terms-dual-datetime.json"))
  val starts = response.termsForService.termsForDays.flatMap(_.terms).map(_.dateTimeFrom.value)
  assertEquals(starts.map(_.getZone.getId).distinct, List("Europe/Warsaw"))
  assertEquals(starts.map(_.toLocalTime.toString), List("09:00", "10:00"))

test("new cookies replace old cookies by name without dropping unrelated cookies"):
  val merged = CookieJar("A" -> Secret("old"), "B" -> Secret("keep"))
    .merge(List("A" -> Secret("new"), "C" -> Secret("added")))
  assertEquals(merged.get("A").map(_.value), Some("new"))
  assertEquals(merged.names, Set("A", "B", "C"))

test("session rendering never reveals bearer credentials"):
  val rendered = sampleSession.toString
  List("ACCESS_1", "REFRESH_1", "JWT_1").foreach(secret =>
    assert(!rendered.contains(secret))
  )
```

- [ ] **Step 3: Run the codec suite and verify red**

Run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.WireCodecTest"
```

Expected: compilation fails because the model and codecs do not exist.

- [ ] **Step 4: Implement immutable models and codecs**

Use final case classes and the existing `config.Secret`. The core auth types
are:

```scala
final case class OAuthTokens(
    accessToken: Secret,
    expiresIn: Int,
    refreshToken: Secret,
    tokenType: String
)

final case class Credentials(username: String, password: Secret)

final case class LuxmedSession(
    accessToken: Secret,
    tokenType: String,
    refreshToken: Secret,
    expiresAt: Instant,
    jwtToken: Secret,
    cookies: CookieJar
)

final case class LuxmedDateTime(value: ZonedDateTime)
```

Implement `LuxmedDateTime` decoding by trying
`DateTimeFormatter.ISO_OFFSET_DATE_TIME` first and
`DateTimeFormatter.ISO_LOCAL_DATE_TIME` second. Offset values convert with
`withZoneSameInstant(ZoneId.of("Europe/Warsaw"))`; bare values attach that zone
with `atZone`.

Configure derived NewPortal codecs explicitly to accept additional upstream
fields while retaining their measured camelCase names:

```scala
private inline def newPortalConfig =
  CodecMakerConfig
    .withSkipUnexpectedFields(true)
    .withTransientDefault(false)
```

Do not apply one global snake-case mapper. Implement the `OAuthTokens` codec
explicitly so its four accepted names are exactly `access_token`, `expires_in`,
`refresh_token`, and `token_type`; reject missing or duplicate fields and skip
unknown fields. NewPortal DTOs use `newPortalConfig` and retain their measured
camelCase spelling. Pin both families by asserting literal fixture bytes, not
only round trips.

- [ ] **Step 5: Verify and commit**

Run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.WireCodecTest"
sbt backend/testFull
sbt scalafmtAll
nix flake check
```

Commit:

```bash
git add backend/src/main/scala/lmbot/backend/luxmed \
  backend/src/test/scala/lmbot/backend/luxmed/WireCodecTest.scala \
  backend/src/test/resources/luxmed
git commit -m "feat: model Luxmed wire contracts"
```

---

### Task 3: Build the mock server, typed errors, and raw transport

**Files:**

- Create: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedConfig.scala`
- Create: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedError.scala`
- Create: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedTransport.scala`
- Create: `backend/src/test/scala/lmbot/backend/luxmed/support/MockLuxmedServer.scala`
- Create: `backend/src/test/scala/lmbot/backend/luxmed/support/GearsTest.scala`
- Create: `backend/src/test/scala/lmbot/backend/luxmed/ErrorClassificationTest.scala`

**Interfaces:**

- Produces:
  `enum LuxmedError`, including `AuthFailed`, `UnexpectedAuthResponse`,
  `SessionExpired`, `VersionRejected`, `ApiRejected`, `RateLimited`,
  `Transient`, `NetworkFailure`, `DecodeFailed`, `PersistenceFailed`,
  `ProtocolViolation`, and `SlotGone`
- Produces:
  `LuxmedTransport.send[A](request)(using Async, RequestPermit):
  Either[LuxmedError, TransportResponse[A]]`
- Produces: a random-port `MockLuxmedServer` with queued responses and captured
  raw requests

- [ ] **Step 1: Write failing request/error tests**

Start the mock on port `0` and assert:

```scala
test("transport does not follow a session-expiry redirect"):
  mock.enqueue(
    status = 302,
    headers = Map("Location" -> "/PatientPortal/LogOn"),
    body = ""
  )
  val result = runAsync:
    given RequestPermit = testPermit
    transport.getString("/redirect")
  assertEquals(result, Left(LuxmedError.SessionExpired))
  assertEquals(mock.requests.size, 1)

test("429 is RateLimited"):
  mock.enqueue(status = 429, body = """{"message":"slow down"}""")
  val result = runAsync:
    given RequestPermit = testPermit
    transport.getString("/limited")
  assertEquals(result, Left(LuxmedError.RateLimited))

test("a 409 credential message is AuthFailed"):
  mock.enqueue(
    status = 409,
    body = """{"error":{"code":1,"message":"invalid login or password"}}"""
  )
  val result = runAsync:
    given RequestPermit = testPermit
    transport.getString("/token")
  assertEquals(result, Left(LuxmedError.AuthFailed))
```

Also cover the Polish credential phrase, error list, error map, single error,
session-expired message, 5xx, malformed success JSON, and a connection failure.

- [ ] **Step 2: Run and verify red**

Run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.ErrorClassificationTest"
```

Expected: compilation fails because transport, mock, and error ADT do not
exist.

- [ ] **Step 3: Implement the mock server**

Use `com.sun.net.httpserver.HttpServer` with a virtual-thread executor. A
captured request contains:

```scala
final case class RecordedRequest(
    method: String,
    path: String,
    rawQuery: Option[String],
    headers: Map[String, List[String]],
    body: String
)
```

Responses are queued values containing status, multi-value headers, and a
literal body string. `close()` stops the server. Never import `WireCodecs` to
generate a response.

- [ ] **Step 4: Add the Gears test entrypoint**

Tests mix in:

```scala
trait GearsTest:
  import gears.async.default.given

  protected def runAsync[A](body: gears.async.Async.Spawn ?=> A): A =
    gears.async.Async.fromSync(body)
```

This is the only meaning of `runAsync` in this plan.

- [ ] **Step 5: Implement config, errors, redaction, and transport**

`LuxmedConfig` contains injectable old/new `Uri` bases plus:

```scala
final case class LuxmedConfig(
    oldApi: Uri,
    newApi: Uri,
    appVersion: String,
    deviceUuid: UUID,
    apiLevel: Int = 33,
    deviceModel: String = "Samsung Galaxy S23"
)
```

`LuxmedTransport` uses `HttpClientSyncBackend`, `followRedirects(false)`, and
`asStringAlways`. It accumulates every `Set-Cookie` header into `CookieJar`,
normalizes header lookup case-insensitively, and decodes only after status/error
classification. Its diagnostic value is:

```scala
final case class RedactedResponse(
    status: Int,
    headers: Map[String, String],
    bodySummary: String
)
```

Redact authorization, cookies, passwords, OAuth tokens, JWT-like strings, email
addresses, and phone-number-shaped values before constructing
`DecodeFailed`.

Define the narrow pacing capability beside the transport:

```scala
private[luxmed] trait RequestPermit:
  def beforeRequest()(using Async): Unit
```

Every transport send calls `beforeRequest()` immediately before
`backend.send`. Transport tests supply a package-local deterministic permit;
Task 4 makes the only production permit.

- [ ] **Step 6: Verify and commit**

Run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.ErrorClassificationTest"
sbt backend/testFull
sbt scalafmtAll
nix flake check
```

Commit:

```bash
git add backend/src/main/scala/lmbot/backend/luxmed \
  backend/src/test/scala/lmbot/backend/luxmed
git commit -m "feat: add typed Luxmed transport"
```

---

### Task 4: Serialize account operations and pace every request

**Files:**

- Create: `backend/src/main/scala/lmbot/backend/luxmed/AccountGate.scala`
- Create: `backend/src/test/scala/lmbot/backend/luxmed/support/FakeTime.scala`
- Create: `backend/src/test/scala/lmbot/backend/luxmed/AccountGateTest.scala`
- Modify: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedTransport.scala`

**Interfaces:**

- Produces:
  `AccountGate.serialized[A](body: AccountGate.Permit ?=> A)(using Async): A`
- Produces: `AccountGate.Permit extends RequestPermit`
- Consumes: transport operations require a `RequestPermit`

- [ ] **Step 1: Write deterministic failing gate tests**

The tests prove:

```scala
test("two account operations never overlap"):
  runAsync:
    Async.group:
      val first = Future(gate.serialized { probe.enterAndWait() })
      probe.awaitEntered()
      val second = Future(gate.serialized { probe.recordSecond() })
      assertEquals(probe.maximumConcurrent, 1)
      probe.releaseFirst()
      first.awaitResult
      second.awaitResult

test("a permit spaces each HTTP request, not only each public operation"):
  runAsync:
    gate.serialized:
      summon[AccountGate.Permit].beforeRequest()
      fakeTime.advance(200.millis)
      summon[AccountGate.Permit].beforeRequest()
  assertEquals(fakeTime.sleeps, List(800.millis))
```

Use `Future` only as `gears.async.Future`, never
`scala.concurrent.Future`.

- [ ] **Step 2: Run and verify red**

Run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.AccountGateTest"
```

Expected: compilation fails because `AccountGate` does not exist.

- [ ] **Step 3: Implement the capability-style gate**

The shape is:

```scala
final class AccountGate(
    minimumSpacing: FiniteDuration,
    now: () => Instant,
    sleeper: Sleeper
):
  private val semaphore = Semaphore(1)

  def serialized[A](body: AccountGate.Permit ?=> A)(using Async): A =
    val guard = semaphore.acquire()
    try body(using permit)
    finally guard.release()

trait Sleeper:
  def sleep(duration: FiniteDuration)(using Async): Unit
```

Only `AccountGate` can construct `Permit`. `beforeRequest()` calculates the
remaining duration, sleeps through the injected direct-style `Sleeper`, then
records the actual request start. `LuxmedTransport.send` calls
`beforeRequest()` immediately before `backend.send`.

- [ ] **Step 4: Verify and commit**

Run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.AccountGateTest"
sbt backend/testFull
sbt scalafmtAll
nix flake check
```

Commit:

```bash
git add backend/src/main/scala/lmbot/backend/luxmed \
  backend/src/test/scala/lmbot/backend/luxmed
git commit -m "feat: serialize and pace Luxmed requests"
```

---

### Task 5: Implement authentication, CAS session storage, and full refresh

**Files:**

- Create: `backend/src/main/scala/lmbot/backend/luxmed/SessionStore.scala`
- Create: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedClient.scala`
- Create: `backend/src/test/scala/lmbot/backend/luxmed/SessionStoreTest.scala`
- Create: `backend/src/test/scala/lmbot/backend/luxmed/LuxmedClientAuthTest.scala`
- Modify: `backend/src/test/scala/lmbot/backend/luxmed/support/MockLuxmedServer.scala`

**Interfaces:**

- Produces:
  `SessionStore.load`, `replace(expected: Option[Secret], updated)`, `clear`
- Produces: `InMemorySessionStore`
- Produces:
  `LuxmedClient.authenticate()(using Async):
  Either[LuxmedError, LuxmedSession]`
- Produces:
  `LuxmedClient.withSession[A](operation: AccountGate.Permit ?=>
  LuxmedSession => Either[LuxmedError, A])(using Async):
  Either[LuxmedError, A]`

- [ ] **Step 1: Write failing in-memory CAS tests**

Cover initial insert, matching replacement, stale-token rejection, load, and
clear:

```scala
test("replace is compare-and-set on the refresh token"):
  val store = InMemorySessionStore()
  assertEquals(store.replace(None, session1), Right(()))
  assertEquals(
    store.replace(Some(Secret("wrong")), session2),
    Left(SessionStoreError.ConcurrentModification)
  )
  assertEquals(store.load(), Right(Some(session1)))
  assertEquals(
    store.replace(Some(session1.refreshToken), session2),
    Right(())
  )
```

- [ ] **Step 2: Write failing three-step auth tests**

Queue and capture:

1. `POST /PatientPortalMobileAPI/api/token`
2. `GET /PatientPortal/Account/LogInToApp?app=search&client=3&lang=pl`
3. `GET /PatientPortal/NewPortal/Page/Reservation`

Assert the password body is form-encoded; `Authorization` on step 2 contains
the raw access token with no `Bearer`; `X-Requested-With` is `pl.luxmed.pp`;
cookies from each response merge by name; and the returned/stored JWT comes
from `Authorization-Token`.

Add a missing-JWT test which returns
`Left(LuxmedError.ProtocolViolation("Authorization-Token missing"))`; never
fall back to the OAuth access token.

- [ ] **Step 3: Write failing refresh transaction tests**

With an injected clock, prove:

- 301 seconds remaining: no refresh.
- 300 seconds remaining: exactly one refresh grant.
- Refresh repeats `LogInToApp` and reservation-page bootstrap with the new
  access token.
- The completed session is stored with expected old refresh token before it is
  returned.
- A store failure after bootstrap produces pending persistence; the next call
  retries the store only and sends no HTTP request.
- A bootstrap failure after token rotation retains pending OAuth tokens; the
  next call retries bootstrap and never resends the consumed refresh token.
- Concurrent callers under `AccountGate` produce one refresh transaction.
- A rejected refresh token clears the stale session and performs one password
  authentication only when credentials are available.

- [ ] **Step 4: Run both suites and verify red**

Run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.SessionStoreTest"
sbt "backend/testOnly lmbot.backend.luxmed.LuxmedClientAuthTest"
```

Expected: compilation fails because store/client do not exist.

- [ ] **Step 5: Implement the store and session-state ADT**

Use:

```scala
enum SessionStoreError:
  case Unavailable(message: String)
  case ConcurrentModification

trait SessionStore:
  def load(): Either[SessionStoreError, Option[LuxmedSession]]
  def replace(
      expectedRefreshToken: Option[Secret],
      updatedSession: LuxmedSession
  ): Either[SessionStoreError, Unit]
  def clear(): Either[SessionStoreError, Unit]

private enum ClientSessionState:
  case Unloaded
  case Ready(session: LuxmedSession)
  case PendingBootstrap(
      expectedRefreshToken: Option[Secret],
      oauth: OAuthTokens,
      cookies: CookieJar
  )
  case PendingPersistence(
      expectedRefreshToken: Option[Secret],
      session: LuxmedSession
  )
```

`InMemorySessionStore` uses `AtomicReference[Option[LuxmedSession]]` and a true
compare-and-set loop; do not implement check-then-set with separate reads.

- [ ] **Step 6: Implement authentication and refresh in direct style**

Every public method enters `gate.serialized`; its scoped permit is then
available to `withSession`, the operation, and each transport call. Inside,
use ordinary calls and exhaustive `Either` matches. Do not introduce `F[_]`
or a for-comprehension over an async effect.

`authenticate` stores with `expectedRefreshToken = None`. Refresh stores with
`Some(old.refreshToken)`. Once a refresh grant succeeds, transition to
`PendingBootstrap` before the NewPortal calls. Once bootstrap succeeds,
transition to `PendingPersistence`. Publish `Ready` only after store success.
Construct one `LuxmedClient` per account with immutable
`Credentials(username: String, password: Secret)` so the single permitted
password fallback does not pass credentials through every public method.

- [ ] **Step 7: Verify and commit**

Run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.SessionStoreTest"
sbt "backend/testOnly lmbot.backend.luxmed.LuxmedClientAuthTest"
sbt backend/testFull
sbt scalafmtAll
nix flake check
```

Commit:

```bash
git add backend/src/main/scala/lmbot/backend/luxmed \
  backend/src/test/scala/lmbot/backend/luxmed
git commit -m "feat: manage rotating Luxmed sessions"
```

---

### Task 6: Add dictionaries and full terms search

**Files:**

- Modify: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedTransport.scala`
- Modify: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedClient.scala`
- Create: `backend/src/test/scala/lmbot/backend/luxmed/DictionaryAndTermsTest.scala`

**Interfaces:**

- Produces: `cities`, `serviceVariants`, `facilitiesAndDoctors`, `searchTerms`
  on `LuxmedClient`
- Consumes: `TermsQuery` with city/service/date and optional
  facility/doctor IDs

- [ ] **Step 1: Write failing dictionary tests**

Assert exact verified paths and the `authorization-token: Bearer <jwt>` header:

```text
/PatientPortal/NewPortal/Dictionary/cities
/PatientPortal/NewPortal/Dictionary/serviceVariantsGroups
/PatientPortal/NewPortal/Dictionary/facilitiesAndDoctors
```

For `facilitiesAndDoctors`, assert `cityId` and `serviceVariantId` are present
when defined and absent when `None`.

- [ ] **Step 2: Write the failing full-query search test**

Inject UUID `00000000-0000-0000-0000-000000000123` and assert the captured
query contains exactly:

```text
searchPlace.id=70
searchPlace.type=0
serviceVariantId=4502
languageId=10
searchDateFrom=2026-08-03
searchDateTo=2026-08-10
searchDatePreset=14
processId=00000000-0000-0000-0000-000000000123
serviceVariantSource=0
facilitiesIds=78
doctorsIds=111111
nextSearch=false
searchByMedicalSpecialist=false
delocalized=false
```

Add a second test proving optional facility/doctor parameters are omitted, not
sent as empty strings.

- [ ] **Step 3: Run and verify red**

Run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.DictionaryAndTermsTest"
```

Expected: compilation fails because client operations do not exist.

- [ ] **Step 4: Implement the four operations**

Each operation:

1. enters `AccountGate.serialized`;
2. obtains a fresh session through the Task 5 state machine;
3. sends exactly one authenticated NewPortal request;
4. returns decoded immutable values or the typed error unchanged.

Date query values use `DateTimeFormatter.ISO_LOCAL_DATE`. Do not use the
server's default zone.

- [ ] **Step 5: Verify and commit**

Run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.DictionaryAndTermsTest"
sbt backend/testFull
sbt scalafmtAll
nix flake check
```

Commit:

```bash
git add backend/src/main/scala/lmbot/backend/luxmed \
  backend/src/test/scala/lmbot/backend/luxmed/DictionaryAndTermsTest.scala
git commit -m "feat: search Luxmed appointment terms"
```

---

### Task 7: Add XSRF-protected reservation primitives

**Files:**

- Modify: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedTransport.scala`
- Modify: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedClient.scala`
- Create: `backend/src/test/scala/lmbot/backend/luxmed/ReservationPrimitivesTest.scala`

**Interfaces:**

- Produces: `getXsrfToken`, `lockTerm`, `confirm`, and `releaseTerm`
- Does not produce: an auto-booking orchestration; that remains Plan 6

- [ ] **Step 1: Write failing XSRF and cookie-merge tests**

`GET /PatientPortal/security/getforgerytoken` must use the current NewPortal
session. Capture its response cookie separately. For each mutation, assert:

- `authorization-token: Bearer JWT_1`
- `xsrf-token: XSRF_1`
- session cookies plus XSRF cookies
- a duplicate cookie name uses the newer XSRF response value

- [ ] **Step 2: Write failing request-byte tests**

Assert exact verified endpoints:

```text
POST /PatientPortal/NewPortal/reservation/lockterm
POST /PatientPortal/NewPortal/reservation/confirm
POST /PatientPortal/NewPortal/reservation/releaseterm?reservationId=222222
```

Pin important request JSON field names with raw body assertions:

```scala
assert(request.body.contains(""""temporaryReservationId":222222"""))
assert(request.body.contains(""""serviceVariantId":555555"""))
assert(!request.body.contains("temporary_reservation_id"))
```

Release sends the explicit empty JSON body expected by the verified client and
retains `reservationId` as a query parameter.

- [ ] **Step 3: Run and verify red**

Run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.ReservationPrimitivesTest"
```

Expected: compilation fails because the primitives do not exist.

- [ ] **Step 4: Implement independent primitives**

Keep lock, confirm, and release as separate methods returning their wire/domain
values. Do not add validation policy, automatic confirm, or automatic release
here: Plan 6 owns `lock → validate → confirm or release`.

- [ ] **Step 5: Verify and commit**

Run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.ReservationPrimitivesTest"
sbt backend/testFull
sbt scalafmtAll
nix flake check
```

Commit:

```bash
git add backend/src/main/scala/lmbot/backend/luxmed \
  backend/src/test/scala/lmbot/backend/luxmed/ReservationPrimitivesTest.scala
git commit -m "feat: add Luxmed reservation primitives"
```

---

### Task 8: Close the error, challenge, retry, and redaction matrix

**Files:**

- Modify: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedError.scala`
- Modify: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedTransport.scala`
- Modify: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedClient.scala`
- Modify: `backend/src/test/scala/lmbot/backend/luxmed/ErrorClassificationTest.scala`
- Modify: `backend/src/test/scala/lmbot/backend/luxmed/LuxmedClientAuthTest.scala`

**Interfaces:**

- Completes: every PRD §5.4/§7 error variant
- Preserves: one retry only for typed session expiry; no blind auth loop

- [ ] **Step 1: Add the missing failing matrix tests**

Add table-driven cases for:

| Wire condition | Expected value |
|---|---|
| 302 body or Location contains `/LogOn` | `SessionExpired` |
| 302 body or Location contains `/UniversalLink` | `SessionExpired` |
| 409 Polish/English bad credentials | `AuthFailed` |
| `{"errors":[...]}` old-app message | `VersionRejected` |
| `{"errors":{field:[...]}}` with no recognized special message | `ApiRejected` |
| `{"error":{code,message}}` with no recognized special message | `ApiRejected` |
| any status with "session has expired" | `SessionExpired` |
| 429 | `RateLimited` |
| 500–599 | `Transient` |
| success with challenge-shaped body | `UnexpectedAuthResponse` |
| success missing expected JWT | `ProtocolViolation` |
| success malformed JSON | `DecodeFailed` |

For diagnostics, seed bodies/headers with `PASSWORD_1`, `ACCESS_1`,
`REFRESH_1`, `JWT_1`, cookie secrets, `person@example.com`, and
`501 234 567`; assert none appear in the returned error's `toString`.

- [ ] **Step 2: Add retry-policy tests**

Prove:

- An authenticated operation receiving `SessionExpired` refreshes/re-auths and
  retries the original operation exactly once.
- A second `SessionExpired` is returned; no third request is made.
- `RateLimited`, `VersionRejected`, `UnexpectedAuthResponse`, and
  `PersistenceFailed` are never converted to `AuthFailed`.
- No error path performs more than one password grant.

- [ ] **Step 3: Run and verify red**

Run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.ErrorClassificationTest"
sbt "backend/testOnly lmbot.backend.luxmed.LuxmedClientAuthTest"
```

Expected: at least one new matrix/retry assertion fails.

- [ ] **Step 4: Complete exhaustive classification and retry**

Implement the minimum changes needed for the matrix. Pattern-match every closed
ADT exhaustively; do not add wildcard cases to silence compiler warnings.
Recognized Luxmed error envelopes without a more specific match become
`ApiRejected` with a redacted summary. Unknown payloads are
`DecodeFailed`/`ProtocolViolation`, not guessed auth failures.

- [ ] **Step 5: Run the async-vocabulary review gate**

Run:

```bash
grep -rn --include='*.scala' 'scala\.concurrent' \
  shared/src/main backend/src/main frontend/src/main | grep -v '/bridge/'
```

Expected: no output.

- [ ] **Step 6: Verify and commit**

Run:

```bash
sbt backend/testFull
sbt testFull
sbt scalafmtAll
nix flake check
```

Expected: both `testFull` commands report non-zero totals and pass.

Commit:

```bash
git add backend/src/main/scala/lmbot/backend/luxmed \
  backend/src/test/scala/lmbot/backend/luxmed
git commit -m "test: harden Luxmed client failures"
```

---

### Task 9: Harden the mock with a guided real-API exploration

**Files:**

- Modify: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedTransport.scala`
- Modify: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedClient.scala`
- Create: `backend/src/main/scala/lmbot/backend/luxmed/JsonShape.scala`
- Create: `backend/src/test/scala/lmbot/backend/luxmed/MockConformanceTest.scala`
- Create: `backend/src/test/scala/lmbot/backend/luxmed/GuidedContractExplorer.scala`
- Modify: literal fixtures under `backend/src/test/resources/luxmed/` only when
  the exploration supplies evidence that the existing shape is stale

**Interfaces:**

- Produces: a value-free `WireFingerprint` for each transport exchange
- Produces: a manual `Test / runMain` explorer; it is not an MUnit suite and
  never runs in CI
- Preserves: the same expectations run offline against `MockLuxmedServer`

- [ ] **Step 1: Write the offline mock-conformance test**

Define fingerprints without response values:

```scala
final case class WireFingerprint(
    step: String,
    status: Int,
    headerNames: Set[String],
    cookieNames: Set[String],
    decodedBody: String,
    bodyShape: Option[JsonShape]
)
```

`decodedBody` is a closed label such as `OAuthTokens`, `Cities`,
`ServiceVariants`, `TermsResponse`, `XsrfToken`, `LockResponse`, or
`EmptySuccess`; it is produced only after the production codec succeeds.
`JsonShape` is a value-free ADT:

```scala
enum JsonShape:
  case Obj(fields: SortedMap[String, JsonShape])
  case Arr(elementShapes: Set[JsonShape])
  case Str, Num, Bool, Null
```

Implement a small one-pass JSON shape scanner which validates JSON syntax,
retains object keys, and discards every scalar value as it parses. It must not
construct a generic JSON AST containing real values. Shape compatibility
requires all fixture fields and scalar kinds to exist in the live shape;
additional live object fields are reported but allowed, and `Null` is
compatible only where the fixture already demonstrates a nullable field.

`MockConformanceTest` executes the required flow against the mock:

1. password grant;
2. `LogInToApp`;
3. reservation page;
4. refresh grant;
5. refreshed `LogInToApp`;
6. refreshed reservation page;
7. cities;
8. service variants;
9. terms search;
10. XSRF.

Assert the exact ten-step fingerprint list, including required
`Authorization-Token`/session cookie names. This test remains fully offline.

- [ ] **Step 2: Run and verify red**

Run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.MockConformanceTest"
```

Expected: compilation fails because fingerprints and the conformance observer
do not exist.

- [ ] **Step 3: Add value-free transport observation**

Add a package-private observer with a no-op production default:

```scala
private[luxmed] trait WireObserver:
  def observed(fingerprint: WireFingerprint): Unit
```

The transport reports status, lower-cased header names, cookie names, the
successfully decoded body label, and `JsonShape` computed before the raw body
leaves the send method. It must never pass header values, cookie values,
response bodies, credentials, or tokens to the observer.

Add a package-private `refreshNowForConformance()` entrypoint which uses the
normal refresh state machine but disables password fallback. The explorer uses
a client policy with zero session-expiry retries; an unexpected response ends
the exploration rather than issuing another request.

- [ ] **Step 4: Implement the guided explorer with hard safety limits**

`GuidedContractExplorer` is a `main` program under test sources, invoked only
with `backend/Test/runMain`. It must:

- bind `val console = System.console()` and refuse to run when it is `null`;
- print the complete request budget before doing anything;
- read username normally and the password through
  `console.readPassword("Password: ")` with no echo, wrap it in `Secret`, and
  zero the returned character array immediately afterward;
- increment `spike/contract-exploration-count` before the password grant and
  refuse to run when it is already `2`;
- construct `AccountGate` with real Gears sleep and **five-second minimum
  spacing**;
- use one password grant and forbid all automatic retry/password fallback;
- ask for an explicit `CONTINUE` before login, refresh, dictionaries/search,
  and XSRF phases;
- stop immediately on 401, 409, 429, `VersionRejected`,
  `UnexpectedAuthResponse`, or any fingerprint difference;
- show dictionary values only on the local console to guide city/service
  selection, but never write them to disk;
- show only field/header/cookie-name differences when comparing behavior;
- keep all sessions, terms, and response bodies in memory only; never persist
  them or include them in the final summary.

The required flow sends exactly ten requests. The program prints a final
value-free summary:

```text
CONFORMANCE OK: 10/10 requests, one password grant, one refresh,
no retries, no mutation, all mock fingerprints matched
```

- [ ] **Step 5: Add the optional lock/release phase**

After the required flow succeeds, offer one optional advanced phase. It:

1. displays the selected term locally;
2. requires the exact phrase `LOCK AND RELEASE`;
3. obtains no new XSRF token—the required phase's token is reused;
4. sends one `lockterm`;
5. records the response fingerprint;
6. calls `releaseterm` from `finally` using the returned temporary reservation
   id;
7. prints a prominent warning if release does not succeed.

There is no delay beyond the gate's five-second minimum between lock and
release. Never call `confirm`, `changeterm`, appointment cancellation, or a
second lock. The maximum exploration budget including this phase is 12
requests.

- [ ] **Step 6: Run the offline test and all normal verification**

Run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.MockConformanceTest"
sbt backend/testFull
sbt scalafmtAll
nix flake check
```

Expected: all pass without network access or credentials.

- [ ] **Step 7: Run the guided exploration once**

From an interactive terminal inside the flake devShell:

```bash
sbt "backend/Test/runMain lmbot.backend.luxmed.GuidedContractExplorer"
```

Use an owned Luxmed account. Do not pipe input, run under CI, or bypass the
attempt ledger. If the explorer stops because of 401/409/429, a challenge,
version rejection, or a fingerprint mismatch, stop the task and report the
evidence; do not rerun immediately.

The required ten-step phase must succeed. The optional lock/release phase may
be declined without blocking Plan 3 and is recorded separately.

- [ ] **Step 8: Reconcile evidence without copying real values**

If a fingerprint differs, amend the PRD first if behavior changed, then update
only the affected literal mock fixture and offline expectation. Never paste a
real response, token, cookie, username, selected service, doctor, facility, or
slot into the repository.

Re-run:

```bash
sbt "backend/testOnly lmbot.backend.luxmed.MockConformanceTest"
sbt backend/testFull
nix flake check
```

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/scala/lmbot/backend/luxmed \
  backend/src/test/scala/lmbot/backend/luxmed \
  backend/src/test/resources/luxmed \
  docs/superpowers/specs/2026-07-27-lm-bot-prd-design.md
git commit -m "test: verify mock against guided Luxmed exploration"
```

Do not add anything under `spike/`; it is ignored local safety state.

---

### Task 10: Verify the complete Plan 3 deliverable and record completion

**Files:**

- Modify: `docs/superpowers/plans/2026-07-27-lm-bot-roadmap.md`
- Create: `docs/superpowers/reports/2026-07-28-plan-03-complete.md`

**Interfaces:**

- Produces: reviewable evidence that Plan 3 meets the PRD
- Changes roadmap status only after every required check passes

- [ ] **Step 1: Run complete verification in the flake devShell**

Run:

```bash
sbt scalafmtCheckAll
sbt backend/testFull
sbt testFull
sbt frontend/fastLinkJS
nix flake check
grep -rn --include='*.scala' 'scala\.concurrent' \
  shared/src/main backend/src/main frontend/src/main | grep -v '/bridge/'
git diff --check
```

Expected:

- formatting passes;
- both `testFull` runs report non-zero totals and no failures;
- the Wasm frontend still links;
- flake checks pass;
- the async-vocabulary grep prints nothing;
- `git diff --check` prints nothing.

- [ ] **Step 2: Audit scope and security**

Run:

```bash
git diff --name-only main...HEAD
rg -n 'password|accessToken|refreshToken|jwtToken|Authorization-Token' \
  backend/src/main/scala/lmbot/backend/luxmed
rg -n '/api/(terms|lockterm|confirm|releaseterm)' \
  backend/src/main backend/src/test
```

Review every secret-bearing occurrence for `Secret` wrapping/redaction.
Expected for the mobile-path search: no implemented unverified endpoint.

- [ ] **Step 3: Write the completion report**

Record:

- files and architecture delivered;
- exact upstream commit used for shapes;
- exact test counts from fresh `testFull` output;
- commands and outcomes from Step 1;
- confirmation that CI has no live Luxmed call;
- the guided exploration's value-free success summary and request count;
- whether optional lock/release was run, declined, or blocked by no suitable
  slot;
- confirmation that no database/API/UI entered scope;
- any skipped check as an explicit blocker rather than completion.

- [ ] **Step 4: Mark Plan 3 complete only if no check was skipped**

Change the roadmap row to:

```markdown
| 3 | Luxmed API client & mock server | A client that authenticates and searches slots against a mock | ✅ **complete** — [plan](2026-07-28-lm-bot-03-luxmed-client.md), [report](../reports/2026-07-28-plan-03-complete.md) |
```

If any required suite or the required ten-step guided exploration could not
run, leave the roadmap at **next** and state the blocker in the report. The
optional lock/release phase is not a required check and declining it is not a
skipped suite.

- [ ] **Step 5: Commit the verified completion evidence**

Run:

```bash
nix flake check
git add docs/superpowers/plans/2026-07-27-lm-bot-roadmap.md \
  docs/superpowers/reports/2026-07-28-plan-03-complete.md
git commit -m "docs: record Plan 3 completion"
```

Do not merge until CI is green, all review threads are resolved, bot comments
are addressed, and no changes-requested review remains active.
