# Plan 4 Completion Report: Luxmed Accounts & Monitor CRUD

**Date:** 2026-08-01
**Plan:** [`2026-07-30-lm-bot-04-accounts-monitors.md`](../plans/2026-07-30-lm-bot-04-accounts-monitors.md)
**Spec:** [`2026-07-27-lm-bot-prd-design.md`](../specs/2026-07-27-lm-bot-prd-design.md)
**Mid-plan review:** [`2026-07-30-plan-04-review.md`](2026-07-30-plan-04-review.md) (Tasks 1–5)

**changed-files:** Task 11 added `backend/src/test/scala/lmbot/backend/Plan4AcceptanceApp.scala`, `backend/src/test/scala/lmbot/backend/Plan4AcceptanceConfig.scala`, this report, and updated `README.md` and `docs/superpowers/plans/2026-07-27-lm-bot-roadmap.md`. The plan as a whole added the accounts/monitors migration, `AccountRepo`/`MonitorRepo`/`SessionRepo`, `AesGcm` envelopes, `PostgresSessionStore`, `AccountService`/`DictionaryService`/`MonitorService`, `AccountRoutes`/`DictionaryRoutes`/`MonitorRoutes`, the shared `AccountEndpoints`/`DictionaryEndpoints`/`MonitorEndpoints` contracts, and the Laminar account and monitor UI.
**verification-run:** `sbt scalafmtAll`, `sbt scalafmtCheckAll`, `sbt scalafmtSbtCheck`, `sbt testFull` (0 failed, 0 errors, 1 skipped across sharedJVM 32, sharedJS 32, frontend 82, backend 278 — sbt's own sum is 424; this report counts 392, treating the cross-compiled `shared` suite as one 32-test suite rather than two, since sharedJVM and sharedJS run the identical source set), `sbt frontend/fastLinkJS` (Wasm + JSPI), `nix flake check` (all checks passed), async-vocabulary grep (no output), `git diff --check` (no output), plus a ten-scenario real-browser run against `Plan4AcceptanceApp` in Chromium 150.
**skipped-checks:** none. One test skips itself: `PostgresSessionStoreTest."concurrent replacements allow exactly one CAS winner"` calls `assumeRealPostgres()` and is skipped unless `EMBEDDED_DB=zonky`, because Memgres does not guarantee the row-level locking the assertion is about. This is pre-existing and by design, not an exclusion added for this plan.
**branch:** `feat/plan-04-complete`
**pr:** not yet opened — the branch is committed and awaiting whole-branch review before push.
**blocker:** none.

## What Plan 4 delivered

A running app in which an operator signs in, links a Luxmed account, and manages
appointment monitors — end to end, through the shared Tapir contract and a real
browser.

| Area | Delivered |
|---|---|
| Encryption at rest | `AesGcm` AES-256-GCM envelopes bound to `(ownerId, accountId, purpose)`; username, password, device UUID, and the complete Luxmed session are each encrypted under their own purpose |
| Session persistence | `PostgresSessionStore` with compare-and-set on the rotating refresh token; restart → proactive refresh → rotated-token persistence, with no password grant |
| Linking | Single-step link verified by a live Luxmed login; at most one password grant per attempt (the duplicate-label check runs first); no partial account on failure |
| Account state | `active` / `auth_failed` / `disabled` with a reason string, so a challenge or a lockout is never reported as "wrong password" |
| Dictionaries | Owner-scoped proxy for cities, service variants, and facilities/doctors; no Luxmed wire model crosses the service boundary |
| Monitors | `monitors` table, create/edit/pause/resume/delete with ownership enforced in `MonitorService`, Warsaw-time semantics, 10-minute default and 5-minute floor |
| UI | Account list and link form; a single-page monitor form (see below) and the monitor list, with explicit confirmation before either delete |

### Not delivered, and out of scope

Monitors are **stored definitions only**. Nothing runs them: there is no check
loop, no slot query on a schedule, no `monitor_events` history, no last-check
summary, and no notification of any kind — that is Plan 5. Nothing books —
that is Plan 6. The monitor list deliberately shows no "last checked" field for
this reason.

### One deliberate departure from the plan text

Task 10 was written as a six-step guided wizard. The project owner asked instead
for a single-page form — every field visible at once, one submit button — which
is what shipped; the plan document and the roadmap now describe the form. The
dictionary-loading rules are unchanged by this: choosing an account loads cities
and services together, and a city plus a service (in either order) loads
facilities and doctors.

## The acceptance harness (Task 11)

`Plan4AcceptanceApp` is a **test-scope** main that starts the ordinary
composition graph from `Main` — embedded database and Flyway migrations, admin
bootstrap, Argon2 hashing, AES-GCM crypto, every repository and service, the
whole Tapir endpoint list, and the linked frontend served from the classpath —
and substitutes only the owned Luxmed HTTP boundary. It is unreachable from the
production artifact: it lives in `src/test`, the assembly declares one main
class, and `Config` has no "mock Luxmed" switch that could redirect a deployed
server.

```bash
sbt "backend/Test/runMain lmbot.backend.Plan4AcceptanceApp"
```

`Plan4AcceptanceConfig` holds every fixed input — the acceptance operator's
credentials, the Luxmed link-form credentials, a fixed master key, and free-port
discovery for the app, the control server, and the database. The credentials
are not secrets; the master key is genuine AES-256-GCM key material, but a
fixed, test-only one that must never be reused outside this harness. None of
it reaches `backend/src/main`.

**How the boundary is substituted.** The plan text named
`LuxmedTransport.withBackend` with `StubLuxmedBackend`. Two things made that
literal approach unworkable, so the harness substitutes the boundary the way
`AccountHttpApiTest` and `DictionaryServiceTest` already do — by pointing
`LuxmedConfig` at a loopback HTTP server:

1. `LuxmedTransport.withBackend` is `private[luxmed]` and
   `AccountClientFactory`'s constructor is private, so no code outside
   `lmbot.backend.luxmed` can inject a backend into the app graph without
   widening production visibility. Pointing the base URIs at loopback needs no
   production change at all.
2. `StubLuxmedBackend` answers from a FIFO queue. A browser decides how many
   Luxmed calls happen and in what order — the monitor form asks for cities and
   services concurrently, and every reload or retry asks again — so a queue
   would hand a service list to a city request. The harness stub therefore
   **routes by request path**, while still using `LuxmedResponseScripts` for the
   auth flow and the committed `backend/src/test/resources/luxmed/*.json`
   fixtures for the dictionaries.

The stub also counts what it was asked for, which is what the restart/refresh
gate reads.

## Browser acceptance run

Chromium 150.0.7871.186, driven by `agent-browser` against
`Plan4AcceptanceApp`. All ten scenarios passed. Screenshots of the account page,
the monitor form, and the monitor list were captured and reviewed; they contain
no secrets and no Luxmed payloads (there are none to leak — every Luxmed
response came from the loopback stub).

| # | Scenario | Result |
|---|---|---|
| 1 | Sign in as the bootstrapped operator | Dashboard reached; empty states read "No accounts linked yet." and "Link a Luxmed account before creating a monitor." |
| 2 | Link one Luxmed account | Row appears with status **Active** and a last-login timestamp; exactly **1** password grant spent |
| 3 | Reload | Account still listed, status still Active, same timestamp — read back from the encrypted row |
| 4 | Create a monitor on the single-page form | Name, account, city (Białystok), service (`On-site consultations > Allergologist consultation`), one clinic, one doctor, 2026-08-10→2026-08-31, 08:00–12:30, Mon+Wed, 15-minute interval. Interval field defaulted to **10**; submitting with **4** was refused with "Check no more often than every 5 minutes."; submitting with no dates/times/days listed all three missing answers |
| 5 | Reload | Every criterion renders from the persisted row, unchanged |
| 6 | Edit the time window and interval | 07:30–11:00 and 20 minutes; saved, and still correct after a reload. The edit form opened pre-populated from the stored monitor |
| 7 | Pause, then resume | State went Active → Paused → Active, each transition surviving a reload |
| 8 | Cross-owner API access | As a second operator owning nothing: `GET/PUT/DELETE /api/monitors/1`, `POST /api/monitors/1/pause`, `.../resume`, `DELETE /api/accounts/1`, both `/api/accounts/1/dictionaries/*`, and `POST /api/monitors` naming the other owner's account — **all 404**. `GET /api/monitors` returned 200 with an empty list. The victim's monitor was untouched |
| 9 | Delete the account, with confirmation | "Delete" alone deleted nothing: it showed "Deleting this account will also delete its monitors."; Cancel left both account and monitor intact through a reload; Confirm delete removed the account **and cascaded to its monitor**, and both stayed gone after a reload |
| 10 | Console and network | **No console messages at all** in any browser session. No 5xx anywhere. Two expected non-2xx: `GET /favicon.ico` 404 (no favicon is shipped) and the first `GET /api/auth/me` 401 before sign-in, which is the "no session" boot probe |

One harness artifact worth recording, because it looked like an app fault and
was not: after issuing a batch of raw `fetch` probes from the page without
reading their response bodies, that browser tab stalled on subsequent requests
while `curl` against the same endpoints answered in 10–16 ms. A fresh browser
session against the same running server behaved correctly. The cause was the
undrained response bodies holding HTTP/1.1 connections in the tab, not the
server.

## Restart / refresh release gate

Re-run as a release gate, not treated as optional coverage.

The gate is carried by an automated test, not by the harness step below:
`PostgresSessionStoreClientTest."a new client refreshes a persisted session
without a password grant"` constructs a client from nothing but the encrypted
row and a clock past the boundary — the same starting state a restarted
process would have — refreshes, and reads the rotated refresh token back out
of the database. Runs in `sbt testFull`.

The composition-level walk below **corroborates** that same behaviour end to
end through a real browser and the full app graph; it is not independent proof
on its own, because `AccountClientFactory.forStored` already builds a fresh
`LuxmedClient`/`PostgresSessionStore` per request in production — there is no
in-memory Luxmed session cache anywhere in the request path for a restart to
invalidate. The same refresh would fire on any post-boundary request whether
or not the graph were rebuilt; rebuilding it is what makes this step exercise
the *whole* app (routes, services, repos) rather than just the store-and-client
layer, not what makes the refresh-without-a-password-grant property true.

Through the harness:

1. After linking, `GET /status` reported `passwordGrants: 1`,
   `refreshGrants: 0`, `sessionPersisted: true`.
2. `POST /restart?advanceSeconds=301` tore down the whole composition graph and
   rebuilt it on the same port with its Luxmed-facing clock moved 301 seconds
   past the mint time — one second past the 300-second proactive-refresh
   boundary for the stub's 600-second tokens. Every service, repository, and
   client factory is new; the database keeps its rows, so the new graph starts
   from exactly what a restarted process would read: an encrypted persisted
   session and nothing else. (The app's own login sessions keep the wall clock,
   so the browser stayed signed in — the two clocks answer different
   questions.)
3. One dictionary call from the browser then drove the refresh. Afterwards:
   `passwordGrants: 1` (**unchanged** — no password was spent),
   `refreshGrants: 1`, and `rotationPersisted: true`, meaning the refresh token
   now stored in the database is the one the stub minted during the refresh.
   Token values are compared inside the app and never rendered.
4. Opening the monitor form after the refresh loaded 3 cities and 5 services
   with no error and **no further grant of either kind**.

Because a JVM restart would take the in-memory embedded database with it, the
harness restarts the composition graph rather than the process — which is the
same starting state a restarted process has when it points at an external
database, and is the same "brand-new client, same rows" premise the automated
test asserts on. A future run with `EMBEDDED_DB=zonky` against a persistent data
directory could make it a literal process restart.

## Plan 4 completion criteria

| Criterion | Evidence |
|---|---|
| Credentials, device identity, and complete sessions encrypted with record/purpose-bound AES-256-GCM envelopes | `AesGcmTest`, `AccountRepoTest`, `PostgresSessionStoreTest`, `SessionCodecTest` |
| Restart → proactive refresh → rotated-token persistence without a password grant | `PostgresSessionStoreClientTest`; harness gate above |
| Linking creates no partial account and uses at most one password grant | `AccountServiceTest`, `AccountHttpApiTest` (422/409 paths never contact Luxmed) |
| Every account, dictionary, and monitor operation enforces owner scope in a service | `AccountServiceTest`, `DictionaryServiceTest`, `MonitorServiceTest`, `MonitorHttpApiTest`; browser scenario 8 |
| Monitor validation enforces Warsaw semantics, a 10-minute default, and a 5-minute floor | `MonitorServiceTest`, `UpdateTest`; browser scenario 4 |
| Account and monitor CRUD work through the shared Tapir contract and the browser | `CodecRoundTripTest`, `AccountHttpApiTest`, `MonitorHttpApiTest`; browser scenarios 2–7 |
| Account deletion explicitly confirmed and cascades to monitors | `AccountHttpApiTest`, `UpdateTest`; browser scenario 9 |
| A real browser completes the end-to-end flow without console errors | Ten scenarios above; console empty throughout |
| All verification gates pass with no required tests skipped or excluded | See **verification-run** and **skipped-checks** |

## Known rough edges, for later plans

- **No stylesheet.** The UI is unstyled browser default. Layout, responsive
  behaviour, and the accessibility pass are Plan 7's scope; every control is
  reachable and labelled, but nothing is designed.
- **No favicon**, so every page load logs a 404 for `/favicon.ico`.
- **The per-account Luxmed mutex and minimum call spacing are not actually held
  across requests.** `AccountClientFactory.forStored` builds a brand-new
  `LuxmedClient` and `AccountGate` on every call
  (`AccountClientFactory.scala:40,56`), so `AccountGate`'s `Semaphore(1)` and
  `minimumSpacing` only serialize and pace calls *within one request* — never
  across the several concurrent requests one browser session can now issue
  against the same account (the single-page monitor form's `loadDictionaries`
  fires up to three simultaneous Luxmed calls per "Edit" click). This is
  narrower than spec §5.4's "one in-flight request per Luxmed account, minimum
  spacing between calls" and the fair-use-lockout mitigation it exists for
  (spec §10) — the invariant those describe is not currently held at all
  across requests, not merely raced at one boundary. The concrete symptom seen
  so far is benign — two calls landing at the refresh boundary can both
  attempt a refresh; the CAS loser surfaces "Luxmed is temporarily unavailable"
  with a retry, and the rotated token is never lost, which is what the CAS
  exists to guarantee — but that is one consequence, not the whole gap. Fixing
  it means giving a Luxmed account's client and gate a lifetime longer than one
  request, which is exactly the shape of the per-account scheduler Plan 5
  needs anyway (see the tracking issue). Left for Plan 5 rather than fixed in
  Plan 4, deliberately: Plan 4 doesn't execute any recurring Luxmed calls of
  its own, so nothing in this plan depends on the invariant actually holding
  yet.
- **No user management.** A second operator can only be created by the
  acceptance harness; the admin UI is Plan 7.
