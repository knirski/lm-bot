# Plan 3 Completion Report: Luxmed API Client & Mock Server

**Date:** 2026-07-29
**Plan:** [`2026-07-28-lm-bot-03-luxmed-client.md`](../plans/2026-07-28-lm-bot-03-luxmed-client.md)
**Spec:** [`2026-07-27-lm-bot-prd-design.md`](../specs/2026-07-27-lm-bot-prd-design.md)
**Upstream shapes:** [dyrkin/luxmed-bot](https://github.com/dyrkin/luxmed-bot) (not lmassist)

**changed-files:** Backend client, transport, models, codecs, mocks, fixtures, tests, docs
**verification-run:** `sbt backend/testFull` (152/152), `sbt frontend/fastLinkJS`, `sbt scalafmtCheckAll`, `nix flake check`, async-vocabulary gate, `git diff --check`
**skipped-checks:** none
**branch:** `feat/luxmed-client-plan3-complete`
**pr:** https://github.com/knirski/lm-bot/pull/20
**blocker:** none

## Files and Architecture Delivered

### Core client (`backend/src/main/scala/lmbot/backend/luxmed/`)

| File | Purpose |
|---|---|
| `LuxmedClient.scala` | Authenticate, session lifecycle, dictionaries, terms search, XSRF token, lock/confirm/release. Direct-style with Gears + `Either`. |
| `LuxmedTransport.scala` | Raw HTTP to old API (mobile) and new Portal API; cookie extraction; wire classification; `WireObserver` for conformance fingerprinting. |
| `LuxmedError.scala` | Typed error taxonomy: `AuthFailed`, `SessionExpired`, `RateLimited`, `Transient`, `ApiRejected`, `VersionRejected`, `UnexpectedAuthResponse`, `ProtocolViolation`, `DecodeFailed`, `PersistenceFailed`, `NetworkFailure` |
| `LuxmedConfig.scala` | Configuration for old/new API URIs, app version, device UUID, device model, API level. |
| `AccountGate.scala` | Per-account mutex + rate limiter via Gears `Semaphore`. |
| `SessionStore.scala` | Trait + in-memory impl with compare-and-set on `refreshToken`. CAS contract for safe concurrent session rotation. |
| `CookieJar.scala` | Cookie jar with merge-by-name semantics. |
| `JsonShape.scala` | Structural JSON fingerprint for conformance verification. |

### Wire models (`backend/src/main/scala/lmbot/backend/luxmed/model/`)

| File | Purpose |
|---|---|
| `AuthModels.scala` | `OAuthTokens`, `Credentials`, `LuxmedSession` (all secrets wrapped in `Secret`) |
| `DictionaryModels.scala` | `City`, `ServiceVariant` (recursive tree), `Doctor`, `Facility`, `FacilitiesAndDoctors` |
| `TermsModels.scala` | `TermsQuery`, `Term`, `TermsForDay`, `TermsForService`, `TermsResponse`, `LuxmedDateTime`, `PreparationItem`, `AdditionalData` |
| `ReservationModels.scala` | `XsrfToken`, `LockTermRequest`, `LockTermResponse`, `LockTermResponseValue`, `Valuation`, `RelatedVisit`, `ConfirmRequest`, `ConfirmResponse`, `ConfirmValue`, `ReleaseTermRequest` |
| `OpaqueIds.scala` | `DoctorId`, `FacilityId`, `ScheduleId`, `ServiceVariantId`, `ReservationId`, `CityId` — compile-time distinct `Long` wrappers with jsoniter codecs |
| `TokenType.scala` | `TokenType.Bearer` |
| `WireCodecs.scala` | All jsoniter codecs: snake_case for OAuth, camelCase for NewPortal, custom recursive `ServiceVariant` codec, dual-format `LuxmedDateTime` codec |
| `LuxmedEndpoint.scala` | Typed endpoint descriptors with path segments |

### Tests (`backend/src/test/scala/lmbot/backend/luxmed/`)

| Test | Scope |
|---|---|
| `WireCodecTest.scala` | 15 tests: all fixture round-trips, datetime normalization, `Secret` redaction, ServiceVariant recursion, cookie jar semantics |
| `ErrorClassificationTest.scala` | 20 tests: error classification via sttp stub (17) + real-HTTP redirect/cookie/connection tests (3) |
| `AccountGateTest.scala` | 6 tests: serialization, rate limiting, timeout |
| `LuxmedClientAuthTest.scala` | 19 tests: full auth flow, refresh, retry on session expiry, retry on auth failure, re-authentication after expiry, concurrency |
| `SessionStoreTest.scala` | 7 tests: CAS contract, concurrent update rejection |
| `DictionaryAndTermsTest.scala` | 7 tests: cities, serviceVariants, facilitiesAndDoctors, terms search with params (via sttp stub) |
| `ReservationPrimitivesTest.scala` | 4 tests: XSRF token, lock, confirm, release (via sttp stub) |
| `MockConformanceTest.scala` | 1 test: full 10-step flow against real loopback server with fingerprint verification |
| `StubLuxmedBackendTest.scala` | 4 tests: production factory, `withBackend` injection, stub FIFO/capture behaviour |
| `WireContractTest.scala` | 6 tests: real wire invariants (redirect count, `Set-Cookie`, empty body, form body, connection failure, cookie `=` preservation) |

### Support (`backend/src/test/scala/lmbot/backend/luxmed/support/`)

| File | Purpose |
|---|---|
| `RealHttpLuxmedServer.scala` | Renamed/reduced JDK `HttpServer` loopback for wire-boundary tests; no Luxmed-specific methods |
| `MockLuxmedServer.scala` | (Deprecated alias for `RealHttpLuxmedServer`) |
| `StubLuxmedBackend.scala` | FIFO-queue sttp stub backend for client-policy tests; enqueue responses, capture requests |
| `LuxmedResponseScripts.scala` | Backend-independent realistic auth response components (literal strings, never production codecs) |
| `FakeTime.scala` | Controllable clock for testing time-sensitive operations |
| `GearsTest.scala` | `runAsync` helper for testing Gears `Async` blocks in munit |

### Fixtures (`backend/src/test/resources/luxmed/`)

13 literal JSON fixtures captured from Luxmed's real API responses.

## Verification Results

All checks pass in the flake devShell:

| Check | Outcome |
|---|---|
| `sbt backend/testFull` | **Passed:** 152 tests, 0 failed, 0 errors |
| `sbt scalafmtCheckAll` | Passed |
| `sbt frontend/fastLinkJS` | Passed |
| `nix flake check` | All checks passed |
| Async-vocabulary gate | Clean — no `scala.concurrent` outside `/bridge/` |
| `git diff --check` | Clean |

### Test count breakdown (Luxmed client suites only — full `sbt backend/testFull` totals 152)

```
AccountGateTest:           6 passed
ErrorClassificationTest:  20 passed
LuxmedClientAuthTest:     19 passed
SessionStoreTest:          7 passed
WireCodecTest:            15 passed
DictionaryAndTermsTest:    7 passed
ReservationPrimitivesTest: 4 passed
MockConformanceTest:       1 passed
StubLuxmedBackendTest:     4 passed
WireContractTest:          6 passed

Additional suites (non-Luxmed):
AdminBootstrapTest:     4 passed
ConfigTest:            13 passed
TokensTest:             4 passed
AuthServiceTest:       14 passed
HttpApiTest:            6 passed
SessionRepoTest:       14 passed
StaticRoutesTest:       5 passed
EmbeddedPgTest:         1 passed
PostgresSuite:         12 passed
BridgeTest:             3 passed
UpdateTest:             4 passed
---
Total:                152 passed
```

## Security Review

- All secrets (`Secret`): `accessToken`, `refreshToken`, `jwtToken`, `password`, cookies — wrapped in opaque `Secret` type with redacted `toString`.
- Redaction in `LuxmedTransport`: bearer tokens, `access_token`, `refresh_token`, `password`, `jwt`, `Authorization-Token`, `Authorization`, emails, phone numbers all masked in diagnostics.
- Secrets never logged: `Secret` wrapping prevents accidental interpolation leaks.
- No unverified mobile-API endpoints (`/api/terms`, `/api/lockterm`, etc.) used — only verified `NewPortal/*` paths.
- Session rotation uses CAS pattern: `SessionStore.replace` atomically updates with `expectedRefreshToken` guard.

## Scope Confirmation

- **No database**: `SessionStore` is a trait with an in-memory impl; PostgreSQL implementation deferred to Plan 4.
- **No HTTP API**: No Tapir endpoints were added. The client is a library used internally.
- **No UI**: No Laminar views.
- **No CI live Luxmed call**: Mock server with fixtures; no external connection.
- **Guided exploration** (`GuidedContractExplorer`): The required live phase
  confirmed auth, refresh, bootstrap, dictionaries, terms search, and XSRF.
  The value-bearing terminal transcript was intentionally not retained in the
  repository.
- **Lock/release**: The optional live lock succeeded, but release decoding
  failed on Luxmed's empty response body. `releaseTerm` was fixed afterward
  and verified against the deterministic mock; that fix has not been
  reconfirmed against the live API.

## Decisions Made During Implementation

1. **`ServiceVariant` recursive codec**: jsoniter's `JsonCodecMaker.make` does not support recursive types. Custom hand-written codec with `JsonValueCodec[ServiceVariant]` and `listServiceVariantCodec`. Children arrays parsed via the canonical jsoniter `isNextToken('[')` → `rollbackToken()` → while loop with `isNextToken(',')` pattern.

2. **Dual datetime format**: Luxmed returns both offset-aware (`2026-08-03T09:00:00+02:00`) and local (`2026-08-03T09:00:00`) datetimes. The `LuxmedDateTime` codec tries `ISO_OFFSET_DATE_TIME` first, falls back to `ISO_LOCAL_DATE_TIME`, normalising both to `Europe/Warsaw`.

3. **XSRF flow**: Separate `getXsrfToken()` call returns token + extra cookies. Lock/confirm/release take both the token and extra cookies separately from the session.

4. **Response classification**: `LuxmedTransport.classify` maps HTTP status codes and body patterns to `LuxmedError` variants, extracting cookies and `Authorization-Token` from successful responses.

5. **Opaque IDs**: `CityId`, `DoctorId`, `FacilityId`, `ServiceVariantId`, `ScheduleId`, `ReservationId` — all compile-time distinct `Long` wrappers. Used throughout domain signatures, converted to `Long` only at the wire boundary via their jsoniter codecs. This was verified: `WireCodecs.scala` uses the opaque types, the codecs transparently serialize/deserialize to `Long`.

## Upstream Commit

Dyrkin/luxmed-bot commit
`c970447b319c9b06a06497ce972972903902491f` supplied the reference shapes.

## Plan 3 Status

✅ **Complete** — all 10 tasks delivered, all 152 tests pass.
