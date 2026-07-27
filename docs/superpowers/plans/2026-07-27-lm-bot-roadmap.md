# lm-bot Implementation Roadmap

**Spec:** [`docs/superpowers/specs/2026-07-27-lm-bot-prd-design.md`](../specs/2026-07-27-lm-bot-prd-design.md) (as amended 2026-07-27)

The spec describes a complete application. At the TDD granularity this project uses, it does not fit one plan, so it is split into six sequential plans. Each produces working, testable software on its own and is written out in full only when it is reached — later plans are deliberately left as scope statements, because the real ergonomics of Gears, Magnum, and Scala.js/Wasm will be known by then and would otherwise invalidate speculative detail.

| # | Plan | Deliverable | Status |
|---|---|---|---|
| 1 | Foundation & auth walking skeleton | A deployable app you can log into | **written** — [`2026-07-27-lm-bot-01-foundation.md`](2026-07-27-lm-bot-01-foundation.md) |
| 2 | Luxmed API client & mock server | A client that authenticates and searches slots against a mock | not yet written |
| 3 | Luxmed accounts & monitor CRUD | Link accounts, create/edit monitors through the wizard | not yet written |
| 4 | Monitor engine & notifications | Monitors actually run and tell you what they found | not yet written |
| 5 | Auto-booking | Matching slots get booked | not yet written |
| 6 | Hardening & ops | Admin UI, ops notifications, observability, release polish | not yet written |

## Why this order

Plan 1 is a vertical slice through *every* layer — sbt cross-build, Postgres/Flyway/Magnum, the jdkhttp server, the Elm-on-Gears frontend runtime, Scala.js Wasm linking, Docker, CI. The stack is the single largest risk in this project (Gears is experimental, Scala.js Wasm needs JSPI, Magnum is young), so the first plan exists to prove the stack works end to end while the amount of code at stake is still small. If the Wasm/JSPI path fails here, the documented fallback in spec §5.1 (swap the effect runner to `Future`) costs a day rather than a rewrite.

Plans 2–5 then follow the data: get slots out of Luxmed, let users describe what they want, run those descriptions on a schedule, act on the results. Plan 2 is pure backend with no UI, and is the most test-heavy plan in the project — it is where the reverse-engineered API is pinned down against fixtures.

## Scope of the unwritten plans

### Plan 2 — Luxmed API client & mock server
Ports the client from dyrkin/luxmed-bot's real shapes (spec §5.4), **not** from lmassist. Covers: the three-step auth flow; the XSRF token flow; the in-memory session (`accessToken`, `tokenType`, `jwt`, cookie jar); per-account rate limiter and mutex via Gears `Semaphore`; the `LuxmedError` taxonomy; a decoder for the dual datetime format; dictionaries; `terms/index` with its full parameter set; and `lockterm` / `confirm` / `releaseterm`. Ships alongside a mock Luxmed server with fixtures transcribed from luxmed-bot's documented payloads, covering every error variant. No database, no HTTP API, no UI.

### Plan 3 — Luxmed accounts & monitor CRUD
AES-256-GCM credential encryption at rest with the master key from env; link-time live login verification; account status (`active` / `auth_failed` / `disabled`); a dictionaries proxy endpoint so the wizard is driven by live Luxmed data; the `monitors` table; monitor create/edit/pause/delete endpoints with service-layer ownership checks; the guided wizard UI and the monitor list. Interval validation enforces the 10-min default / 5-min floor from spec §3.3. Monitors are stored but nothing runs them yet.

### Plan 4 — Monitor engine & notifications
The Gears supervisor and per-monitor check loops; slot filtering (date range, time-of-day, days-of-week, all in `Europe/Warsaw`); `monitor_events` as the append-only log and the basis of slot dedup; the monitor state machine (`active` / `paused` / `completed` / `failed`) with the failure policy from §5.5 (backoff, auth-failure pause, version rejection to admin, retry budget); jittered per-monitor intervals queued behind each account's rate limiter; restart resuming the active set. Then the `NotificationChannel` trait and the Telegram implementation: plain sttp sends, deep-link `/start <code>` linking over long polling, per-slot dedup, and the no-Telegram-linked degradation from §3.5. Monitors now find slots and notify.

### Plan 5 — Auto-booking
The `lock → validate → confirm or release` sequence from the amended §3.4, including mandatory `releaseterm` on every abort and error path; strict filter re-validation before locking; valuation inspection for price/referral exclusion; the `bookings` table; monitor → `completed` on success; notification with full slot details. This plan is small but is the one where a bug has real-world consequences, so it gets the heaviest test scrutiny per line.

### Plan 6 — Hardening & ops
Admin user management UI; password change and reset; the remaining error surfaces and empty states; structured logging review with a secret-masking audit; ops notifications to admin; docker-compose and deployment documentation polish; a pass over accessibility and responsive layout.

## Conventions that apply to every plan

These come from spec §5.7 and are not repeated in each task:

1. Gears is the only async vocabulary. `scala.concurrent.Future` and JS `Promise` never appear in application signatures — only inside the `bridge` package.
2. Errors are values (`Either`, union types). Exceptions mean bugs and crash their own fiber, never the supervisor.
3. Airstream vocabulary appears in exactly two places: the store `Var` and view projections.
4. Tapir endpoint descriptions and Laminar views carry no control flow.
5. No DI framework and no reflection; plain classes wired by constructors, codecs derived at compile time.
6. TDD throughout. CI runs everything on every push.
