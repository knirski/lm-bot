# Plan 4 — Luxmed Accounts and Monitor CRUD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an authenticated user link Luxmed accounts and create, browse,
edit, pause, resume, and delete stored monitors through the browser.

**Architecture:** Keep browser contracts and application domain values in
`shared`, while Luxmed wire DTOs remain backend-internal. Backend services own
authorization and state transitions; focused Magnum repositories own
persistence; a versioned AES-256-GCM boundary protects credentials, device
identities, and sessions. Tapir routes translate only, and the frontend remains
Elm-on-Gears: pure `update`, effectful API calls, rendering-only Laminar views.

**Tech Stack:** Scala 3.8.4, JVM 25, Scala.js 1.22.0 Wasm + JSPI, Gears 0.3.1,
Tapir 1.13.29, sttp 3.11.0, jsoniter-scala 2.39.1, Laminar 17.2.1, Magnum
1.3.1, Flyway 11.8.2, PostgreSQL, MUnit 1.3.4.

## Global Constraints

- Work in the flake devShell; Node 26+ and Temurin 25 are required.
- Follow strict red-green-refactor. Run `testFull`, never bare `test`.
- The approved PRD is authoritative. Amend and commit it before code if this
  plan exposes a design error; do not work around the error in implementation.
- Gears is the only async vocabulary. `scala.concurrent.Future` and JavaScript
  `Promise` stay inside `frontend/.../bridge/`.
- Expected failures are values. Do not use exceptions for control flow.
- Plain constructor wiring only: no DI framework and no reflection.
- Browser-facing endpoints use `setCookieOpt`, never `setCookie`.
- Pin actual JSON bytes for every new shared wire type; round-trip-only tests
  are insufficient.
- `AccountId` is an lm-bot application identifier in `shared`, never a Luxmed
  wire identifier in `lmbot.backend.luxmed.model`.
- Luxmed passwords, device UUIDs, and complete persisted sessions use
  AES-256-GCM under `LMBOT_MASTER_KEY`; secrets never enter API values or logs.
- Every resource operation is scoped to the authenticated owner in a service,
  including dictionary proxy calls and account deletion.
- Linking performs at most one password grant. Excessive login attempts can
  lock a Luxmed account for about a day.
- Luxmed-facing dates and times have `Europe/Warsaw` semantics.
- Monitor interval defaults to 10 minutes and has a hard 5-minute floor.
- Plan 4 stores monitors but does not run them. Monitor loops, events,
  notifications, and last-check summaries belong to Plan 5; booking belongs to
  Plan 6.
- Format Scala with `sbt scalafmtAll` and finish with `sbt testFull`,
  `frontend/fastLinkJS`, `nix flake check`, the async-vocabulary gate, and
  `git diff --check`.

---

## Planned File Structure

```text
shared/src/main/scala/lmbot/shared/
├── api/
│   ├── AccountEndpoints.scala
│   ├── Codecs.scala
│   ├── DictionaryEndpoints.scala
│   └── MonitorEndpoints.scala
└── domain/
    ├── Account.scala
    ├── Dictionary.scala
    └── Monitor.scala

backend/src/main/scala/lmbot/backend/
├── account/
│   ├── AccountClientFactory.scala
│   ├── AccountService.scala
│   └── AccountStatusReason.scala
├── config/
│   ├── Config.scala
│   └── MasterKey.scala
├── crypto/
│   ├── AesGcm.scala
│   └── EncryptedEnvelope.scala
├── db/
│   ├── AccountRepo.scala
│   ├── MonitorRepo.scala
│   ├── PostgresSessionStore.scala
│   └── Rows.scala
├── http/
│   ├── AccountRoutes.scala
│   ├── DictionaryRoutes.scala
│   └── MonitorRoutes.scala
└── monitor/
    └── MonitorService.scala

frontend/src/main/scala/lmbot/frontend/
├── AppState.scala
├── Msg.scala
├── Update.scala
├── api/ApiClient.scala
└── view/
    ├── AccountView.scala
    ├── AppView.scala
    └── MonitorView.scala
```

Tests mirror those responsibilities. Do not create one generic “CRUD”
repository, route, or view: account linking, encrypted session replacement,
dictionary mapping, monitor policy, and browser state transitions have
different invariants and should remain independently understandable.

### Task 1: Define and pin the shared account, dictionary, and monitor contract

**Files:**

- Create: `shared/src/main/scala/lmbot/shared/domain/Account.scala`
- Create: `shared/src/main/scala/lmbot/shared/domain/Dictionary.scala`
- Create: `shared/src/main/scala/lmbot/shared/domain/Monitor.scala`
- Create: `shared/src/main/scala/lmbot/shared/api/AccountEndpoints.scala`
- Create: `shared/src/main/scala/lmbot/shared/api/DictionaryEndpoints.scala`
- Create: `shared/src/main/scala/lmbot/shared/api/MonitorEndpoints.scala`
- Modify: `shared/src/main/scala/lmbot/shared/api/Codecs.scala`
- Modify: `shared/src/test/scala/lmbot/shared/CodecRoundTripTest.scala`

**Interfaces:**

- Produces opaque `AccountId` and `MonitorId` values with JSON number codecs and
  Tapir schemas.
- Produces `AccountView`, `LinkAccountRequest`, `AccountStatus`,
  `DictionaryCity`, `DictionaryService`, `DictionaryFacility`,
  `DictionaryDoctor`, `MonitorDraft`, `MonitorView`, and `MonitorState`.
- Produces authenticated endpoints under `/api/accounts`,
  `/api/accounts/{id}/dictionaries`, and `/api/monitors`.
- All secured endpoints consume `Option[String]` from the `lmbot_session`
  cookie and return the existing `ApiError`.

- [ ] **Step 1: Write literal wire-format tests**

Add tests that decode and re-encode complete literal values, including this
monitor request:

```scala
val monitorJson =
  """{"accountId":7,"name":"Dermatologist","city":{"id":3,"name":"Warsaw"},"service":{"id":42,"name":"Dermatology"},"facilities":[{"id":9,"name":"Puławska"}],"doctors":[],"dateFrom":"2026-08-01","dateTo":"2026-08-31","timeFrom":"08:00","timeTo":"16:00","daysOfWeek":["Monday","Wednesday"],"autoBook":false,"intervalMinutes":10}"""

val decoded = readFromString[MonitorDraft](monitorJson)
assertEquals(writeToString(decoded), monitorJson)
```

Pin the account status as a string, not a discriminated object:

```scala
assertEquals(
  writeToString(AccountStatus.AuthFailed),
  "\"auth_failed\""
)
```

Also pin `MonitorState` strings (`active`, `paused`, `completed`, `failed`),
opaque IDs as JSON numbers, optional status reasons, and empty optional
facility/doctor lists.

- [ ] **Step 2: Run the shared JVM and JS suites and verify red**

```bash
sbt "sharedJVM/testOnly lmbot.shared.CodecRoundTripTest"
sbt "sharedJS/testOnly lmbot.shared.CodecRoundTripTest"
```

Expected: compilation fails because the new types and codecs do not exist.

- [ ] **Step 3: Add total shared domain values**

Define IDs as opaque `Long` values with explicit constructors and extensions:

```scala
opaque type AccountId = Long
object AccountId:
  def apply(value: Long): AccountId = value
  extension (id: AccountId) def value: Long = id

opaque type MonitorId = Long
object MonitorId:
  def apply(value: Long): MonitorId = value
  extension (id: MonitorId) def value: Long = id
```

Use closed enums and immutable products:

```scala
enum AccountStatus:
  case Active, AuthFailed, Disabled

final case class AccountView(
    id: AccountId,
    label: String,
    username: String,
    status: AccountStatus,
    statusReason: Option[String],
    lastSuccessfulLogin: Option[Instant]
)

final case class LinkAccountRequest(
    label: String,
    username: String,
    password: String
)

enum MonitorState:
  case Active, Paused, Completed, Failed

final case class NamedId(id: Long, name: String)

final case class MonitorDraft(
    accountId: AccountId,
    name: String,
    city: NamedId,
    service: NamedId,
    facilities: List[NamedId],
    doctors: List[NamedId],
    dateFrom: LocalDate,
    dateTo: LocalDate,
    timeFrom: LocalTime,
    timeTo: LocalTime,
    daysOfWeek: List[DayOfWeek],
    autoBook: Boolean,
    intervalMinutes: Int = 10
)
```

`MonitorView` adds `id`, `state`, `createdAt`, and `updatedAt`. Do not add a
fabricated last-check value before Plan 5 has events.

- [ ] **Step 4: Add codecs, schemas, and endpoint descriptions**

Extend `Codecs.config` with field-name mappings for snake_case enum values, or
provide explicit codecs, so the pinned lowercase wire forms are exact. Add
schemas for opaque IDs using `Schema.schemaForLong.map`.

Give each endpoint object its own `errorOut`, following `AuthEndpoints`, until
a later refactor can extract a shared package-private endpoint base without
changing behavior. Required operations:

```text
POST   /api/accounts
GET    /api/accounts
DELETE /api/accounts/{accountId}
GET    /api/accounts/{accountId}/dictionaries/cities
GET    /api/accounts/{accountId}/dictionaries/services
GET    /api/accounts/{accountId}/dictionaries/facilities-doctors
POST   /api/monitors
GET    /api/monitors
GET    /api/monitors/{monitorId}
PUT    /api/monitors/{monitorId}
POST   /api/monitors/{monitorId}/pause
POST   /api/monitors/{monitorId}/resume
DELETE /api/monitors/{monitorId}
```

The facilities/doctors endpoint takes `cityId` and `serviceId` query inputs.
None of these endpoints returns credentials, a device UUID, a session, cookies,
or Luxmed wire models.

- [ ] **Step 5: Verify and commit**

```bash
sbt "sharedJVM/testOnly lmbot.shared.CodecRoundTripTest"
sbt "sharedJS/testOnly lmbot.shared.CodecRoundTripTest"
sbt sharedJVM/testFull
sbt sharedJS/testFull
sbt scalafmtAll
git diff --check
git add shared
git commit -m "feat: define account and monitor API contract"
```

Expected: both focused suites pass with non-zero totals and the literal bytes
match exactly.

### Task 2: Validate the master key and implement the AES-GCM boundary

**Files:**

- Create: `backend/src/main/scala/lmbot/backend/config/MasterKey.scala`
- Create: `backend/src/main/scala/lmbot/backend/crypto/EncryptedEnvelope.scala`
- Create: `backend/src/main/scala/lmbot/backend/crypto/AesGcm.scala`
- Create: `backend/src/test/scala/lmbot/backend/AesGcmTest.scala`
- Modify: `backend/src/main/scala/lmbot/backend/config/Config.scala`
- Modify: `backend/src/test/scala/lmbot/backend/ConfigTest.scala`
- Modify: `build.sbt`
- Modify: `README.md`

**Interfaces:**

- Produces `MasterKey.fromBase64(String): Either[String, MasterKey]`.
- Produces `EncryptedEnvelope.render: String` and
  `EncryptedEnvelope.parse(String): Either[CryptoError, EncryptedEnvelope]`.
- Produces `AesGcm.encrypt(plaintext, context): EncryptedEnvelope` and
  `AesGcm.decrypt(envelope, context): Either[CryptoError, Secret]`.
- `context` is `EncryptionContext(ownerId, accountId, purpose)`, where purpose
  is the closed enum `Password`, `DeviceId`, or `Session`.

- [ ] **Step 1: Write failing configuration and crypto tests**

Cover:

```scala
test("master key must decode to exactly 32 bytes")
test("encrypt/decrypt round-trips with the same context")
test("two encryptions of one plaintext have different nonces and ciphertext")
test("changing owner, account, or purpose rejects authentication")
test("tampered ciphertext rejects authentication")
test("unsupported envelope version is a typed error")
test("envelope and errors never render plaintext or the master key")
```

Use a deterministic `SecureRandom` test double only to pin the textual envelope
format:

```text
v1.<base64url-12-byte-nonce>.<base64url-ciphertext-and-tag>
```

Production tests must also use real `SecureRandom` to prove nonce variation.

- [ ] **Step 2: Run focused tests and verify red**

```bash
sbt "backend/testOnly lmbot.backend.ConfigTest lmbot.backend.AesGcmTest"
```

Expected: compilation fails because `MasterKey`, `AesGcm`, and the new config
field do not exist.

- [ ] **Step 3: Parse `LMBOT_MASTER_KEY` at the configuration boundary**

Add `masterKey: MasterKey` to `Config`. Require standard Base64 input that
decodes to exactly 32 bytes. Error messages may name the variable and required
length but must never include its value.

Add a non-production development default to `Compile / envVars` in `build.sbt`
using a clearly documented fixed Base64 test key. Production config remains
env-only and has no runtime fallback. Document generation:

```bash
openssl rand -base64 32
```

- [ ] **Step 4: Implement the versioned envelope and AES/GCM/NoPadding**

Use a fresh 12-byte nonce per encryption, a 128-bit GCM tag, UTF-8 plaintext,
and UTF-8 AAD:

```scala
enum EncryptionPurpose(val wireName: String):
  case Password extends EncryptionPurpose("password")
  case DeviceId extends EncryptionPurpose("device-id")
  case Session extends EncryptionPurpose("session")

final case class EncryptionContext(
    ownerId: Long,
    accountId: AccountId,
    purpose: EncryptionPurpose
):
  def aad: Array[Byte] =
    s"lm-bot:v1:$ownerId:${accountId.value}:${purpose.wireName}"
      .getBytes(StandardCharsets.UTF_8)
```

Catch only cryptographic authentication/format failures at this boundary and
return a closed `CryptoError`. Do not expose exception messages.

- [ ] **Step 5: Verify and commit**

```bash
sbt "backend/testOnly lmbot.backend.ConfigTest lmbot.backend.AesGcmTest"
sbt backend/testFull
sbt scalafmtAll
git diff --check
git add build.sbt README.md backend/src/main/scala/lmbot/backend/config \
  backend/src/main/scala/lmbot/backend/crypto \
  backend/src/test/scala/lmbot/backend/AesGcmTest.scala \
  backend/src/test/scala/lmbot/backend/ConfigTest.scala
git commit -m "feat: encrypt Luxmed account secrets"
```

### Task 3: Add account and monitor schema plus focused repositories

**Files:**

- Create: `backend/src/main/resources/db/migration/V2__accounts_monitors.sql`
- Create: `backend/src/main/scala/lmbot/backend/db/AccountRepo.scala`
- Create: `backend/src/main/scala/lmbot/backend/db/MonitorRepo.scala`
- Create: `backend/src/test/scala/lmbot/backend/AccountRepoTest.scala`
- Create: `backend/src/test/scala/lmbot/backend/MonitorRepoTest.scala`
- Modify: `backend/src/main/scala/lmbot/backend/db/Rows.scala`
- Modify: `backend/src/test/scala/lmbot/backend/support/PostgresSuite.scala`

**Interfaces:**

- `AccountRepo.reserveId(): AccountId` allocates an ID before encryption so the
  ID can be part of AAD.
- `AccountRepo.insert`, `findOwned`, `listOwned`, `deleteOwned`, and
  `updateSessionCas` are owner-scoped.
- `MonitorRepo.insert`, `findOwned`, `listOwned`, `updateOwned`,
  `transitionOwned`, and `deleteOwned` are owner-scoped through the account
  join.
- Repository methods return rows/counts only; services translate absence and
  policy into `ApiError`.

- [ ] **Step 1: Write failing migration and repository tests**

Cover unique account labels per owner, duplicate Luxmed usernames allowed under
different owners, account-delete cascade, complete monitor round-trip,
facility/doctor array round-trip, owner isolation for every mutation, and
allowed state constraints.

Add account and monitor tables to the truncate list in `PostgresSuite`:

```scala
sql"""truncate table monitors, luxmed_accounts, sessions, users
      restart identity cascade""".update.run()
```

- [ ] **Step 2: Run focused tests and verify red**

```bash
sbt "backend/testOnly lmbot.backend.AccountRepoTest lmbot.backend.MonitorRepoTest"
```

Expected: tests fail because migration V2 and repositories do not exist.

- [ ] **Step 3: Add normalized tables and constraints**

The migration must include:

```sql
create sequence luxmed_account_id_seq;

create table luxmed_accounts (
    id                    bigint primary key,
    owner_user_id         bigint not null references users(id) on delete cascade,
    label                 text not null,
    luxmed_username       text not null,
    encrypted_password    text not null,
    encrypted_device_uuid text not null,
    encrypted_session     text,
    status                text not null
                          check (status in ('active','auth_failed','disabled')),
    status_reason         text,
    last_successful_login timestamptz,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    unique (owner_user_id, label)
);
```

Add `monitors` with explicit city/service IDs and names, `bigint[]` and
`text[]` pairs for facilities/doctors, `date`, `time`, a `smallint` days mask,
`interval_minutes check (interval_minutes >= 5)`, state check, timestamps, and
`on delete cascade` from `luxmed_account_id`. Add owner-listing indexes.
Parallel ID/name arrays must have equal cardinality via check constraints.

- [ ] **Step 4: Implement typed rows and focused repositories**

Use Magnum queries with explicit owner predicates. `transitionOwned` must use
an expected-state set in SQL, so a completed or failed monitor cannot be
resumed by racing requests:

```sql
update monitors m
set state = ?, updated_at = now()
from luxmed_accounts a
where m.id = ?
  and m.luxmed_account_id = a.id
  and a.owner_user_id = ?
  and m.state = any(?)
returning m.*
```

Do not decrypt anything in repositories except inside the session CAS method
introduced in Task 4.

- [ ] **Step 5: Verify and commit**

```bash
sbt "backend/testOnly lmbot.backend.AccountRepoTest lmbot.backend.MonitorRepoTest"
sbt backend/testFull
sbt scalafmtAll
git diff --check
git add backend/src/main/resources/db/migration \
  backend/src/main/scala/lmbot/backend/db \
  backend/src/test/scala/lmbot/backend/AccountRepoTest.scala \
  backend/src/test/scala/lmbot/backend/MonitorRepoTest.scala \
  backend/src/test/scala/lmbot/backend/support/PostgresSuite.scala
git commit -m "feat: persist Luxmed accounts and monitors"
```

### Task 4: Implement encrypted PostgreSQL session CAS and restart recovery

**Files:**

- Create: `backend/src/main/scala/lmbot/backend/db/PostgresSessionStore.scala`
- Create: `backend/src/test/scala/lmbot/backend/PostgresSessionStoreTest.scala`
- Create: `backend/src/main/scala/lmbot/backend/luxmed/SessionCodec.scala`
- Create: `backend/src/test/scala/lmbot/backend/luxmed/SessionCodecTest.scala`
- Modify: `backend/src/main/scala/lmbot/backend/db/AccountRepo.scala`

**Interfaces:**

- Produces
  `PostgresSessionStore(xa, ownerId, accountId, crypto): SessionStore`.
- Produces a private/backend-only versioned `PersistedSessionV1` codec for all
  six session components: access token, token type, refresh token, expiry, JWT,
  and cookie jar.
- `replace` compares the decrypted current refresh token and updates the newly
  encrypted session within one database transaction.

- [ ] **Step 1: Pin the plaintext session payload format**

Use a literal JSON assertion independent of encryption:

```json
{"version":1,"accessToken":"access","tokenType":"bearer","refreshToken":"refresh","expiresAt":"2026-08-01T10:00:00Z","jwtToken":"jwt","cookies":{"WAF":"abc","SESSION":"def"}}
```

Assert decode rejects unknown versions and token types as values. Assert no
test failure renders token contents.

- [ ] **Step 2: Add failing store tests**

Cover:

- `load` returns `None` for no session;
- initial `replace(None, session)` succeeds only when empty;
- `replace(Some(old), rotated)` succeeds and persists all fields;
- stale expected refresh token returns `ConcurrentModification` and writes
  nothing;
- two store instances racing on one account yield exactly one winner;
- `clear` removes the session;
- a newly constructed store loads the rotated token after the original store
  is discarded;
- ciphertext does not contain any session secret.

- [ ] **Step 3: Run focused tests and verify red**

```bash
sbt "backend/testOnly lmbot.backend.luxmed.SessionCodecTest lmbot.backend.PostgresSessionStoreTest"
```

- [ ] **Step 4: Implement transactional compare-and-set**

Inside one `transact(xa)` block:

1. select `encrypted_session ... for update` for the owned account;
2. decrypt and decode the current session, if present;
3. compare its refresh token to `expectedRefreshToken` using
   `MessageDigest.isEqual` over UTF-8 bytes;
4. encrypt the new complete session with the account/session AAD;
5. update exactly the owner/account row.

Map missing/decryption/SQL failures to a safe `SessionStoreError.Unavailable`;
map only expectation mismatch to `ConcurrentModification`. Never retry a
failed CAS and never overwrite on mismatch.

- [ ] **Step 5: Add client restart/rotation integration coverage**

Construct client A with a database store, authenticate against
`StubLuxmedBackend`, discard it, then construct client B with a new store and
the same account. Set time to the 300-second proactive refresh boundary, queue
only refresh/bootstrap responses, and assert:

- no password grant was sent;
- the old refresh token was sent once;
- the rotated token is stored;
- client C loads the rotated token after another restart.

- [ ] **Step 6: Verify and commit**

```bash
sbt "backend/testOnly lmbot.backend.luxmed.SessionCodecTest lmbot.backend.PostgresSessionStoreTest"
sbt backend/testFull
sbt scalafmtAll
git diff --check
git add backend/src/main/scala/lmbot/backend/db/PostgresSessionStore.scala \
  backend/src/main/scala/lmbot/backend/luxmed/SessionCodec.scala \
  backend/src/test/scala/lmbot/backend/PostgresSessionStoreTest.scala \
  backend/src/test/scala/lmbot/backend/luxmed/SessionCodecTest.scala \
  backend/src/main/scala/lmbot/backend/db/AccountRepo.scala
git commit -m "feat: persist rotating Luxmed sessions"
```

### Task 5: Build the account application service and client factory

**Files:**

- Create: `backend/src/main/scala/lmbot/backend/account/AccountStatusReason.scala`
- Create: `backend/src/main/scala/lmbot/backend/account/AccountClientFactory.scala`
- Create: `backend/src/main/scala/lmbot/backend/account/AccountService.scala`
- Create: `backend/src/test/scala/lmbot/backend/AccountServiceTest.scala`
- Modify: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedConfig.scala`

**Interfaces:**

- `AccountClientFactory.forLink(...)` builds a client over an empty
  `InMemorySessionStore`; production endpoints and response policy remain the
  existing `LuxmedTransport.production`.
- `AccountClientFactory.forStored(ownerId, accountId)` decrypts credentials and
  device identity, uses `PostgresSessionStore`, and returns a client scoped to
  the stored account.
- `AccountService.link(ownerId, LinkAccountRequest)(using Async)` returns
  `Either[ApiError, AccountView]`.
- `AccountService.list(ownerId)` and `delete(ownerId, accountId)` are
  owner-scoped.

- [ ] **Step 1: Write failing service tests**

Cover empty/oversized label and username validation, duplicate label conflict,
successful single login, credential rejection with no row, challenge with no
row, version rejection with no row, network/persistence failure with no row,
one and only one password grant, encrypted-at-rest columns, list isolation, and
owner-scoped cascade deletion.

Use the existing sttp stub response scripts; do not call live Luxmed.

- [ ] **Step 2: Run the focused suite and verify red**

```bash
sbt "backend/testOnly lmbot.backend.AccountServiceTest"
```

- [ ] **Step 3: Implement explicit safe error mapping**

Use a closed mapping, never `error.toString`:

```scala
private def linkError(error: LuxmedError): ApiError = error match
  case LuxmedError.AuthFailed =>
    ApiError.Validation("Luxmed rejected these credentials.")
  case _: LuxmedError.UnexpectedAuthResponse =>
    ApiError.Conflict("Luxmed requested an unexpected authentication step.")
  case LuxmedError.RateLimited =>
    ApiError.Conflict("Luxmed may have temporarily locked this account.")
  case _: LuxmedError.VersionRejected =>
    ApiError.Conflict("The configured Luxmed app version is no longer accepted.")
  case _: LuxmedError.NetworkFailure | _: LuxmedError.Transient =>
    ApiError.Unexpected("Luxmed is temporarily unavailable.")
  case _ =>
    ApiError.Unexpected("The Luxmed account could not be linked.")
```

Do not claim that an arbitrary auth failure on a previously working account is
a wrong password. The persistent `auth_failed` transitions needed by the Plan 5
engine remain defined here as service methods with reason values, even though
Plan 4 does not yet call them from a scheduler.

- [ ] **Step 4: Make linking persistence atomic**

The service flow is:

1. validate request;
2. reserve `AccountId`;
3. generate one stable UUID;
4. authenticate once using an in-memory session store;
5. begin one DB transaction;
6. encrypt password, UUID, and returned complete session with the reserved ID;
7. insert the active account and all encrypted fields;
8. commit, then return a secret-free view.

If step 7 conflicts on label, return `ApiError.Conflict`. A failed transaction
must leave no account. Never repeat step 4 after any failure.

- [ ] **Step 5: Verify and commit**

```bash
sbt "backend/testOnly lmbot.backend.AccountServiceTest"
sbt backend/testFull
sbt scalafmtAll
git diff --check
git add backend/src/main/scala/lmbot/backend/account \
  backend/src/main/scala/lmbot/backend/luxmed/LuxmedConfig.scala \
  backend/src/test/scala/lmbot/backend/AccountServiceTest.scala
git commit -m "feat: link and manage Luxmed accounts"
```

### Task 6: Expose account and dictionary endpoints with service authorization

**Files:**

- Create: `backend/src/main/scala/lmbot/backend/http/AccountRoutes.scala`
- Create: `backend/src/main/scala/lmbot/backend/http/DictionaryRoutes.scala`
- Create: `backend/src/main/scala/lmbot/backend/account/DictionaryService.scala`
- Create: `backend/src/test/scala/lmbot/backend/AccountHttpApiTest.scala`
- Create: `backend/src/test/scala/lmbot/backend/DictionaryServiceTest.scala`
- Modify: `backend/src/main/scala/lmbot/backend/account/AccountService.scala`
- Modify: `backend/src/main/scala/lmbot/backend/Main.scala`

**Interfaces:**

- Routes authenticate with `AuthService.authenticate`, then pass only
  `AuthedUser.id` plus decoded input to services.
- Dictionary service methods return shared DTOs and never expose Luxmed wire
  DTOs.
- Every proxy call first resolves an account owned by the authenticated user.

- [ ] **Step 1: Add failing route and dictionary mapping tests**

Route tests cover missing/invalid session, success, literal response JSON,
cross-owner 404, duplicate-label 409, invalid request 422, and deletion
cascade. Verify `password`, cookies, UUID, JWT, access token, and refresh token
never appear in any response.

Dictionary tests map recursive `ServiceVariant` values to a flattened,
selectable list while retaining parent/variant display names, and map
facilities/doctors to shared `NamedId` values.

- [ ] **Step 2: Run focused suites and verify red**

```bash
sbt "backend/testOnly lmbot.backend.AccountHttpApiTest lmbot.backend.DictionaryServiceTest"
```

- [ ] **Step 3: Implement thin Tapir routes**

Follow `AuthRoutes`:

```scala
AccountEndpoints.list
  .serverSecurityLogicPure(auth.authenticate)
  .serverLogicPure(user => (_: Unit) => accounts.list(user.id))
```

For Gears-using service methods, enter one Gears async boundary in the route
adapter on its virtual thread. Do not place branching policy in the endpoint
description or route.

- [ ] **Step 4: Wire production components by hand**

In `Main`, construct crypto, repositories, account client factory, account
service, and routes explicitly. Append the new endpoints to `Server.start`.
Keep creation order readable and avoid a service locator.

- [ ] **Step 5: Verify and commit**

```bash
sbt "backend/testOnly lmbot.backend.AccountHttpApiTest lmbot.backend.DictionaryServiceTest"
sbt backend/testFull
sbt scalafmtAll
git diff --check
git add backend/src/main/scala/lmbot/backend/http \
  backend/src/main/scala/lmbot/backend/account/AccountService.scala \
  backend/src/main/scala/lmbot/backend/Main.scala \
  backend/src/test/scala/lmbot/backend/AccountHttpApiTest.scala \
  backend/src/test/scala/lmbot/backend/DictionaryServiceTest.scala
git commit -m "feat: expose Luxmed account and dictionary APIs"
```

### Task 7: Implement monitor validation, ownership, and state transitions

**Files:**

- Create: `backend/src/main/scala/lmbot/backend/monitor/MonitorService.scala`
- Create: `backend/src/test/scala/lmbot/backend/MonitorServiceTest.scala`
- Modify: `backend/src/main/scala/lmbot/backend/db/MonitorRepo.scala`

**Interfaces:**

- `create(ownerId, draft): Either[ApiError, MonitorView]`
- `list(ownerId): List[MonitorView]`
- `get(ownerId, monitorId): Either[ApiError, MonitorView]`
- `update(ownerId, monitorId, draft): Either[ApiError, MonitorView]`
- `pause`, `resume`, and `delete` are owner-scoped.
- New monitors start `Active`; editing never changes state.

- [ ] **Step 1: Write a validation and transition matrix**

Tests must cover:

```text
valid default interval                   -> 10
interval 5                               -> accepted
interval 4                               -> validation error
dateFrom after dateTo                    -> validation error
timeFrom equal/after timeTo              -> validation error
empty daysOfWeek                         -> validation error
cross-owner account on create/update     -> not found
pause active                             -> paused
pause paused                             -> idempotently paused
resume paused                            -> active
resume active                            -> idempotently active
resume completed/failed                  -> conflict
edit/delete cross-owner monitor          -> not found
```

Also verify facility/doctor IDs and names keep their input ordering and equal
cardinality through persistence.

- [ ] **Step 2: Run the focused suite and verify red**

```bash
sbt "backend/testOnly lmbot.backend.MonitorServiceTest"
```

- [ ] **Step 3: Implement pure validation before effects**

Create a private total validator that accumulates deterministic messages:

```scala
private def validate(draft: MonitorDraft): Either[ApiError, ValidMonitorDraft]
```

Convert `daysOfWeek` to a seven-bit mask with Monday as bit 0. The conversion
and inverse get direct unit coverage. Treat local dates/times as Warsaw
calendar values; do not apply the server default zone or convert them through
`Instant`.

- [ ] **Step 4: Keep state policy in the service**

Only the service chooses allowed transition sets. Repositories enforce the
expected current state atomically and report whether a row changed. Return
`NotFound` for ownership/missing resource and `Conflict` for a disallowed
transition on an owned monitor.

- [ ] **Step 5: Verify and commit**

```bash
sbt "backend/testOnly lmbot.backend.MonitorServiceTest"
sbt backend/testFull
sbt scalafmtAll
git diff --check
git add backend/src/main/scala/lmbot/backend/monitor \
  backend/src/main/scala/lmbot/backend/db/MonitorRepo.scala \
  backend/src/test/scala/lmbot/backend/MonitorServiceTest.scala
git commit -m "feat: manage stored monitors"
```

### Task 8: Expose monitor CRUD through Tapir

**Files:**

- Create: `backend/src/main/scala/lmbot/backend/http/MonitorRoutes.scala`
- Create: `backend/src/test/scala/lmbot/backend/MonitorHttpApiTest.scala`
- Modify: `backend/src/main/scala/lmbot/backend/Main.scala`

**Interfaces:**

- Implements every endpoint from `MonitorEndpoints`.
- Route logic authenticates and delegates only; validation and ownership remain
  in `MonitorService`.

- [ ] **Step 1: Write failing HTTP contract tests**

Cover literal create/list/get/update/pause/resume/delete request and response
bytes; 401 without a session; 404 for another owner; 422 for interval 4 and
invalid dates; 409 for resuming completed/failed; and absence after delete.
Assert an authenticated admin cannot access another user's monitors.

- [ ] **Step 2: Run the focused suite and verify red**

```bash
sbt "backend/testOnly lmbot.backend.MonitorHttpApiTest"
```

- [ ] **Step 3: Implement routes and composition-root wiring**

Follow the account route pattern and append endpoints in `Main`. Do not
duplicate validation in Tapir validators: shared endpoint descriptions may
constrain primitive decoding, while domain validation stays in the service and
returns the documented `ApiError`.

- [ ] **Step 4: Verify and commit**

```bash
sbt "backend/testOnly lmbot.backend.MonitorHttpApiTest"
sbt backend/testFull
sbt scalafmtAll
git diff --check
git add backend/src/main/scala/lmbot/backend/http/MonitorRoutes.scala \
  backend/src/main/scala/lmbot/backend/Main.scala \
  backend/src/test/scala/lmbot/backend/MonitorHttpApiTest.scala
git commit -m "feat: expose monitor CRUD API"
```

### Task 9: Add frontend account management

**Files:**

- Create: `frontend/src/main/scala/lmbot/frontend/view/AccountView.scala`
- Modify: `frontend/src/main/scala/lmbot/frontend/AppState.scala`
- Modify: `frontend/src/main/scala/lmbot/frontend/Msg.scala`
- Modify: `frontend/src/main/scala/lmbot/frontend/Update.scala`
- Modify: `frontend/src/main/scala/lmbot/frontend/api/ApiClient.scala`
- Modify: `frontend/src/main/scala/lmbot/frontend/view/AppView.scala`
- Modify: `frontend/src/test/scala/lmbot/frontend/UpdateTest.scala`

**Interfaces:**

- Adds account-list state and a link form with label, username, password,
  submitting, and error.
- Adds derived Tapir client methods for account list/link/delete.
- Views only dispatch messages; `update` owns validation and effects.

- [ ] **Step 1: Write failing pure update tests**

Cover navigating to accounts, loading success/failure, incomplete-form
validation, double-submit suppression, password clearing on both success and
failure, new account insertion, delete confirmation state, delete success, and
status-reason rendering state. Assert `AppState` never retains a successful
Luxmed password.

- [ ] **Step 2: Run the focused frontend suite and verify red**

```bash
sbt "frontend/testOnly lmbot.frontend.UpdateTest"
```

- [ ] **Step 3: Extend state, messages, effects, and API client**

Add a screen enum case and closed load state rather than booleans:

```scala
enum LoadState[+A]:
  case NotAsked, Loading
  case Loaded(value: A)
  case Failed(message: String)
```

API methods use Tapir-derived clients and `Bridge.awaitEither`; do not name a
`Future` or `Promise`. Account errors use user-safe server messages. Do not
reinterpret a lockout/challenge conflict as “wrong password.”

- [ ] **Step 4: Implement rendering-only account views**

Render account label, username, status, reason, and last successful login.
Delete requires an explicit confirmation state naming the account and warning
that monitors will be deleted. Event handlers dispatch one message each.

- [ ] **Step 5: Verify and commit**

```bash
sbt "frontend/testOnly lmbot.frontend.UpdateTest"
sbt frontend/testFull
sbt frontend/fastLinkJS
sbt scalafmtAll
git diff --check
git add frontend
git commit -m "feat: add Luxmed account management UI"
```

### Task 10: Add the monitor form, list, and edit UI

> **Superseded by human direction (2026-08-01):** this task originally
> specified a 6-step guided wizard. The human who owns this project asked
> instead for a single-page form (every field visible at once, one submit
> button), which is what shipped and is described below. The per-task SDD
> report (implementation and review record) lives in the gitignored
> `.superpowers/sdd/` workspace and is not committed; see
> `docs/superpowers/reports/2026-07-30-plan-04-complete.md` for the
> committed record of what shipped.

**Files:**

- Create: `frontend/src/main/scala/lmbot/frontend/view/MonitorView.scala`
- Modify: `frontend/src/main/scala/lmbot/frontend/AppState.scala`
- Modify: `frontend/src/main/scala/lmbot/frontend/Msg.scala`
- Modify: `frontend/src/main/scala/lmbot/frontend/Update.scala`
- Modify: `frontend/src/main/scala/lmbot/frontend/api/ApiClient.scala`
- Modify: `frontend/src/main/scala/lmbot/frontend/view/AppView.scala`
- Modify: `frontend/src/test/scala/lmbot/frontend/UpdateTest.scala`

**Interfaces:**

- One single-page form shows account, city, service, providers, and schedule
  fields at once, gated only by which dictionaries have loaded; there is no
  step state and one submit button.
- Account selection triggers city *and* service loading together (the
  `DictionaryEndpoints.services` endpoint is account-scoped, not
  city-scoped); city and service together (in either order) trigger
  facility/doctor loading for the selected owned account.
- Changing the city clears only the facility/doctor selection and keeps the
  chosen service, since the service list does not depend on city and the
  previously chosen value is still valid. Changing the service still clears
  facility/doctor selections.
- Create and edit share one `MonitorForm`; edit starts from persisted
  denormalized IDs and names.

- [ ] **Step 1: Write failing single-page/update tests**

Cover:

- no linked accounts shows a link-account action;
- dictionary calls occur only after prerequisites exist (account → cities +
  services together; city + service, in either order, → facilities/doctors);
- changing city clears facility/doctor selections but keeps the service;
- changing service clears facility/doctor selections;
- optional facility and doctor selections may remain empty;
- date, time, days, and interval validation blocks submission;
- interval initializes to 10 and rejects 4;
- create success appears in the list;
- edit preserves state while replacing criteria;
- pause/resume updates state;
- delete confirmation and success remove the monitor;
- stale responses for a previously selected account/city/service are ignored.

- [ ] **Step 2: Run the focused suite and verify red**

```bash
sbt "frontend/testOnly lmbot.frontend.UpdateTest"
```

- [ ] **Step 3: Implement pure wizard transitions and effects** (superseded —
  see the note above; the shipped code has no wizard steps, but the
  staleness-guarding approach described below is unchanged)

Represent each dictionary request with a request key containing account and
prerequisite IDs. Response messages carry the same key; `update` applies a
response only when it matches current selection. This prevents a slow response
from repopulating choices for an obsolete city.

Keep local validation aligned with server validation for immediate feedback,
but still display server `ApiError.Validation` because the backend is
authoritative.

- [ ] **Step 4: Implement accessible rendering-only views**

Use real `label` elements, fieldsets/legends for days and provider selections,
an error summary with `role="alert"`, disabled/busy states, and explicit
previous/next controls. The review step shows denormalized names and Warsaw
date/time semantics before submit. List rows expose edit, pause/resume, and
delete; do not show a fake last-check timestamp or event log.

- [ ] **Step 5: Verify and commit**

```bash
sbt "frontend/testOnly lmbot.frontend.UpdateTest"
sbt frontend/testFull
sbt frontend/fastLinkJS
sbt scalafmtAll
git diff --check
git add frontend
git commit -m "feat: add monitor wizard and management UI"
```

This was the actual first commit (`bf2a4ed`); it was superseded on top by
`860d0f0`, "refactor: consolidate monitor wizard into a single-page form" —
see the note above Step 1.

### Task 11: Exercise the complete browser flow and close Plan 4

**Files:**

- Create: `backend/src/test/scala/lmbot/backend/Plan4AcceptanceApp.scala`
- Create: `backend/src/test/scala/lmbot/backend/Plan4AcceptanceConfig.scala`
- Create: `docs/superpowers/reports/2026-07-30-plan-04-complete.md`
- Modify: `docs/superpowers/plans/2026-07-27-lm-bot-roadmap.md`
- Modify: `README.md`

**Interfaces:**

- Produces a recorded, secret-free acceptance result for link → create → edit →
  pause → resume → delete.
- `Plan4AcceptanceApp` is a test-scope main that starts the ordinary
  composition graph against a deterministic Luxmed stub; it is unavailable from
  the production artifact.
- Marks Plan 4 complete only after all automated gates and a real-browser flow
  pass.

- [ ] **Step 1: Add a deterministic browser-test account seam**

Implement `Plan4AcceptanceApp` under `backend/src/test`: use the real embedded
database, built frontend, routes, services, repositories, and crypto. Substitute
only the owned Luxmed HTTP boundary with a deterministic stub, loading
`LuxmedResponseScripts` and the committed dictionary fixtures. Put fixed
non-secret acceptance usernames, stub responses, and random-port discovery in
`Plan4AcceptanceConfig`. Do not add a runtime “mock Luxmed” configuration or
ship fixture credentials in `backend/src/main`.

> **Corrected during implementation (2026-08-01):** this step originally named
> `LuxmedTransport.withBackend` with `StubLuxmedBackend`. Neither is usable
> here. `withBackend` is `private[luxmed]` and `AccountClientFactory`'s
> constructor is private, so injecting a backend into the app graph would mean
> widening production visibility; pointing `LuxmedConfig` at a loopback base URI
> needs no production change and is what `AccountHttpApiTest` and
> `DictionaryServiceTest` already do. And `StubLuxmedBackend` answers from a
> FIFO queue, while a browser decides how many Luxmed calls happen and in what
> order — the monitor form asks for cities and services concurrently — so a
> queue would answer a city request with a service list. The harness stub
> therefore routes by request path.

- [ ] **Step 2: Run the app in the pinned devShell and drive a real browser**

Use the `agent-browser` skill. Verify:

1. sign in;
2. link one Luxmed account and see `active`;
3. reload the page and see the persisted account;
4. create a monitor by filling in the single-page form;
5. reload and see its criteria;
6. edit its time window and interval;
7. pause and resume it;
8. attempt cross-owner API access and receive 404;
9. delete the account only after confirmation and observe monitor cascade;
10. inspect browser console and network failures.

Capture screenshots of the account page, monitor form, and monitor list. Keep
all secrets and live Luxmed payloads out of screenshots and reports.

- [ ] **Step 3: Re-run the restart/refresh acceptance**

With one persisted test account/session, restart the backend, advance the fake
clock to the proactive refresh boundary, and assert refresh/bootstrap occurs
without a password grant and stores the rotated token. This is a release gate,
not optional coverage.

- [ ] **Step 4: Run every project verification gate**

```bash
sbt scalafmtAll
sbt scalafmtCheckAll
sbt scalafmtSbtCheck
sbt testFull
sbt frontend/fastLinkJS
nix flake check
grep -rn --include='*.scala' 'scala\.concurrent' \
  shared/src/main backend/src/main frontend/src/main | grep -v '/bridge/'
git diff --check
```

Expected:

- `testFull` reports a non-zero total with no failures, exclusions, or skipped
  required suites;
- frontend linking succeeds with Wasm and JSPI;
- Nix checks pass;
- async-vocabulary grep prints nothing;
- `git diff --check` prints nothing.

- [ ] **Step 5: Update roadmap and completion report**

Link this plan from roadmap row 4 and mark it complete only after Step 4 and the
browser run pass. The report must use:

```text
changed-files:
verification-run:
skipped-checks:
branch:
pr:
blocker:
```

Record exact test totals and browser scenarios. Do not claim Plan 5 monitor
execution, event history, notifications, last-check summaries, or Plan 6
booking.

- [ ] **Step 6: Review the final branch and commit**

```bash
git status --short
git diff origin/main...HEAD --stat
git diff origin/main...HEAD --check
git add README.md docs/superpowers/plans/2026-07-27-lm-bot-roadmap.md \
  docs/superpowers/reports/2026-07-30-plan-04-complete.md
git commit -m "docs: complete Luxmed accounts and monitor CRUD plan"
```

Request code review against the full branch diff. Push a `feat/` implementation
branch and create a conventional-commit PR. Merge only with green, non-stale CI,
all review threads resolved, bot comments addressed, and no active
“changes requested” review.

## Plan 4 Completion Criteria

Plan 4 is complete only when:

- credentials, device identity, and complete sessions are encrypted with
  record/purpose-bound AES-256-GCM envelopes;
- restart → proactive refresh → rotated-token persistence works without a
  password grant;
- linking creates no partial account and uses at most one password grant;
- every account, dictionary, and monitor operation enforces owner scope in a
  service;
- monitor validation enforces Warsaw semantics, a 10-minute default, and a
  5-minute floor;
- account and monitor CRUD work through the shared Tapir contract and the
  browser;
- account deletion is explicitly confirmed and cascades to monitors;
- a real browser completes the end-to-end flow without console errors;
- all verification gates pass with no required tests skipped or excluded.
