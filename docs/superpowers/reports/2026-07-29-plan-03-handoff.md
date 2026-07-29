# Plan 3 Handoff Report: Luxmed API Client & Mock Server

**Date:** 2026-07-29
**For:** Next AI agent (Plan 4 — Luxmed accounts & monitor CRUD)
**Plan:** [`docs/superpowers/plans/2026-07-28-lm-bot-03-luxmed-client.md`](../plans/2026-07-28-lm-bot-03-luxmed-client.md)
**PR:** `feat/luxmed-client-plan3-complete`

**changed-files:** Backend client, transport, models, codecs, mocks, fixtures, tests, docs
**verification-run:** `sbt backend/testFull` (152/152), `sbt frontend/fastLinkJS`, `sbt scalafmtCheckAll`, `nix flake check`, async-vocabulary gate
**skipped-checks:** none
**branch:** `feat/luxmed-client-plan3-complete`
**pr:** https://github.com/knirski/lm-bot/pull/20
**blocker:** none

---

## What was delivered

A complete Luxmed API client in `backend/src/main/scala/lmbot/backend/luxmed/` that:

- **Authenticates** via OAuth password grant, bootstraps the NewPortal session (LogInToApp → ReservationPage), and manages session lifecycle with proactive refresh at ~300s
- **Reads dictionaries**: cities, service variants (recursive tree), facilities & doctors
- **Searches appointment terms** with full query parameter set
- **Acquires XSRF tokens** and performs reservation primitives: lock, confirm, release
- Handles **error classification** (session expiry, auth failure, rate limiting, version rejection, transient errors, challenges)
- All tested with a **hybrid architecture**: client-policy suites use an injected sttp stub (`StubLuxmedBackend`), wire-boundary invariants use a real loopback server (`RealHttpLuxmedServer`), and auth response scripts (`LuxmedResponseScripts`) are backend-independent

## Key architectural decisions

### Session lifecycle
- Three-step auth: OAuth password grant → `LogInToApp` → `ReservationPage`
- Session stored in `SessionStore` trait with **CAS contract on refreshToken** (critical: refresh tokens rotate on every use)
- Proactive refresh at 300s (expires_in is ~600s)
- On refresh failure: password fallback (rationed)
- **Cookies from OAuth token response are propagated through bootstrap** (the real API sets WAF cookies on the token endpoint that are needed downstream)

### JSON codecs
- **`ServiceVariant` uses a custom manual codec** — jsoniter's `JsonCodecMaker.make` does not support recursive types
- **Canonical jsoniter pattern** for arrays: `in.isNextToken('[')` → `in.isNextToken(']')` → `in.rollbackToken()` → while loop with `in.isNextToken(',')`
- Dual datetime format handled by `LuxmedDateTime` codec (tries ISO_OFFSET_DATE_TIME first, falls back to ISO_LOCAL_DATE_TIME)

### Transport behavior (critical differences from dyrkin/luxmed-bot reference)
1. **Redirects are not followed automatically** — a redirect to LogOn or UniversalLink is `SessionExpired`; other 3xx responses are returned undecoded for bootstrap callers to inspect rather than classified as `ApiRejected`.
2. **Headers and cookies on redirect responses are preserved** — bootstrap can consume values attached directly to the unfollowed response.
3. **JWT extracted from multiple sources** in `bootstrapNewPortal`: cookies → LogInToApp headers → ReservationPage headers.

### Opaque IDs
`CityId`, `DoctorId`, `FacilityId`, `ServiceVariantId`, `ScheduleId`, `ReservationId` — all compile-time distinct `Long` wrappers with jsoniter codecs in companion objects. Used throughout domain signatures, transparently serialized at the wire boundary.

### Release endpoint
Returns 200 with empty body. `releaseTerm` returns `Either[LuxmedError, Unit]` and handles empty responses.

## Files to know for Plan 4

| File | Relevance |
|---|---|
| `LuxmedClient.scala` | Reuse for live account linking and dictionary proxy operations |
| `LuxmedTransport.scala` | May need new HTTP methods for account-specific flows |
| `SessionStore.scala` | Needs PostgreSQL implementation |
| `AccountGate.scala` | Per-account rate limiter — reuse for Plan 4 monitors |
| `OpaqueIds.scala` | Luxmed wire IDs only; do not put lm-bot's `AccountId` here |
| `WireCodecs.scala` | Add codecs for new models |

## Verification state

- **152 tests pass**, 0 failed
- `sbt frontend/fastLinkJS` — passes (frontend still links)
- `nix flake check` — all checks pass
- Async-vocabulary gate — clean (no `scala.concurrent` outside bridge)
- **Guided exploration against live API**: Auth, refresh, cities, serviceVariants, terms search, and XSRF were confirmed working. The optional lock succeeded, but live release decoding failed on its empty response body. `releaseTerm` was fixed afterward and verified against the deterministic mock; the fix has not been reconfirmed against the live API.

## What to watch for

1. **The `terminate` or cancel scopes in Gears error handling** — several places use `try/catch` inside `Async.fromSync` blocks. Plan 4's monitor engine needs proper Gears structured concurrency.
2. **Session persistence** — `SessionStore` is in-memory. Plan 4 needs a PostgreSQL implementation with the CAS contract preserved.
3. **Account-level encryption** — credentials need AES-256-GCM at rest. The key management pattern isn't yet established.
4. **`LuxmedError` taxonomy** — it's closed but may need extension for Plan 4's account statuses.
5. **Cookie `Set-Cookie` decoding** — `TransportResponse.toString` redacts secrets but doesn't decode cookie values. sttp's `unsafeCookies` method can be used if needed.
6. **`GlobalLang=pl` cookie** must be added during bootstrap (done in `bootstrapNewPortal`). Reminder for any new bootstrap-like flows.
