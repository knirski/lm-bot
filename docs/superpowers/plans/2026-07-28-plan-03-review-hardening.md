# Plan 3 Tasks 1–5 Review Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox syntax.

**Goal:** Make the existing Tasks 1–5 Luxmed foundation safe, Gears-native, and independently reviewable without implementing Tasks 6–10.

**Architecture:** Keep the existing transport/client/store boundaries. Move account serialization and request spacing into Gears primitives owned by `AccountGate`; make the client refresh transaction explicitly resumable through its existing closed state ADT; harden only transport diagnostics and cookie parsing.

**Tech Stack:** Scala 3.8.4, Gears 0.3.1, sttp 3.11.0, jsoniter-scala, MUnit, JDK 25, sbt 2.

## Global Constraints

- Tasks 6–10 remain out of scope: no dictionaries, terms search, XSRF, reservation primitives, guided explorer, or completion claim.
- Gears is the only async vocabulary in production code; do not add `scala.concurrent` imports outside the existing frontend bridge.
- Expected failures remain typed `Either[LuxmedError, A]`; one public operation may perform at most one password grant.
- Refresh tokens are single-use and rotating; never retry a consumed refresh token.
- Secrets must not appear in `toString`, diagnostics, or error values.
- Run tests with `testFull`, format with `scalafmtAll`, and finish with `nix flake check`.
- Preserve the five existing task commits; add focused hardening commits.

---

### Task 1: Make account serialization and pacing Gears-native

**Files:** modify `AccountGate.scala`, `AccountGateTest.scala`, and `FakeTime.scala`.

**Interfaces:** Keep `AccountGate.serialized[A](body: AccountGatePermit ?=> A)(using Async): A`, `Sleeper.sleep`, and `AccountGatePermit.beforeRequest`. Use `java.time.Duration` in production so the async-vocabulary gate has no new `scala.concurrent` matches.

- [ ] Add a failing test that performs a request in one serialized operation, advances fake time by 200 ms, then performs the first request of a second operation with 1-second spacing; assert an 800 ms sleep.
- [ ] Run `nix develop -c sbt "backend/testOnly lmbot.backend.luxmed.AccountGateTest"` and confirm the new test fails because the timestamp is currently per permit.
- [ ] Replace JDK `Semaphore` with `gears.async.Semaphore(1)`, await its guard under `Async`, and release it in `finally`. Move `lastRequestAt` to the gate. Implement the default sleeper with `gears.async.JvmAsyncOperations.sleep(duration.toMillis, summon[Async])`.
- [ ] Re-run the focused suite and `grep -rn --include='*.scala' 'scala\.concurrent' shared/src/main backend/src/main frontend/src/main | grep -v '/bridge/'`; expect tests green and grep empty.
- [ ] Commit with `git commit -m "fix: use Gears for Luxmed account pacing"`.

---

### Task 2: Make refresh and persistence recovery resumable

**Files:** modify `LuxmedClient.scala`, `LuxmedClientAuthTest.scala`, and `SessionStoreTest.scala`.

**Interfaces:** Add `now: () => Instant = () => Instant.now()` to `LuxmedClient`; keep public return types and the existing four-state ADT unchanged.

- [ ] Add failing tests for 301 seconds (no refresh) and 300 seconds (one refresh), using the injected fake clock.
- [ ] Add a store that returns `Unavailable` once, then succeeds. Assert a failed authentication enters pending persistence and the next call retries only the store, with no HTTP request.
- [ ] Add a bootstrap-failure-after-refresh test. On the next call provide only bootstrap responses and assert the old refresh token is never sent again.
- [ ] Add a refresh-token-rejection test that queues one password grant and bootstrap, then asserts one password fallback and stale-store clearing.
- [ ] Add a fresh-client test with a pre-populated non-expiring store session; assert `withSession` loads it and sends no password grant.
- [ ] Run `nix develop -c sbt "backend/testOnly lmbot.backend.luxmed.LuxmedClientAuthTest"`; confirm the new tests fail before production changes.
- [ ] After red, set `PendingBootstrap` immediately after each successful OAuth grant, set `PendingPersistence` after bootstrap, and publish `Ready` only after CAS success. On `Unloaded`, load the store first. On refresh rejection, clear the stale store and permit one password fallback. Use `Duration.between(now(), expiresAt).getSeconds <= 300`.
- [ ] Run the auth- and store-focused suites, then commit with `git commit -m "fix: resume rotated Luxmed sessions safely"`.

---

### Task 3: Harden cookie parsing and secret diagnostics

**Files:** modify `LuxmedTransport.scala`, `CookieJar.scala`, `WireCodecTest.scala`, and `ErrorClassificationTest.scala`.

**Interfaces:** Keep `TransportResponse`, `CookieJar`, and `Secret` method signatures stable.

- [ ] Add a failing cookie test for `Set-Cookie: token=abc==; Path=/` and assert the stored value is `abc==`.
- [ ] Add failing rendering/error tests seeded with access token, refresh token, JWT, cookie, email, and phone values; assert none occur in `TransportResponse.toString` or typed error details.
- [ ] Run the focused codec and classification suites and confirm the new tests fail.
- [ ] Parse cookies at the first equals sign and preserve the complete remaining value. Keep names case-insensitive and update documentation to describe only the information actually passed to `CookieJar.merge`.
- [ ] Override `TransportResponse.toString` to expose only status, header names, cookie names, and bounded redacted body text. Extend summaries to redact authorization headers, JWT/cookie fields, both OAuth token spellings, email, and phone-like values. Do not place raw response headers/body in error details.
- [ ] Run the focused suites and commit with `git commit -m "fix: redact Luxmed transport diagnostics"`.

---

### Task 4: Pin the authentication wire contract

**Files:** modify `LuxmedClientAuthTest.scala`; change production code only if a new assertion proves it necessary.

- [ ] Add raw-request assertions for `POST /PatientPortalMobileAPI/api/token`, login query `app=search&client=3&lang=pl`, and reservation page path.
- [ ] Assert form-encoded `grant_type=password`, `client_id=Android`, username, and password; raw access-token `Authorization`; `X-Requested-With: pl.luxmed.pp`; and cookie propagation to the reservation-page request.
- [ ] Run the focused test and confirm the new assertions fail or expose an unpinned behavior.
- [ ] Make only the minimum transport/client correction required, preserving verified endpoints and raw access-token semantics.
- [ ] Run the focused test and commit with `git commit -m "test: pin Luxmed authentication requests"`.

---

### Task 5: Verify, document, review, and create the PR

**Files:** modify `docs/superpowers/reports/2026-07-28-plan-03-progress.md`.

- [ ] Run `nix develop -c sbt testFull`, both scalafmt checks, `nix flake check`, `git diff --check`, and the quoted async-vocabulary grep. All must pass; no tests may be skipped or renamed.
- [ ] Update the report to say Tasks 1–5 plus review hardening are complete, remove resolved risks, list the new tests and verification, and retain Tasks 6–10 as deferred. Do not claim live conformance or dictionary/reservation support.
- [ ] Inspect `git diff origin/main...HEAD --stat`, `git diff origin/main...HEAD --check`, and `git status --short`; ensure the report is tracked.
- [ ] Request a code review using the final branch diff.
- [ ] Push `feat/luxmed-client` and create a PR titled `feat: add Luxmed client foundation`, explicitly describing the Tasks 1–5 scope and deferred follow-ups.
