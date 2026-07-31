# lm-bot Implementation Roadmap

**Spec:** [`docs/superpowers/specs/2026-07-27-lm-bot-prd-design.md`](../specs/2026-07-27-lm-bot-prd-design.md) (as amended 2026-07-27)

The spec describes a complete application. At the TDD granularity this project uses, it does not fit one plan, so it is split into seven sequential plans. Each produces working, testable software on its own and is written out in full only when it is reached — later plans are deliberately left as scope statements, because the real ergonomics of Gears, Magnum, and Scala.js/Wasm will be known by then and would otherwise invalidate speculative detail.

| # | Plan | Deliverable | Status |
|---|---|---|---|
| 1 | Foundation & auth walking skeleton | A deployable app you can log into | ✅ **complete** — [plan](2026-07-27-lm-bot-01-foundation.md), [review](../reports/2026-07-27-plan-01-review.md) |
| 2 | **API spike** (investigation, not implementation) | Auth flows, JWT, token rotation measured; MFA found **not enforced** | ✅ **complete** — [plan](2026-07-27-lm-bot-02-2fa-spike.md), [findings](../reports/2026-07-27-luxmed-api-analysis.md) |
| 3 | Luxmed API client & mock server | A client that authenticates and searches slots against a mock | ✅ **complete** — [plan](2026-07-28-lm-bot-03-luxmed-client.md), [report](../reports/2026-07-28-plan-03-complete.md) |
| 4 | Luxmed accounts & monitor CRUD | Link accounts, create/edit monitors | 🚧 **in progress** — persistence & service layer shipped, [reviewed](../reports/2026-07-30-plan-04-review.md) (Tasks 1–5 of [the plan](2026-07-30-lm-bot-04-accounts-monitors.md)); HTTP routes, monitor service wiring, and the frontend UI (Tasks 6–11) remain |
| 5 | Monitor engine & notifications | Monitors actually run and tell you what they found | not yet written |
| 6 | Auto-booking | Matching slots get booked | not yet written |
| 7 | Hardening & ops | Admin UI, ops notifications, observability, release polish | not yet written |

**Plan 2 was inserted on 2026-07-27** when Luxmed's two-factor authentication came to light. It is an investigation, not a build: it produces recorded evidence and a decision, and writes no production code. Everything downstream of it is renumbered.

## Why this order

Plan 1 is a vertical slice through *every* layer — sbt cross-build, Postgres/Flyway/Magnum, the jdkhttp server, the Elm-on-Gears frontend runtime, Scala.js Wasm linking, Docker, CI. The stack is the single largest risk in this project (Gears is experimental, Scala.js Wasm needs JSPI, Magnum is young), so the first plan exists to prove the stack works end to end while the amount of code at stake is still small. If the Wasm/JSPI path fails here, the documented fallback in spec §5.1 (swap the effect runner to `Future`) costs a day rather than a rewrite.

Plan 2 comes next because it is now the project's biggest unknown: Luxmed's two-factor flow is undocumented, unimplemented by any predecessor, and shapes the design of the client, the persistence model, the account state machine, and the notification surface. Investigating it costs an evening; guessing it wrong costs a rewrite of all four.

Plans 3–6 then follow the data: get slots out of Luxmed, let users describe what they want, run those descriptions on a schedule, act on the results. Plan 3 is pure backend with no UI, and is the most test-heavy plan in the project — it is where the reverse-engineered API is pinned down against fixtures.

## Scope of the unwritten plans

### Plan 3 — Luxmed API client & mock server

Ports the client from dyrkin/luxmed-bot's real shapes (spec §5.4), **not** from lmassist. Covers: the three-step auth flow; the XSRF token flow; the session (`accessToken`, `tokenType`, `refreshToken`, `expiresAt`, `jwt`, cookie jar) with **proactive refresh at ~300 s and an atomic compare-and-set `SessionStore` contract for the rotating refresh token** — the spike's sharpest finding, and the easiest thing here to get quietly wrong; per-account rate limiter and mutex via Gears `Semaphore`; the `LuxmedError` taxonomy, including one variant for an unexpected challenge-shaped response (§3.2); a decoder for the dual datetime format; dictionaries; `terms/index` with its full parameter set; and `lockterm` / `confirm` / `releaseterm`. The implementation is direct-style functional Scala with Gears and `Either`-valued expected failures. It ships an in-memory store and a JDK `HttpServer` mock whose literal fixtures are independent of the production codecs. No database, no HTTP API, no UI.

**No 2FA enrollment flow** — the spike showed it is unreachable, and §3.2 now specifies a single failure path instead.

One caution: §1.5 of the analysis report lists mobile-API endpoint paths (`/api/lockterm`, `/api/confirm`, …) that were **not exercised** during the spike and do not match luxmed-bot's verified `NewPortal/*` paths. Use the verified ones from §5.4; treat that table as a hypothesis, not a source.

### Plan 4 — Luxmed accounts & monitor CRUD

AES-256-GCM encryption at rest for credentials and **persisted sessions**, with the master key from env; the PostgreSQL implementation of Plan 3's compare-and-set `SessionStore`; an lm-bot `AccountId` owned by the account/application domain rather than the Luxmed wire model; **single-step linking** verified by a live login; account status (`active` / `auth_failed` / `disabled`) with a reason string so a challenge or lockout is never surfaced as "wrong password" (§5.5); a dictionaries proxy endpoint so the wizard is driven by live Luxmed data; the `monitors` table; monitor create/edit/pause/delete endpoints with service-layer ownership checks; the guided wizard UI and the monitor list. Interval validation enforces the 10-min default / 5-min floor from spec §3.3. Monitors are stored but nothing runs them yet.

The session round-trip gets explicit test coverage: store, restart, refresh, and confirm the rotated refresh token survived. Losing that write is unrecoverable without a password grant (§10).

### Plan 5 — Monitor engine & notifications

The Gears supervisor and per-monitor check loops; slot filtering (date range, time-of-day, days-of-week, all in `Europe/Warsaw`); `monitor_events` as the append-only log and the basis of slot dedup; the monitor state machine (`active` / `paused` / `completed` / `failed`) with the failure policy from §5.5 (backoff, auth-failure pause **carrying a reason**, version rejection to admin, retry budget); jittered per-monitor intervals queued behind each account's rate limiter; restart resuming the active set. Then the `NotificationChannel` trait and the Telegram implementation: plain sttp sends, deep-link `/start <code>` linking over long polling, per-slot dedup, and the no-Telegram-linked degradation from §3.5. Monitors now find slots and notify.

### Plan 6 — Auto-booking

The `lock → validate → confirm or release` sequence from the amended §3.4, including mandatory `releaseterm` on every abort and error path; strict filter re-validation before locking; valuation inspection for price/referral exclusion; the `bookings` table; monitor → `completed` on success; notification with full slot details. This plan is small but is the one where a bug has real-world consequences, so it gets the heaviest test scrutiny per line.

### Plan 7 — Hardening & ops

Admin user management UI; password change and reset; the remaining error surfaces and empty states; structured logging review with a secret-masking audit; ops notifications to admin; docker-compose and deployment documentation polish; a pass over accessibility and responsive layout.

## Development environment

`flake.nix` + `.envrc` at the repository root pin the entire toolchain (Temurin 25, sbt launcher, Node 26, Metals, scalafmt, `psql`, and the spike's `curl`/`jq`/`uuidgen`). Every plan assumes you are inside that shell; CI runs the same shell via `nix develop`, so local and CI cannot diverge — which matters more than usual given the stack's dependence on an exact Node major and a JSPI-capable runtime.

Two things the flake deliberately does **not** provide:

- **A container runtime.** That is a host service. On this dev machine it is rootless Podman, which Testcontainers cannot discover unaided, so the devShell exports `DOCKER_HOST` and disables Ryuk when it finds the Podman socket.
- **sbt itself, in the version that matters.** The nixpkgs `sbt` is only a launcher; `project/build.properties` declares **sbt 2.0.4**, which the launcher starts.

## Conventions that apply to every plan

These come from spec §5.7 and are not repeated in each task:

1. Gears is the only async vocabulary. `scala.concurrent.Future` and JS `Promise` never appear in application signatures — only inside the `bridge` package.
2. Errors are values (`Either`, union types). Exceptions mean bugs and crash their own fiber, never the supervisor.
3. Airstream vocabulary appears in exactly two places: the store `Var` and view projections.
4. Tapir endpoint descriptions and Laminar views carry no control flow.
5. No DI framework and no reflection; plain classes wired by constructors, codecs derived at compile time.
6. TDD throughout. CI runs everything on every push.
