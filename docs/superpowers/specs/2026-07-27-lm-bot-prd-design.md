# lm-bot — Product Requirements Document

**Date:** 2026-07-27
**Status:** Approved design, pre-implementation. Amended 2026-07-27 twice: first after verifying the API against a working client (§5.4), then to incorporate Luxmed's new two-factor authentication (§3.2, §5.3, §5.4, §5.5, §3.5, §6, §10).
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
- Account status surfaced in UI: `active` / `awaiting_2fa` / `auth_failed` / `disabled`.

**Two-factor authentication (added 2026-07-27).** Luxmed now requires a second factor — a one-time code by SMS or email, or a confirmation tap in the Portal Pacjenta app. **The challenge fires only for devices Luxmed does not recognise**, and Luxmed maintains a per-user trusted-device list. That single fact is what keeps unattended monitoring possible, and the whole design turns on it:

1. lm-bot generates a **stable device identity per linked Luxmed account** at link time and persists it. It is presented on every subsequent request for that account and never regenerated.
2. The first login from that identity raises a challenge. Linking therefore becomes a **two-step, stateful flow**: submit credentials → Luxmed challenges → user supplies the code → lm-bot completes the login. The account sits in `awaiting_2fa` between the steps.
3. Once a challenge is completed, the device is trusted and later logins for that account proceed unattended.

Consequences that are easy to get wrong:

- **Losing the device identity means re-enrolling.** It is the most important thing in the database to preserve — more so than the session. A regenerated identity presents as a new device and triggers a fresh challenge. Any code path that recreates it silently is a bug.
- **`awaiting_2fa` is not `auth_failed`.** Reporting a challenge as an authentication failure invites the user to retype credentials that were always correct — the same failure mode called out for account lockout in §10. The two states have different causes, different UI, and different remedies.
- **A challenge can also appear mid-life**, not only at linking: trust may be revoked or expire server-side. The engine must handle a challenge arriving at any login (§5.5), not just the first.

The predecessor projects have not solved this: [luxmed-bot#113](https://github.com/dyrkin/luxmed-bot/issues/113) has been open and unfixed since 2026-06-25, with the bot simply receiving a 401. It hardcodes a device UUID but cannot complete a challenge, so its device is never enrolled — a stable identity alone is insufficient. There is consequently **no reference implementation to port for this flow**, which is why the exact wire mechanics are established by a dedicated spike before implementation (§5.4, and Plan 2 of the roadmap).

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
- **Inbound Telegram also accepts a two-factor code** (§3.2). When an account is challenged mid-life, the owner is notified and can reply with the code directly, rather than having to open the web UI — a challenge at an inconvenient hour would otherwise stall every monitor on that account until someone reached a browser. Codes are accepted only from the linked chat, only while that user has an account in `awaiting_2fa`, and only within a short window. Both inbound interactions still arrive by long polling; there is no public webhook in v1.
- Notification types: new slots found, auto-book success/failure, Luxmed account auth failure (once, with monitors paused), **two-factor code required (once per challenge, with monitors paused)**, monitor completed/expired. Version-rejection errors from Luxmed notify the **admin** (ops event).
- A user with no Telegram linked supplies codes through the web UI instead; the challenge is visible on the accounts page regardless of channel.
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
| Language | Scala 3.8 (JVM 21+ backend; Scala.js **Wasm** backend frontend) |
| Concurrency | Gears (direct style) on both platforms |
| API contract | Tapir endpoint definitions in a shared cross-compiled module |
| HTTP server | tapir-jdkhttp-server (`Identity` interpreter) on a virtual-thread executor |
| HTTP client | sttp (backend: Luxmed + Telegram; frontend: Tapir-derived API client) |
| Frontend UI | Laminar |
| Database | PostgreSQL, Flyway migrations; Magnum over blocking JDBC on virtual threads |
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
| Testcontainers | 1.21.3 | |
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
| `luxmed_accounts` | id, owner user id, label, Luxmed username, AES-GCM-encrypted password, status, last successful login, **stable device identity, AES-GCM-encrypted persisted session (access + refresh token, JWT, cookie jar), pending-challenge state** |
| `monitors` | id, luxmed account id, criteria (city/service/facility/doctor ids + denormalized names), date range, time window, days-of-week mask, auto-book flag, state, check interval, timestamps |
| `monitor_events` | append-only per-monitor log: slots found, notification sent, booking attempted/succeeded/failed, error; powers detail view and slot dedup |
| `bookings` | v1-minimal record of auto-booked appointments (reservation id, slot details, monitor id) |

Rules:
- All Luxmed-facing dates and times (slot times, monitor date ranges, time-of-day windows) are interpreted in `Europe/Warsaw`, regardless of server or browser time zone.
- Every resource is reachable only through its owning user; ownership checked in the service layer on every operation.
- Deleting a Luxmed account cascades to its monitors (UI confirms first).
- A Luxmed session is **more than a JWT**: it is `(accessToken, tokenType, refreshToken, jwtToken, cookieJar)`. The cookie jar is accumulated across all three auth steps and must be replayed on every subsequent call, merged with XSRF cookies for reservation operations (§5.4).
- **The session and the device identity are persisted, encrypted** (AES-256-GCM, same master key as credentials). This reverses the pre-2FA design, which kept the session in memory only. The reason is §3.2: a login from an unrecognised device raises a challenge that needs a human, so a process restart that discarded sessions and device identities would demand a code for every account on every deploy. Persisting them makes restart resume silently, as §5.5 requires.
- The device identity is written once at link time and thereafter only read. Rotating it is never automatic and always costs a fresh challenge.

### 5.4 Luxmed client

- One client per linked account; sttp + Gears `Async`.
- Two base URLs: `oldApi = https://portalpacjenta.luxmed.pl/PatientPortalMobileAPI/api`, `newApi = https://portalpacjenta.luxmed.pl/PatientPortal`.
- `Custom-User-Agent` app version string is **configurable via env var** — Luxmed rejects outdated versions (401 / 409 "old app version") and this must be changeable without redeploy.
- In-memory session cache per account; re-login on 401 or on a detected session expiry.
- Per-account rate limiter and mutex: one in-flight request per Luxmed account, minimum spacing between calls.
- Redirects are **not** followed automatically; a 302 is a signal, not a hop (see session-expiry detection below).
- Any response that fails to decode is logged with the raw payload — the early-warning system for upstream API changes. Credentials and tokens are masked in all such output (§6).

**Auth flow (three steps, then XSRF on demand):**

1. `POST {oldApi}/token` — **form-encoded** body `client_id=Android`, `grant_type=password`, `username`, `password`. Returns `access_token`, `expires_in`, `refresh_token`, `token_type`.
2. `GET {newApi}/Account/LogInToApp?app=search&client=3&lang=pl` — header `Authorization: <access_token>` (**no** `Bearer` prefix) plus `X-Requested-With: pl.luxmed.pp`. Sets session cookies, including the JWT.
3. `GET {newApi}/NewPortal/Page/Reservation` — replays the cookies from step 2 and accumulates the remainder.

Authenticated `NewPortal/*` calls then use header `authorization-token: Bearer <jwt>` plus the cookie jar.

**Device identity.** `Custom-User-Agent` has the form `Patient Portal; {appVersion}; {deviceUuid}; Android; {apiLevel}; {deviceName}`. The `deviceUuid` is **not** a per-session random value: it is the account's persisted device identity (§3.2), stable for the lifetime of the link. lmassist regenerated it per session, which would present as an unknown device on every login and guarantee a challenge every time — do not copy that. Only `appVersion` comes from env; the UUID comes from the database.

**Two-factor challenge.** Since 2026 a login from an unrecognised device is challenged, and the exact wire mechanics are **not documented anywhere and not implemented by any predecessor project** (§3.2). They are therefore established empirically before this client is built. The spike (Plan 2 of the roadmap) must answer, with recorded payloads:

1. Which step raises the challenge (`POST /token`, or `LogInToApp`), and what the response is — status code, body shape, and any challenge/transaction identifier to carry into the verification call.
2. Which endpoint verifies the code, with what body, and what it returns on success and on a wrong or expired code.
3. Whether the method (SMS / email / mobile-app tap) is chosen by the client or dictated by the account's settings, and how a mobile-app confirmation is polled rather than typed.
4. Which field actually establishes device identity — the `Custom-User-Agent` UUID, the `account_id` / `client_id` in the token request, a long-lived cookie, or a combination.
5. Whether `grant_type=refresh_token` renews a session without a challenge, and how long trust survives across process restarts, IP changes, and elapsed time.

Until those are recorded, the client's 2FA code is not written. Everything else in this section is already pinned down and can proceed independently.

**Session maintenance.** The access token is short-lived (`expires_in` is around 600 seconds). The session is refreshed **proactively on a timer**, not lazily on a 401, because a 401 now means "possibly challenged" rather than merely "expired", and the cheapest challenge is the one never triggered. A refresh failure downgrades to a full re-login; a challenge during that re-login moves the account to `awaiting_2fa` (§5.5).

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
  - Auth failure (credentials rejected): mark account `auth_failed`, pause its monitors, notify owner once.
  - **Two-factor challenge:** mark account `awaiting_2fa`, pause its monitors, notify the owner once with a prompt to supply the code. Monitors resume automatically the moment the challenge is completed — no manual resume, because the user has already acted. This is distinct from `auth_failed`: the credentials are fine and asking the user to re-enter them would be actively misleading (§3.2).
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

- Direct style with typed domain failures as values (Scala 3 union types / `Either`), e.g. `LuxmedError = AuthFailed | SlotGone | VersionRejected | RateLimited | ApiChanged`.
- Exceptions are reserved for genuine bugs; they crash the failing fiber, not the supervisor.

## 8. Testing

- **shared/domain:** pure unit tests (slot filtering, dedup, monitor state transitions) — most logic lives here by design.
- **Luxmed client:** tested against a mock Luxmed server, replicating lmassist's `luxmed-mock-server` *approach* — but with fixtures transcribed from the shapes documented in luxmed-bot's model sources, **not** from lmassist's invented ones (§1). Coverage: the three-step auth flow, XSRF acquisition, dictionaries, terms search (including both datetime forms in one response), lock → confirm, lock → release, and each error variant (session-expiry 302, bad-credentials 409, all three error-body shapes, 429, version rejection).
- Fixtures are the project's written record of current upstream behaviour; when a decode failure fires in production, the fixture is what gets updated.
- **Backend API:** integration tests with real Postgres (Testcontainers) against Tapir endpoints in-process.
- **Frontend:** domain logic unit-tested; minimal DOM smoke tests in v1.
- TDD throughout; CI runs everything on every push.

## 9. Observability & ops

- Structured logging (slf4j/logback), `/health` endpoint, monitor status visible in the UI. No metrics stack in v1.
- Configuration via env vars: DB URL, credential master key, Telegram bot token, Luxmed app version string, initial admin credentials (`ADMIN_USERNAME`/`ADMIN_PASSWORD`, read only when the `users` table is empty). Device identities are **not** configuration — they are per-account data, generated once and stored (§5.3).
- docker-compose: backend container (API + static frontend) + Postgres.

## 10. Risks

| Risk | Mitigation |
|---|---|
| **Two-factor authentication blocks unattended login.** Luxmed added MFA in 2026; it fires for unrecognised devices and returns a 401 to naive clients. No predecessor project has solved it ([luxmed-bot#113](https://github.com/dyrkin/luxmed-bot/issues/113), open since 2026-06-25), so there is no implementation to port. | Challenges fire **only for unknown devices**, so a stable per-account device identity plus one completed enrollment yields unattended operation thereafter (§3.2). Device identity and session are persisted encrypted so restarts do not re-trigger challenges. Wire mechanics are established by a spike (Plan 2) before any 2FA code is written. Residual risk: if Luxmed later expires device trust aggressively, monitoring degrades to bursts around each re-authorisation — that would be re-planned, not worked around. |
| Losing a device identity silently re-triggers 2FA for an account | Identity is written once at link time and thereafter read-only; never regenerated on a code path that can run unattended; covered by tests asserting stability across restart |
| Luxmed API changes without notice | Configurable app version; decode failures logged with raw payloads; admin notified on version rejection; mock-server fixtures document current behavior |
| Gears is experimental; Scala.js Wasm/JSPI browser support | Documented frontend fallback (§5.1): swap the effect runner to `Future`; `update`, views, and the backend are unaffected |
| **Fair-use policy locks the account.** LuxMed temporarily locks an account (reported: ~1 day for a first breach) for excessive querying. A lock takes out every monitor on that account at once, and looks like an auth failure. | 10-min default interval with a 5-min enforced floor (§3.3), ±20% jitter, per-account rate limiting + mutex so monitors sharing an account queue rather than multiply, browser-like headers. Treat a sudden auth failure on a previously-working account as a possible lock, not only a bad password — surface it as such so the user does not "fix" it by re-entering correct credentials in a loop. |
| Aggressive polling triggers other Luxmed countermeasures | Per-account rate limiting + mutex, jittered intervals, browser-like headers |
| Auto-booking books the wrong thing | Strict filter re-validation before lock; payment/referral services excluded; full details in notification; bookings recorded and visible |
