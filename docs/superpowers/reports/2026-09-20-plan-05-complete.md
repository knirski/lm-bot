# Plan 5 Completion Report: Monitor Engine & Notifications

**Date:** 2026-09-20
**Plan:** [`2026-09-20-lm-bot-05-monitor-engine-notifications.md`](../plans/2026-09-20-lm-bot-05-monitor-engine-notifications.md)
**Spec:** [`2026-07-27-lm-bot-prd-design.md`](../specs/2026-07-27-lm-bot-prd-design.md) (as amended 2026-09-20)

**changed-files:** the plan and its spec amendments (PR #58); the shared engine
contract, `monitor_events` schema and repositories, per-account client registry,
slot search, and the notification/Telegram boundary (PR #59); the engine,
application wiring, and HTTP surface (PR #60); the frontend detail/settings
work, the acceptance harness (`Plan5AcceptanceApp`, `FakeTelegramServer`, the
stub's `terms/index` route), this report, `README.md`, and the roadmap (PR #61).
A full file list is in each PR.
**verification-run:** `sbt testFull` (589 passed before the frontend work; the
final run is recorded below), `sbt frontend/fastLinkJS`, `nix flake check`,
`git diff --check`, and a seven-scenario real-browser run against
`Plan5AcceptanceApp` in Chromium 152.
**skipped-checks:** none. No test was excluded, renamed, or skipped.
**branch:** `feat/plan-05-frontend` (Tasks 9–10); Tasks 1–8 merged on `main`.
**pr:** #58, #59, #60 merged; #61 for Tasks 9–10.
**blocker:** none.

## What Plan 5 delivered

Monitors now run: each active monitor checks Luxmed on a jittered interval,
records what it finds in an append-only event log, and notifies its owner over
Telegram — or visibly degrades when no chat is linked.

| Area | Delivered |
|---|---|
| Engine | A Gears supervisor reconciles the active set from Postgres and runs one cancellable fiber per monitor; a monitor created or resumed after startup is picked up at the next pass; the first check is immediate |
| Filtering | Pure Warsaw-local `SlotFilter` in `shared`: date range, `[timeFrom, timeTo)` window, days-of-week, and optional clinic/doctor selections, applied **before** dedup |
| Dedup | `monitor_events` with a partial unique index on `(monitor_id, slot_key)` where `kind = 'slot_found'`; a slot is notified at most once for the monitor's lifetime, enforced by the database, not by memory |
| Failure policy | Transient backoff 1 min × 2ⁿ capped 30 min; rate-limit backoff 5 min → 1 h; three consecutive persistent failures → `failed`; auth failure pauses the account and notifies once **with the reason**; version rejection notifies linked admins once and keeps retrying; a crashing check is one counted failure, never a dead supervisor |
| Fair use | One Luxmed client and `AccountGate` per account (issue #35), shared by engine checks and browser dictionary calls; ±20 % jitter; interval floor unchanged |
| Completion | A monitor past its date range moves to `completed` and notifies once |
| Telegram | `NotificationChannel` boundary; plain sttp Bot API; one-time `/start <code>` deep links over long polling, codes Argon2id-hashed, single-use, 15-minute expiry |
| Degradation | A user without a linked chat still runs monitors; events are recorded and the UI warns no notifications will be delivered (spec §3.5) |
| UI | Last-check summary per row, a detail view with the event log, Resume for failed monitors, and a Telegram settings section with link/unlink |

## Acceptance harness

`Plan5AcceptanceApp` (test scope) composes the full graph — database, auth,
accounts, dictionaries, monitors, engine, poller, settings routes — and
substitutes only the two owned external boundaries:

- **Luxmed** by the Plan 4 loopback stub, extended with a `terms/index` route
  that returns one slot per day for the first week of the requested window (the
  schedule id is the day's epoch day, so slot keys are stable and distinct).
- **Telegram** by `FakeTelegramServer`, selected with `TELEGRAM_API_BASE`; it
  records every `sendMessage` and serves queued `/start <code>` updates.

The engine's `Sleeper` caps every wait at 200 ms so the run takes seconds; the
real durations are unit-tested in `EnginePolicyTest`. A control server injects
`/start` updates, makes the Luxmed stub answer malformed JSON, and reports
monitor state, event counts, and sent messages.

```bash
sbt "backend/Test/runMain lmbot.backend.Plan5AcceptanceApp"
```

## Browser acceptance run

Chromium 152, driven by `agent-browser`. All seven scenarios passed; the
console was empty throughout.

| # | Scenario | Result |
|---|---|---|
| 1 | Sign in as the bootstrapped admin and link a Luxmed account | Account row **Active**; the monitor list shows the "No Telegram chat is linked" warning |
| 2 | Create a monitor (Białystok, allergologist, 2026-09-21→09-30, 08:00–12:00, all seven days, 10-minute interval) | Saved; the engine's first check found **7 slots** within seconds |
| 3 | Detail view | 7 `slot_found` events, newest first, each with clinic, doctor, and Warsaw datetimes; row shows "Checked … — No new slots" |
| 4 | No Telegram linked | **0 messages sent** while 7 slots were recorded — the §3.5 degradation, visible in the UI as a warning |
| 5 | Link Telegram | Code shown in Settings → control server queued `/start <code>` → the real poller consumed it and replied; reload shows **Unlink Telegram** and the warning is gone |
| 6 | New slots after linking (edited the range to October) | 7 new `slot_found` + **7 `notification_sent`**, and the fake Bot API received 7 messages with full slot details |
| 7 | Pause → Resume, then force failures | Pause reached the engine (`paused`), Resume returned `active`; three malformed terms responses moved the monitor to **Failed**, the owner received "stopped after repeated errors: Malformed JSON response", and the UI offered **Resume**, which returned it to `active` |

No secret appeared in any response or notification: messages carry only monitor
names, clinic/doctor names, and Warsaw datetimes.

## Plan 5 completion criteria

| Criterion | Evidence |
|---|---|
| Monitors run on jittered per-monitor intervals and queue behind one account gate | `EnginePolicyTest`, `MonitorEngineTest`, `AccountClientRegistryTest`; issue #35 closed |
| Slot filtering is pure, Warsaw-local, and shared | `SlotFilterTest` |
| A slot is notified at most once per monitor, enforced by the database | `MonitorEventRepoTest`, `MonitorCheckTest` |
| The §5.5 failure policy holds | `EnginePolicyTest`, `MonitorEngineTest`; browser scenario 7 |
| Restart and reconciliation resume the active set; new monitors start without a restart | `MonitorEngineTest`; browser scenarios 2, 7 |
| Telegram linking works through a one-time `/start <code>` deep link over long polling | `TelegramLinkServiceTest`, `TelegramLinkPollerTest`, `TelegramBotTest`; browser scenario 5 |
| A user without Telegram gets events and a visible warning | `NotificationServiceTest`, `UpdateTest`; browser scenario 4 |
| The list shows a last-check summary and the detail view the event log | `MonitorHttpApiTest`, `UpdateTest`; browser scenarios 3, 6 |
| A `failed` monitor can be resumed manually | `MonitorServiceTest`, `MonitorHttpApiTest`, `UpdateTest`; browser scenario 7 |
| All gates pass with no required test skipped or excluded | `verification-run` and `skipped-checks` above |

## Deliberately out of scope

- **Auto-booking** (Plan 6). The `booking_*` event kinds and the check result it
  will consume exist; nothing books yet.
- **Editing Luxmed credentials.** An `auth_failed` account is recovered by
  deleting and re-linking it; a credential-edit flow is Plan 7 material.
- **Event retention.** `monitor_events` grows append-only; pruning is an ops
  concern for Plan 7.
- **Live status push.** tapir-jdkhttp has no websockets; the UI refreshes when
  the user reloads or acts.

## Known rough edges

- The acceptance engine caps waits at 200 ms, so a browser run exercises the
  control flow, not the real cadence; the cadence itself is unit-tested.
- `Plan4AcceptanceConfig.StubLuxmedServer` now serves terms as well as the auth
  and dictionary routes, so the Plan 4 harness can drive a running monitor too.
- The settings page has no password-change section yet; that is Plan 7.
