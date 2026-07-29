# lm-bot — Product Requirements Document

**Date:** 2026-07-27
**Status:** Approved design. Plan 1 (foundation) implemented; Plan 2 (API spike) complete. Amended three times on 2026-07-27: after verifying the API against a working client (§5.4); to add a two-factor design; and then — once the spike measured the real API — to **remove most of that two-factor design as unreachable** and replace it with the refresh-token-rotation requirements the spike did find (§3.2, §5.3, §5.4, §5.5, §3.5, §10).
**Predecessors:**
- [dyrkin/luxmed-bot](https://github.com/dyrkin/luxmed-bot) — feature reference **and the authoritative source for Luxmed request/response shapes, endpoint paths, and the auth/XSRF flow**. It is a live, working Scala client.
- [knirski/lmassist](https://github.com/knirski/lmassist) — prototype; source of the narrative API research (`docs/backend-plan/02-luxmed-api-research.md`), the domain dictionary, and the mock-server *approach*.

## 1. Overview

lm-bot is a self-hosted web application for monitoring and booking Luxmed medical appointments. A small circle of users (family scale) log in with internal accounts, link one or more Luxmed patient-portal accounts, and create **monitors** — persistent watches for appointment slots matching their criteria. When a monitor finds a match, lm-bot notifies the user via Telegram and, if the monitor has auto-booking enabled, books the slot automatically.

The Luxmed API is unofficial and reverse-engineered (from the mobile app `pl.luxmed.pp`).

**Sourcing rule (important).** lmassist's *prose* research is sound, but its Rust DTOs and endpoint paths are not: they are snake_case (`schedule_id`, `lock_token`) against paths (`/NewPortal/API/termsIndex`, `/API/reservation/lock`) that contradict lmassist's own research document, because the client was written against a mock server that lmassist itself authored, using hand-invented fixtures. **Do not port shapes from lmassist.** All concrete shapes, paths, headers, and the auth sequence come from dyrkin/luxmed-bot, which talks to the real service. The real API is camelCase (`scheduleId`, `dateTimeFrom`, `temporaryReservationId`), and login is form-encoded with `client_id="Android"` — there is no `account_id` UUID.

### Goals

- Replace the lmassist Rust prototype with a properly engineered full-stack Scala application.
- Full-stack type safety: one shared, cross-compiled definition of the API contract; frontend and backend cannot drift.
- Direct-style concurrency (Gears) end to end — no effect monads.

### Non-goals

- Multi-tenant public SaaS, billing, email infrastructure.
- Scraping beyond what the reverse-engineered mobile-app API offers.
- High availability / horizontal scaling — one process, one database.

## 2. Users & deployment model

- **Self-hosted, family scale.** One admin runs the instance for themself plus family/friends; tens of users at most.
- **Admin-created accounts.** On first start with an empty `users` table, the admin account is created from `ADMIN_USERNAME`/`ADMIN_PASSWORD` env vars; the admin creates and disables further accounts. No self-registration, no email verification, no password reset flow in v1 (admin resets passwords).
- **Roles:** `admin` (user management + ops notifications) and `user`. Admin does **not** see other users' monitors or Luxmed accounts.
- Deployed via docker-compose (backend + Postgres) behind the operator's own HTTPS reverse proxy.

## 3. v1 feature set

### 3.1 User management
- Login with username + password (Argon2id), cookie sessions.
- Admin: create user, disable user, reset password.
- User: change own password, link Telegram, manage own Luxmed accounts and monitors.

### 3.2 Luxmed account linking

- A user links 1..n Luxmed accounts, each with a label (e.g. "Krzysiek", "Mom").
- Credentials are verified by a live login at link time and stored encrypted (AES-256-GCM, server master key from env).
- Account status surfaced in UI: `active` / `auth_failed` / `disabled`. `auth_failed` carries a **reason** shown to the user, so "wrong password" and "Luxmed wants something from you" are never conflated (see below).
- Linking is a **single live login**. There is no multi-step enrollment.

**Two-factor authentication: not enforced on the API this project uses (verified 2026-07-27).**

An earlier revision of this spec designed a full 2FA enrollment flow — stable device enrollment, an `awaiting_2fa` account state, two-step linking, and one-time codes relayed over Telegram. The Plan 2 spike measured the real API and found none of it is reachable ([the analysis report](../reports/2026-07-27-luxmed-api-analysis.md)):

| Observation | Old mobile API (our target) | New web API |
|---|---|---|
| Login challenged? | No | No |
| `mfadevicestatus` in JWT | `"Trusted"` — always | `"Untrusted"`, and login still succeeds |
| Device identity validated? | **No** — any UUID works, including a fresh random one | `PatientPortalDeviceId` cookie, tracked but not gating |
| `HasAccessToMFA` toggle | Present in the JWT, not enforced | Present, not enforced |

That machinery is therefore **cut**. Building an enrollment subsystem for a challenge that never arrives would be speculative complexity in the most security-sensitive part of the app, and it would be untestable — there is no way to exercise a flow the server never initiates.

**What is kept, and why:**

- **A stable device UUID per linked account**, persisted. Not because trust depends on it — it demonstrably does not — but because it is one column, it keeps the `Custom-User-Agent` header plausible, and a value that changed on every request would be the kind of thing risk-scoring notices. Losing it is a non-event; regenerating it is not a bug.
- **An explicit "unexpected auth response" failure path.** If Luxmed ever does challenge — MFA appears to be account-level opt-in, so another user's account may behave differently, and policy can change — the client must classify it distinctly, not as bad credentials. It sets `auth_failed` with a reason naming what happened, pauses that account's monitors, and notifies the owner that manual attention is needed. **If this fires, stop and design the real flow against the recorded payload rather than guessing** — that is what the earlier revision got wrong.

Reporting a challenge as "wrong password" is the failure worth guarding against: it invites the user to retype credentials that were always correct, and repeated login attempts are exactly what triggers the fair-use lockout in §10.

**Caveats on the evidence.** One account, three login attempts, one day. It establishes that MFA is not *mandatory* on the mobile API; it does not establish that no account can have it enabled. Hence the failure path above rather than an assumption of absence.

### 3.3 Monitors
- **Create** via a guided flow driven by live Luxmed dictionaries: city → service/variant → optional facilities → optional doctors; plus date range, time-of-day window, days-of-week, auto-book toggle, check interval (**default 10 min, minimum 5 min enforced in validation** — see the fair-use risk in §10).
- **Browse** all own monitors with state and last-check summary; **edit**, **pause/resume**, **delete**.
- **Detail view** shows the event log: slots found, notifications sent, booking attempts, errors.
- Monitor states: `active`, `paused`, `completed` (auto-booked or date range passed), `failed`.

### 3.4 Auto-booking

When enabled, the first new slot matching all filters is booked as **lock → validate → confirm or release**.

The middle step is forced by the API: price and referral requirements are **not** in the terms-search response. They arrive only in the `lockterm` response (`valuations[].isReferralRequired`, `valuations[].requireReferralForPP`, `valuations[].price`, `askForReferral`). So the sequence is:

1. **Lock** the slot (`reservation/lockterm`) → yields `temporaryReservationId` and `valuations`.
2. **Validate** the lock response. Abort if any valuation shows `price > 0`, `isReferralRequired`, or `requireReferralForPP`, or if `askForReferral` is set, or if `hasErrors`.
3. **Confirm** (`reservation/confirm`) — echoing back the chosen `valuation` object from the lock response — **or, on abort, `reservation/releaseterm?reservationId=<temporaryReservationId>`**.

Releasing on abort is mandatory: a temporary reservation that is neither confirmed nor released keeps holding the slot, so bailing out without releasing would deny the slot to the user (and to everyone else) — the exact opposite of the feature's purpose. Release is also attempted on the error path, best-effort, and the outcome logged either way.

- On success: monitor → `completed`, a `bookings` record is written, user notified with full slot details.
- On abort or failure (slot taken, payment required, referral required): fall back to notify-only; monitor stays active. Services requiring payment or a referral are never confirmed.
- Auto-book never books a different slot in the same check without re-validating filters against it.

### 3.5 Telegram notifications
- `NotificationChannel` trait; v1 ships one implementation: Telegram Bot API via plain sttp POST (no bot framework dependency).
- Linking: settings page shows a one-time deep-link (`t.me/<bot>?start=<code>`); the bot receives `/start <code>` via long polling and the backend stores the chat id.
- `/start <code>` remains the **only** inbound Telegram interaction in v1 (no public webhook). An earlier revision added inbound one-time-code relay for 2FA; that is cut along with the rest of the enrollment design (§3.2).
- Notification types: new slots found, auto-book success/failure, Luxmed account auth failure (once, with monitors paused, **naming the reason** so a challenge or a lockout is not reported as a bad password), monitor completed/expired. Version-rejection errors from Luxmed notify the **admin** (ops event).
- Per-monitor dedup: a given slot is notified at most once (keyed by slot identity within a lookback window).
- A user without a linked Telegram chat can still run monitors: events are logged and visible in the UI, and the UI warns that no notifications will be delivered. Auto-booking works regardless.

## 4. Future versions (designed-for, not built)

- Browse existing appointments; cancel/reschedule from the UI.
- Manual booking from search results in the UI.
- Visit history, referrals.
- Additional notification channels (Signal via signal-cli-rest-api container is the first candidate — same `NotificationChannel` trait).
- Mobile clients.

## 5. Technical architecture

### 5.1 Stack

| Layer | Choice |
|---|---|
| Language | Scala 3.8 (JVM 25+ backend; Scala.js **Wasm** backend frontend) |
| Concurrency | Gears (direct style) on both platforms |
| API contract | Tapir endpoint definitions in a shared cross-compiled module |
| HTTP server | tapir-jdkhttp-server (`Identity` interpreter) on a virtual-thread executor |
| HTTP client | sttp (backend: Luxmed + Telegram; frontend: Tapir-derived API client) |
| Frontend UI | Laminar |
| Database | PostgreSQL (embedded via memgres by default, zonky embedded-postgres opt-in), Flyway migrations; Magnum over blocking JDBC on virtual threads |
| JSON | jsoniter-scala (Scala 3-native, cross-compiles to Scala.js) |

**Verified versions** (checked against Maven Central 2026-07-27; all three platforms confirmed published where needed):

| Dependency | Version | Note |
|---|---|---|
| Scala | 3.8.4 | latest stable 3.8.x |
| Scala.js | 1.22.0 | Wasm backend |
| Gears | 0.3.1 | `gears_3` + `gears_sjs1_3` both published |
| Tapir | 1.13.29 | `tapir-jdkhttp-server_3`, `tapir-core_sjs1_3`, `tapir-jsoniter-scala` |
| sttp client3 | 3.11.0 | JVM + Scala.js |
| jsoniter-scala | 2.39.1 | JVM + Scala.js |
| Laminar / Airstream | 17.2.1 | |
| scalajs-dom | 2.8.1 | |
| Magnum | 2.0.0-M3 | milestone only — accepted |
| Flyway | 11.8.2 | |
| PostgreSQL JDBC | 42.7.7 | |
| Memgres | 0.2.4 | in-memory PG-compatible engine (default embedded DB) |
| Zonky embedded-postgres | 2.2.2 | real PostgreSQL binary (opt-in: `EMBEDDED_DB=zonky`) |
| MUnit | 1.3.4 | |
| argon2-jvm | 2.12 | |
| logback-classic | 1.6.0 | |

**Gears-on-JS caveats (accepted):** requires the Scala.js WebAssembly backend and a JSPI-capable browser (recent Chrome/Firefox; Safari support to be verified during setup). Dev/test tooling must avoid Node 24/25 (V8 stack-overflow bug in nested async contexts) — use Node 26+. Fallback if this bites in practice: keep Laminar and the Elm architecture (§5.6) and run frontend effects on `scala.concurrent.Future` instead of Gears — the change is isolated to the effect runner and the API-client wiring; `update` and views are unaffected.

**HTTP server note:** tapir-jdkhttp-server was chosen over tapir-netty-server-sync specifically to keep Gears as the *only* concurrency library in the process — netty-sync would pull in Ox (a second direct-style runtime). Handlers are synchronous functions executed on virtual threads, inside which Gears is used freely. Trade-off accepted: no websockets/streaming support; if v2 needs live updates (e.g. monitor status push), the interpreter choice gets revisited then. `com.sun.net.httpserver` behind the operator's reverse proxy is adequate at family scale.

### 5.2 Modules

```
lm-bot/
├── shared/    # crossProject JVM+JS: domain types, Tapir endpoints, JSON codecs
├── backend/   # JVM: services, Luxmed client, monitor engine, persistence, HTTP
└── frontend/  # Scala.js Wasm: Laminar app, Tapir-derived client
```

- **shared** is the single source of truth for the API: domain model (`User`, `LuxmedAccount`, `Monitor`, `BookingSlot`, …), every REST endpoint as a Tapir endpoint (path, auth, request/response/error types), JSON codecs.
- **backend** layers (plain classes, constructor injection, no DI framework):
  - HTTP: jdkhttp interpreter of shared endpoints; session-cookie auth interceptor.
  - Services: auth/users, Luxmed accounts, monitors, notifications.
  - Luxmed client (§5.4), monitor engine (§5.5).
  - Persistence: repositories via Magnum; Flyway migrations.
  - Serves the built frontend as static assets.
- **frontend** pages: login; dashboard (active monitors + lm-bot bookings); monitor wizard; monitor detail/edit; Luxmed accounts; settings (password, Telegram link); admin (users). Structured as Elm-on-Gears (§5.6).

### 5.3 Domain model & persistence

| Table | Contents |
|---|---|
| `users` | id, username, display name, Argon2id hash, role, telegram chat id (nullable), disabled, timestamps |
| `sessions` | token hash (opaque token in cookie), user id, expiry, created-at; revocation = row delete |
| `luxmed_accounts` | id, owner user id, label, Luxmed username, AES-GCM-encrypted password, status, status reason, last successful login, stable device UUID, AES-GCM-encrypted persisted session (access token, **rotating** refresh token, expiry, JWT, cookie jar) |
| `monitors` | id, luxmed account id, criteria (city/service/facility/doctor ids + denormalized names), date range, time window, days-of-week mask, auto-book flag, state, check interval, timestamps |
| `monitor_events` | append-only per-monitor log: slots found, notification sent, booking attempted/succeeded/failed, error; powers detail view and slot dedup |
| `bookings` | v1-minimal record of auto-booked appointments (reservation id, slot details, monitor id) |

Rules:
- All Luxmed-facing dates and times (slot times, monitor date ranges, time-of-day windows) are interpreted in `Europe/Warsaw`, regardless of server or browser time zone.
- Every resource is reachable only through its owning user; ownership checked in the service layer on every operation.
- Deleting a Luxmed account cascades to its monitors (UI confirms first).
- A Luxmed session is **more than a JWT**: it is `(accessToken, tokenType, refreshToken, expiresAt, jwtToken, cookieJar)`. The token endpoint itself sets no auth cookies — only Imperva WAF ones — but `LogInToApp` and the `NewPortal/*` calls do, so the jar is accumulated across the auth steps and replayed on every subsequent call, merged with XSRF cookies for reservation operations (§5.4).
- **The session is persisted, encrypted** (AES-256-GCM, same master key as credentials), reversing the original in-memory-only rule. The reason is no longer 2FA (§3.2) but something firmer: **the refresh token is single-use and rotates on every refresh.** A process that lost its stored session could only recover by a fresh password grant, and repeated password logins are what trip the fair-use lockout in §10. Persisting also lets restart resume silently, as §5.5 requires.
- **The stored session must be updated atomically on every refresh.** Losing the newly issued refresh token while the old one is already consumed breaks the chain irretrievably and forces a password re-login. This is the single most delicate piece of state in the system.
- The device UUID is persisted for stability, not for trust (§3.2). It is written at link time and read thereafter; regenerating it has no observed consequence.

### 5.4 Luxmed client

- One client per linked account; sttp + Gears `Async`.
- Two base URLs: `oldApi = https://portalpacjenta.luxmed.pl/PatientPortalMobileAPI/api`, `newApi = https://portalpacjenta.luxmed.pl/PatientPortal`.
- `Custom-User-Agent` app version string is **configurable via env var** — Luxmed rejects outdated versions (401 / 409 "old app version") and this must be changeable without redeploy.
- In-memory session cache per account; re-login on 401 or on a detected session expiry.
- Per-account rate limiter and mutex: one in-flight request per Luxmed account, minimum spacing between calls.
- Redirects are **not** followed automatically; a 302 is a signal, not a hop
  (see session-expiry detection below). The transport classifies redirects to
  `/LogOn` or `/UniversalLink` as session expiry. Other 3xx responses are
  returned to the caller as undecoded transport responses because Luxmed uses
  them during bootstrap and may attach cookies or authorization headers to the
  response itself.
- Any response that fails to decode is logged with the raw payload — the early-warning system for upstream API changes. Credentials and tokens are masked in all such output (§6).

**Plan 3 client boundaries.** The client is backend-internal; Luxmed's wire
types do not enter the browser-facing `shared` module.

- `lmbot.backend.luxmed.model` owns immutable wire DTOs and jsoniter codecs
  shared by the production client and the test mock.
- `LuxmedTransport` owns request construction, headers, cookie parsing and
  merging, redirect suppression, response decoding, and conversion of foreign
  failures into `LuxmedError` values.
- `LuxmedClient` owns authentication, session maintenance, per-account
  serialization/rate limiting, and the public dictionary, search, XSRF, lock,
  confirm, and release operations.
- `SessionStore` is scoped to one linked account and exposes `load`, `replace`,
  and `clear`.
  `replace(expectedRefreshToken: Option[Secret], updatedSession)` is an atomic
  compare-and-set: `None` means that no session may exist (initial login), and
  `Some(token)` means the stored refresh token must still match. It must fail
  rather than overwrite a session that no longer has the expected state.
- Plan 3 supplies an in-memory `SessionStore` and proves the replacement
  protocol. Plan 4 supplies its AES-256-GCM-encrypted PostgreSQL
  implementation and restart round-trip coverage. Plan 3 adds no database,
  lm-bot HTTP endpoint, or UI.

**Plan 4 account-domain ownership.** Plan 4 defines lm-bot's `AccountId` in
the account/application domain alongside the persisted Luxmed-account model.
It is not a Luxmed wire identifier and must not be added to
`lmbot.backend.luxmed.model.OpaqueIds`, whose types and codecs represent
identifiers received from or sent to Luxmed.

The implementation is **direct-style functional Scala with Gears**. Suspended
operations have ordinary synchronous-looking signatures with `(using Async)`;
Gears fibers, structured scopes, and `Semaphore` are the only concurrency
vocabulary. Expected failures are values returned as
`Either[LuxmedError, A]`, where `LuxmedError` and internal session states are
closed Scala 3 ADTs handled with exhaustive pattern matching. `Either` is not
an async effect and is not abstracted behind `F[_]`: there is no tagless-final
layer, effect monad, transformer stack, Cats Effect, or application-level
`Future`. Exceptions represent bugs; transport exceptions are caught only at
the boundary and converted to typed values.

**Auth flow (three steps, then XSRF on demand):**

1. `POST {oldApi}/token` — **form-encoded** body `client_id=Android`, `grant_type=password`, `username`, `password`. Returns `access_token`, `expires_in`, `refresh_token`, `token_type`.
2. `GET {newApi}/Account/LogInToApp?app=search&client=3&lang=pl` — header `Authorization: <access_token>` (**no** `Bearer` prefix) plus `X-Requested-With: pl.luxmed.pp`. Sets session cookies, including the JWT.
3. `GET {newApi}/NewPortal/Page/Reservation` — replays the cookies from step 2 and accumulates the remainder.

Authenticated `NewPortal/*` calls then use header `authorization-token: Bearer <jwt>` plus the cookie jar.

**Device identity.** `Custom-User-Agent` has the form `Patient Portal; {appVersion}; {deviceUuid}; Android; {apiLevel}; {deviceModel}`. The `deviceUuid` is the account's persisted value (§5.3) rather than a fresh random one per request — stability is hygiene, not a trust requirement, since the spike showed the UUID is not validated at all. `appVersion` comes from env; **`4.44.0` is the floor**, because the refresh grant rejects `4.42.0` even though the password grant accepts it.

**Session maintenance is the delicate part.** Verified behaviour:

- Access token TTL is ~600 s. Refresh **proactively at ~300 s**; do not wait for a 401.
- `grant_type=refresh_token` (form-encoded, with `client_id=Android`) returns a new access token **and a new refresh token**. The old refresh token is consumed — it is single-use.
- After a successful refresh grant, repeat auth steps 2 and 3 with the new
  access token to obtain a fresh NewPortal JWT and complete cookie jar. The JWT
  is also short-lived; refreshing only the old-API access token would leave
  `NewPortal/*` calls using an expired `authorization-token`.
- Reusing a consumed refresh token returns `401` with `ErrorCode: 1`, "You have been logged out due to inactivity."
- Therefore the stored session must be written **atomically** with each refresh (§5.3). A crash between "old token consumed" and "new token stored" costs a password re-login, and password logins are the rationed operation (§10).
- A refresh that cannot be recovered falls back to a full password grant. If *that* returns anything challenge-shaped, classify it as an unexpected auth response (§3.2) rather than bad credentials.

`LuxmedClient` publishes a newly rotated session to its ready cache only after
`SessionStore.replace` succeeds. Once Luxmed has returned a rotated OAuth
token, the client retains it in a pending state while it completes the
NewPortal bootstrap and persistence. A failure in either phase blocks further
Luxmed calls and retries only the incomplete phase; it must not repeat the
refresh with the now-consumed old token. Password re-authentication is reserved
for a refresh token rejected by Luxmed, never for a NewPortal bootstrap or
local persistence failure. The per-account `Semaphore` covers session
inspection, refresh, bootstrap, replacement, and the subsequent operation, so
concurrent callers cannot start competing refreshes.

**Two-factor authentication is not enforced on this API** — established by the Plan 2 spike, with the evidence and caveats in §3.2. No enrollment flow is implemented; a single distinct failure path covers the possibility that it appears.


**XSRF is required for every reservation-mutating call.** `GET {newApi}/security/getforgerytoken` returns a token and its own cookies. `reservation/lockterm`, `reservation/confirm`, `reservation/changeterm`, and `reservation/releaseterm` each need the `xsrf-token` header **and** the session cookies merged with the XSRF cookies. Read-only endpoints do not.

**Endpoints used in v1:**

| Purpose | Call |
|---|---|
| Cities | `GET {newApi}/NewPortal/Dictionary/cities` → `[{id, name}]` |
| Services | `GET {newApi}/NewPortal/Dictionary/serviceVariantsGroups` |
| Facilities + doctors | `GET {newApi}/NewPortal/Dictionary/facilitiesAndDoctors?cityId&serviceVariantId` |
| Terms search | `GET {newApi}/NewPortal/terms/index` |
| Lock | `POST {newApi}/NewPortal/reservation/lockterm` (XSRF) |
| Confirm | `POST {newApi}/NewPortal/reservation/confirm` (XSRF) |
| Release | `POST {newApi}/NewPortal/reservation/releaseterm?reservationId=<id>` (XSRF) |

`terms/index` takes a wide, fiddly parameter set that must be sent in full: `searchPlace.id` (city), `searchPlace.type=0`, `serviceVariantId`, `languageId=10`, `searchDateFrom`, `searchDateTo`, `searchDatePreset=14`, `processId` (fresh UUID per call), `serviceVariantSource=0`, `facilitiesIds`, `doctorsIds`, `nextSearch=false`, `searchByMedicalSpecialist=false`, `delocalized=false`.

**Datetime quirk.** `dateTimeFrom` / `dateTimeTo` / `day` are returned *sometimes* with a zone offset (`2021-05-21T18:45:00+02:00`) and *sometimes* as a bare local datetime (`2021-05-21T18:45:00`). The decoder must accept both and normalise to `Europe/Warsaw` (§5.3). A decoder that assumes either form alone will fail intermittently in production — luxmed-bot models this explicitly rather than hoping.

**Error taxonomy from the wire** (drives `LuxmedError` in §7):
- `302` whose body or `Location` contains `/LogOn` or `/UniversalLink` → session expired → re-login and retry once.
- `409` whose body contains `nieprawidłowy login lub hasło` / `invalid login or password` → credentials are wrong → `AuthFailed`.
- Error bodies come in three shapes and all must be tried: `{"errors":[{code,message}]}`, `{"errors":{field:[msg]}}`, `{"error":{code,message}}`.
- A message containing `session has expired` → session expired, regardless of status.
- `429` → `RateLimited`. `5xx` → transient. Old-app-version rejection → `VersionRejected` (notifies admin).

### 5.5 Monitor engine

- One Gears supervisor started with the app; each active monitor runs a check loop.
- Check loop: search terms for the monitor's criteria → filter (date range, time window, days-of-week) → diff against seen slots (`monitor_events`) → notify / auto-book new ones.
- Intervals are per-monitor with ±20% random jitter; monitors sharing a Luxmed account queue behind its rate limiter rather than running concurrently.
- Failure policy:
  - Transient (network, 5xx): exponential backoff within the loop.
  - Auth failure: mark account `auth_failed`, pause its monitors, notify owner once — **with the reason**, since the remedy differs. Credentials rejected means retype them; an unexpected challenge-shaped response (§3.2) or a suspected fair-use lockout (§10) means do *not* retype them, because repeated login attempts make a lockout worse. The status carries a reason string precisely so the notification and the UI can say which it was.
  - Version rejection: notify admin.
  - Persistent unexpected errors (repeated decode failures or check crashes beyond the retry budget): monitor → `failed`, owner notified once; resuming it is a manual action in the UI.
  - A crashing check kills only its own fiber, never the supervisor.
- State transitions are persisted; restart resumes the active set exactly. Single process, no distributed coordination.

### 5.6 Frontend architecture: Elm-on-Gears

The frontend follows the Elm architecture, implemented directly on Gears, with Laminar reduced to a pure render layer. (Tyrian was considered and rejected: it provides this architecture but runs on Cats Effect, which would reintroduce the effect monad this design excludes. The Elm machinery is small enough to own — on the order of 100 lines.)

- **One store:** a single `Var[AppState]` — the only Airstream state in the app.
- **One message channel:** a Gears `Channel[Msg]`. DOM event handlers do exactly one thing: send a `Msg`. No logic in listeners.
- **One event loop**, a direct-style Gears fiber:
  1. read a `Msg` from the channel;
  2. apply the pure `update(state, msg): (AppState, List[Effect])`;
  3. write the new state to the `Var`;
  4. run each `Effect` (API calls via the shared Tapir client, timers, storage) in a spawned fiber as ordinary sequential Gears code with typed errors; results come back as new `Msg`s on the channel.
- **Laminar renders only:** views are functions of `Signal[AppState]` projections (`stateSignal.map(_.monitors).distinct` for fine-grained DOM updates). Business logic never touches an `EventStream` combinator.

`update` and all view-model derivation are pure and unit-tested without a DOM.

### 5.7 Programming style conventions (stack-wide)

The whole codebase is **direct-style functional Scala**: immutable data, pure domain logic, errors as values, side effects executed directly on virtual threads (JVM) / JSPI (Wasm) under Gears structured concurrency. No effect monads anywhere. Concretely:

1. **Gears is the only async vocabulary.** `scala.concurrent.Future` and JS `Promise` are banned from application signatures. Foreign async APIs are adapted once, at the edge, in a single small `bridge` package (Gears resolver/adapter utilities).
2. **Errors are values** (Scala 3 union types / `Either`); exceptions mean bugs and crash the failing fiber, never the supervisor.
3. **Airstream vocabulary is allowed in exactly one place:** the store `Var` and view projections (`Signal.map`/`distinct`). An `observe` or stream combinator anywhere else is a review flag.
4. **Declarative leaf layers carry no control flow:** Tapir endpoint descriptions and Laminar view templates describe structure; behavior lives in services (backend) and `update`/effects (frontend).
5. **No DI framework, no reflection:** plain classes wired by constructors; codecs and schemas derived at compile time.

## 6. Security

- Luxmed passwords: AES-256-GCM at rest, master key from env, decrypted only in memory at login time.
- **Luxmed sessions and device identities are also AES-256-GCM at rest** under the same master key (§5.3). A persisted session is a bearer credential, so it is treated as one: never logged, never returned over the API, and deleted with the account. One-time codes are held only for the seconds needed to complete a challenge and are never persisted or logged.
- Internal passwords: Argon2id. Sessions: opaque random tokens, stored hashed, cookie `HttpOnly` + `Secure` + `SameSite=Lax`.
- Authorization in the service layer, not just the UI.
- TLS terminated by the operator's reverse proxy; not in-app.
- Secrets never logged; Luxmed credentials masked in all log output.

## 7. Error handling

- Direct style with typed domain failures as values. Luxmed operations return
  `Either[LuxmedError, A]`; sequencing is ordinary direct-style Gears code with
  exhaustive matching, not an effect-polymorphic program. `LuxmedError` is a
  closed ADT covering at least `AuthFailed`, `UnexpectedAuthResponse`,
  `SessionExpired`, `VersionRejected`, `ApiRejected`, `RateLimited`,
  `Transient`, `DecodeFailed`, `PersistenceFailed`, `ProtocolViolation`, and
  `SlotGone`. `ApiRejected` retains only a redacted summary of a recognized
  Luxmed error envelope whose message has no more specific classification.
- Exceptions are reserved for genuine bugs; they crash the failing fiber, not the supervisor.

## 8. Testing

- **shared/domain:** pure unit tests (slot filtering, dedup, monitor state transitions) — most logic lives here by design.
- **Luxmed client:** tested with a hybrid architecture that keeps each test at
  the lowest HTTP layer capable of proving its claim:
  - **Session and client policy tests** (auth flow, refresh logic, error
    classification) inject an sttp `SttpBackendStub.synchronous` via the
    package-visible `LuxmedTransport.withBackend` construction path and a
    `StubLuxmedBackend` test harness. These tests remove socket lifecycle and
    expose the request description directly. They must assert consumer-visible
    results and hand-derived request properties rather than merely asserting
    that the stub was invoked.
  - **Wire-boundary tests** (redirect handling, raw `Set-Cookie` preservation,
    empty-body behavior, body serialization, method/path/header transmission,
    connection failures) use a real loopback HTTP server (`RealHttpLuxmedServer`,
    built on the JDK `HttpServer`). These tests prove what the JDK backend
    actually serializes and interprets, which no stub can verify.
  - A full mock conformance test (`MockConformanceTest`) crosses a real HTTP
    boundary end-to-end, proving the entire client pipeline produces the expected
    ten-step fingerprint.
- Realistic auth-response scripts live in a backend-independent shared test
  utility (`LuxmedResponseScripts`), constructed with literal response values
  rather than production JSON codecs. This avoids the encoder-contamination
  problem where the same codec passes both sides of a round-trip.
- Session tests use injected clock, sleeper, and UUID sources. They cover the
  ~300 s refresh threshold, refresh-token rotation, compare-and-set rejection,
  retry of pending persistence without a second HTTP refresh, rejected-refresh
  password fallback, and concurrent callers producing one refresh.
- Rate-limit tests prove one in-flight request and minimum spacing without
  wall-clock sleeps. Error tests cover the session-expiry 302, both
  bad-credential messages, all three error-body shapes, 429, 5xx, version
  rejection, an unexpected challenge-shaped response, malformed JSON, missing
  JWT, persistence failure, and secret redaction.
- Fixtures are the project's written record of current upstream behaviour; when a decode failure fires in production, the fixture is what gets updated. CI never calls live Luxmed endpoints.
- Before Plan 3 is declared complete, run one explicit, guided
  mock-conformance exploration against an owned Luxmed account. It previews
  its safety budget and asks for confirmation before login and each later
  phase. The required path performs one password grant, the NewPortal
  bootstrap, one refresh grant and re-bootstrap, dictionaries, one
  user-selected terms search, and XSRF acquisition. It sends at most 12
  requests, spaces them by at least five seconds, never retries, and never
  performs a second password grant. An optional advanced phase may lock one
  user-selected slot only after another explicit confirmation and must release
  it in `finally`; release failure is surfaced prominently because the slot may
  remain held until Luxmed expires it. The explorer never calls `confirm`,
  `changeterm`, or appointment cancellation — a real confirm books healthcare
  and belongs to Plan 6's safety work. It compares JSON shapes and required
  header/cookie names in memory, shows structural differences without writing
  payloads, and stops immediately on 401, 409, 429, a challenge-shaped
  response, or a version rejection. A persistent ignored attempt ledger caps
  it at two exploration sessions so an implementation bug cannot create a
  login loop. It is opt-in and never runs in CI, but one successful required
  exploration is completion evidence for Plan 3; the optional lock/release
  result is recorded separately.
- **Backend API:** integration tests with an embedded PostgreSQL-compatible database (memgres by default, zonky embedded-postgres opt-in) against Tapir endpoints in-process.
- **Frontend:** domain logic unit-tested; minimal DOM smoke tests in v1.
- TDD throughout; CI runs everything on every push.

## 9. Observability & ops

- Structured logging (slf4j/logback), `/health` endpoint, monitor status visible in the UI. No metrics stack in v1.
- Configuration via env vars: DB URL, credential master key, Telegram bot token, Luxmed app version string, initial admin credentials (`ADMIN_USERNAME`/`ADMIN_PASSWORD`, read only when the `users` table is empty). Device identities are **not** configuration — they are per-account data, generated once and stored (§5.3).
- docker-compose: backend container (API + static frontend) + Postgres.

## 10. Risks

| Risk | Mitigation |
|---|---|
| **MFA could be enabled per-account.** The Plan 2 spike found no enforcement on the mobile API, but `HasAccessToMFA` exists as a feature toggle and MFA appears to be account-level opt-in, so another user's account may behave differently — and Luxmed can change policy. | No enrollment flow is built (§3.2); instead a challenge-shaped response is classified distinctly, sets `auth_failed` with a reason, pauses that account's monitors, and asks the owner for manual attention. If it ever fires, the flow gets designed against the recorded payload. This is deliberately less than the earlier revision specified — building an untestable enrollment subsystem for an unreachable code path was the greater risk. |
| **The old mobile API is showing deprecation signals.** `POST /token` answers a stale `APP_VERSION` with a 409 saying the app "is not supported by the new Patient Portal system", and the web API is the platform's direction. | `APP_VERSION` is env-configurable so a bump needs no redeploy (§5.4); decode failures are logged with raw payloads; the admin is notified on version rejection. Migration to the web API's cookie-based auth is an architectural change, documented in the analysis report and monitored rather than pre-built. |
| **Refresh-token rotation breaks the session if a write is lost.** The refresh token is single-use; reuse returns 401 and the chain cannot be recovered. | Store the session atomically on every refresh (§5.3), refresh at ~300 s against a ~600 s TTL, and fall back to a password grant — which is rationed, so this must be rare rather than routine. |
| Luxmed API changes without notice | Configurable app version; decode failures logged with raw payloads; admin notified on version rejection; mock-server fixtures document current behavior |
| Gears is experimental; Scala.js Wasm/JSPI browser support | Documented frontend fallback (§5.1): swap the effect runner to `Future`; `update`, views, and the backend are unaffected |
| **Fair-use policy locks the account.** LuxMed temporarily locks an account (reported: ~1 day for a first breach) for excessive querying. A lock takes out every monitor on that account at once, and looks like an auth failure. | 10-min default interval with a 5-min enforced floor (§3.3), ±20% jitter, per-account rate limiting + mutex so monitors sharing an account queue rather than multiply, browser-like headers. Treat a sudden auth failure on a previously-working account as a possible lock, not only a bad password — surface it as such so the user does not "fix" it by re-entering correct credentials in a loop. |
| Aggressive polling triggers other Luxmed countermeasures | Per-account rate limiting + mutex, jittered intervals, browser-like headers |
| Auto-booking books the wrong thing | Strict filter re-validation before lock; payment/referral services excluded; full details in notification; bookings recorded and visible |
