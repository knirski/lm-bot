# lm-bot — Product Requirements Document

**Date:** 2026-07-27
**Status:** Approved design, pre-implementation
**Predecessors:** [dyrkin/luxmed-bot](https://github.com/dyrkin/luxmed-bot) (feature reference), [knirski/lmassist](https://github.com/knirski/lmassist) (prototype; source of Luxmed API research, domain dictionary, and mock-server approach)

## 1. Overview

lm-bot is a self-hosted web application for monitoring and booking Luxmed medical appointments. A small circle of users (family scale) log in with internal accounts, link one or more Luxmed patient-portal accounts, and create **monitors** — persistent watches for appointment slots matching their criteria. When a monitor finds a match, lm-bot notifies the user via Telegram and, if the monitor has auto-booking enabled, books the slot automatically.

The Luxmed API is unofficial and reverse-engineered (from the mobile app `pl.luxmed.pp`); lm-bot ports the API knowledge documented in lmassist (`docs/backend-plan/02-luxmed-api-research.md`) and its domain dictionary.

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
- Per-account Luxmed session (JWT) managed in memory with automatic re-login; never persisted.
- Account status surfaced in UI: `active` / `auth_failed` / `disabled`.

### 3.3 Monitors
- **Create** via a guided flow driven by live Luxmed dictionaries: city → service/variant → optional facilities → optional doctors; plus date range, time-of-day window, days-of-week, auto-book toggle, check interval (default ~5 min).
- **Browse** all own monitors with state and last-check summary; **edit**, **pause/resume**, **delete**.
- **Detail view** shows the event log: slots found, notifications sent, booking attempts, errors.
- Monitor states: `active`, `paused`, `completed` (auto-booked or date range passed), `failed`.

### 3.4 Auto-booking
- When enabled, the first new slot matching all filters is booked: **lock** (temporary reservation) → **confirm**.
- On success: monitor → `completed`, a `bookings` record is written, user notified with full slot details.
- On failure (slot taken, payment required, referral required): fall back to notify-only; monitor stays active. Services requiring payment or a referral are never auto-booked.
- Auto-book never books a different slot in the same check without re-validating filters against it.

### 3.5 Telegram notifications
- `NotificationChannel` trait; v1 ships one implementation: Telegram Bot API via plain sttp POST (no bot framework dependency).
- Linking: settings page shows a one-time deep-link (`t.me/<bot>?start=<code>`); the bot receives `/start <code>` via long polling and the backend stores the chat id. This is the only inbound Telegram interaction in v1 (no public webhook).
- Notification types: new slots found, auto-book success/failure, Luxmed account auth failure (once, with monitors paused), monitor completed/expired. Version-rejection errors from Luxmed notify the **admin** (ops event).
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
| `luxmed_accounts` | id, owner user id, label, Luxmed username, AES-GCM-encrypted password, status, last successful login |
| `monitors` | id, luxmed account id, criteria (city/service/facility/doctor ids + denormalized names), date range, time window, days-of-week mask, auto-book flag, state, check interval, timestamps |
| `monitor_events` | append-only per-monitor log: slots found, notification sent, booking attempted/succeeded/failed, error; powers detail view and slot dedup |
| `bookings` | v1-minimal record of auto-booked appointments (reservation id, slot details, monitor id) |

Rules:
- All Luxmed-facing dates and times (slot times, monitor date ranges, time-of-day windows) are interpreted in `Europe/Warsaw`, regardless of server or browser time zone.
- Every resource is reachable only through its owning user; ownership checked in the service layer on every operation.
- Deleting a Luxmed account cascades to its monitors (UI confirms first).
- Luxmed JWTs live in memory only.

### 5.4 Luxmed client

- One client per linked account; sttp + Gears `Async`.
- Auth flow as documented in lmassist research: `POST /PatientPortalMobileAPI/api/token` → `GET /PatientPortal/Account/LogInToApp` → JWT for `/PatientPortal/NewPortal/*` (dictionaries, terms search, reservation lock/confirm/cancel).
- `Custom-User-Agent` app version string is **configurable via env var** — Luxmed rejects outdated versions (401 / 409 "old app version") and this must be changeable without redeploy.
- In-memory session cache per account; re-login on 401.
- Per-account rate limiter and mutex: one in-flight request per Luxmed account, minimum spacing between calls.
- Any response that fails to decode is logged with the raw payload — the early-warning system for upstream API changes.

### 5.5 Monitor engine

- One Gears supervisor started with the app; each active monitor runs a check loop.
- Check loop: search terms for the monitor's criteria → filter (date range, time window, days-of-week) → diff against seen slots (`monitor_events`) → notify / auto-book new ones.
- Intervals are per-monitor with ±20% random jitter; monitors sharing a Luxmed account queue behind its rate limiter rather than running concurrently.
- Failure policy:
  - Transient (network, 5xx): exponential backoff within the loop.
  - Auth failure: mark account `auth_failed`, pause its monitors, notify owner once.
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
- Internal passwords: Argon2id. Sessions: opaque random tokens, stored hashed, cookie `HttpOnly` + `Secure` + `SameSite=Lax`.
- Authorization in the service layer, not just the UI.
- TLS terminated by the operator's reverse proxy; not in-app.
- Secrets never logged; Luxmed credentials masked in all log output.

## 7. Error handling

- Direct style with typed domain failures as values (Scala 3 union types / `Either`), e.g. `LuxmedError = AuthFailed | SlotGone | VersionRejected | RateLimited | ApiChanged`.
- Exceptions are reserved for genuine bugs; they crash the failing fiber, not the supervisor.

## 8. Testing

- **shared/domain:** pure unit tests (slot filtering, dedup, monitor state transitions) — most logic lives here by design.
- **Luxmed client:** tested against a mock Luxmed server with recorded fixtures (auth flow, terms, lock/confirm, error variants including version rejection), replicating lmassist's `luxmed-mock-server` approach.
- **Backend API:** integration tests with real Postgres (Testcontainers) against Tapir endpoints in-process.
- **Frontend:** domain logic unit-tested; minimal DOM smoke tests in v1.
- TDD throughout; CI runs everything on every push.

## 9. Observability & ops

- Structured logging (slf4j/logback), `/health` endpoint, monitor status visible in the UI. No metrics stack in v1.
- Configuration via env vars: DB URL, credential master key, Telegram bot token, Luxmed app version string, initial admin credentials (`ADMIN_USERNAME`/`ADMIN_PASSWORD`, read only when the `users` table is empty).
- docker-compose: backend container (API + static frontend) + Postgres.

## 10. Risks

| Risk | Mitigation |
|---|---|
| Luxmed API changes without notice | Configurable app version; decode failures logged with raw payloads; admin notified on version rejection; mock-server fixtures document current behavior |
| Gears is experimental; Scala.js Wasm/JSPI browser support | Documented frontend fallback (§5.1): swap the effect runner to `Future`; `update`, views, and the backend are unaffected |
| Aggressive polling triggers Luxmed countermeasures | Per-account rate limiting + mutex, jittered intervals, browser-like headers |
| Auto-booking books the wrong thing | Strict filter re-validation before lock; payment/referral services excluded; full details in notification; bookings recorded and visible |
