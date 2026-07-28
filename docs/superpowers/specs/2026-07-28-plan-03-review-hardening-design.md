# Plan 3 Tasks 1–5 Review Hardening Design

**Date:** 2026-07-28

**Scope:** Harden the already implemented Plan 3 Tasks 1–5 slice into a
self-contained first PR. Tasks 6–10 remain explicitly out of scope.

## Goal

Make the authentication/session foundation safe to extend: Gears owns account
serialization and pacing, rotated refresh tokens survive bootstrap and local
store failures, one bounded password fallback handles a rejected refresh, and
transport diagnostics cannot reveal credentials.

## Design

`AccountGate` will use the published Gears `gears.async.Semaphore` and Gears'
JVM sleep primitive. The gate owns the last-request timestamp, so the minimum
spacing applies across all serialized operations for an account. The clock and
sleeper remain injectable for deterministic tests; production code will not
name `scala.concurrent`.

`LuxmedClient` will keep its closed session-state ADT, but transitions become
explicit. A successful OAuth refresh is immediately stored as
`PendingBootstrap`; successful bootstrap becomes `PendingPersistence`; only a
successful compare-and-set publishes `Ready`. A bootstrap or store failure
therefore retries only the incomplete phase and never reuses the consumed
refresh token. A Luxmed rejection of the refresh token clears the stale store
and permits one password-grant fallback. Session loading is performed when the
client is initially unloaded.

The expiry clock will be injected into `LuxmedClient`, and the threshold will
refresh at exactly 300 seconds remaining. Transport response rendering and
error summaries will redact response bodies, authorization headers, cookie
values, OAuth tokens, JWTs, email addresses, and phone-like values. Cookie
parsing will split only at the first equals sign so padded/base64 values remain
intact.

## Tests

Tests will be added before each production change for:

- pacing across two serialized operations;
- the exact 301/300-second refresh boundary;
- pending bootstrap retry without a second refresh grant;
- pending persistence retry without another HTTP request;
- one password fallback after refresh-token rejection;
- loading an existing stored session;
- auth request paths, form body, headers, and merged cookies;
- response/error rendering that excludes seeded secrets;
- cookie values containing equals signs.

The existing codec, classification, backend, shared, frontend, formatting,
and flake checks remain required. No dictionary/search/reservation APIs or live
Luxmed exploration will be added in this PR.

## Integration

The existing five task commits will remain intact. Review hardening will be
added as focused follow-up commits on `feat/luxmed-client`; the first PR will
contain Tasks 1–5 plus this hardening only. The progress report will be updated
after verification to state the exact completed scope and remaining tasks.
