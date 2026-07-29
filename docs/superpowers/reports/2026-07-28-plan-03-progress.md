# Plan 3 Progress: Luxmed Client Foundation

**Date:** 2026-07-28
**Spec:** [PRD](../specs/2026-07-27-lm-bot-prd-design.md)
**Plan:** [Implementation Plan](../plans/2026-07-28-lm-bot-03-luxmed-client.md)
**Branch:** `feat/luxmed-client`

## Scope

This branch implements the original Plan 3 Tasks 1–5 and the review hardening
needed to make those tasks a reasonable first PR. Tasks 6–10 remain deferred:
dictionary queries, terms and reservation operations, expanded error-matrix
coverage, live/mock conformance exploration, and the final completion report.

| Task | Deliverable | Status |
|---|---|---|
| 1 | Luxmed configuration and compile-time sttp dependency | ✅ |
| 2 | Wire models, codecs, and literal JSON fixtures | ✅ |
| 3 | Mock server, typed `LuxmedError`, and transport classification | ✅ |
| 4 | Per-account Gears serialization and request pacing | ✅ |
| 5 | CAS `SessionStore` and rotating-session client state machine | ✅ |

## Review hardening included

- `AccountGate` uses `gears.async.Semaphore` and `JvmAsyncOperations.sleep`.
  Request spacing is gate-wide, not reset for each serialized permit.
- Refresh and bootstrap transitions use the existing closed state ADT. The
  client injects its clock for deterministic expiry boundaries, resumes a
  failed persistence step without another HTTP grant, loads stored sessions on
  a fresh client, and never reuses a consumed refresh token after bootstrap
  failure.
- A rejected refresh response, including Luxmed’s “logged out due to
  inactivity” response, clears stale state and permits one password fallback.
- Cookie parsing preserves values containing `=` and emits a correctly named,
  merged `Cookie` header.
- Transport diagnostics expose only bounded redacted summaries. Access,
  refresh, JWT, cookie, authorization, email, and phone-like secrets are not
  rendered in response diagnostics or typed error details.
- Authentication tests pin the raw method, path, query, form body, content
  type, access-token header, `X-Requested-With`, and reservation-page cookie.
  The tests exposed a URI bug where dynamic slashes and query parameters were
  being percent-encoded into the path; the transport now builds structured
  paths and parameters.

Production Scala uses Gears for asynchronous control flow. The required
production-source check for `scala.concurrent` outside the frontend bridge is
empty. Expected failures remain `Either[LuxmedError, A]`; no exception-based
error channel was added.

## Session state

```text
Unloaded → load or password grant → PendingBootstrap
PendingBootstrap → bootstrap → PendingPersistence
PendingPersistence → CAS replace → Ready
Ready → expiring at ≤300 seconds → refresh and bootstrap
Ready → SessionExpired → clear stale state → one password fallback
```

Refresh tokens are treated as rotating, single-use credentials. The client
does not retry a failed refresh grant with the same token.

## Test coverage

`nix develop -c sbt testFull` passed with **113 tests**, including:

- 6 `AccountGateTest`
- 19 `ErrorClassificationTest`
- 13 `LuxmedClientAuthTest`
- 12 `WireCodecTest`
- 7 `SessionStoreTest`
- the existing shared, frontend, backend, repository, API, password, and
  configuration suites

The tests use the embedded PostgreSQL configured by the repository and the
queued JDK mock server for Luxmed wire behavior. No live Luxmed conformance
claim is made by this PR.

## Deferred work

The following are intentionally not part of this first PR:

- dictionary, service, facility, doctor, and terms-search client operations;
- XSRF acquisition and reservation lock/confirm/release operations;
- broader error-matrix coverage and live/mock conformance exploration;
- completion report and production wiring for the later Plan 3 tasks.

## Commit sequence

The five original task commits are preserved, followed by focused review and
documentation commits:

```text
fdfd976 build: prepare Luxmed client configuration
e7f8958 feat: model Luxmed wire contracts
fe70163 feat: add typed Luxmed transport
53fb36c feat: serialize and pace Luxmed requests
d7bb153 feat: manage rotating Luxmed sessions
ce77d9f docs: define Plan 3 review hardening
de298be docs: plan Plan 3 review hardening
9e5394d fix: use Gears for Luxmed account pacing
17d1632 fix: resume rotated Luxmed sessions safely
0d24c71 fix: redact Luxmed transport diagnostics
2bde2ce fix: pin Luxmed authentication wire contract
```
