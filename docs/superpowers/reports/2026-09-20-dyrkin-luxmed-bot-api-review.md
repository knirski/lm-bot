# Dyrkin/luxmed-bot API Access Review

**Date:** 2026-09-20
**Upstream:** [dyrkin/luxmed-bot](https://github.com/dyrkin/luxmed-bot) at
`a5b7abc83389e718651ce8c6dbb005e72ae694b0` (2026-09-03, current `master`)
**Related:** [Luxmed API analysis, 2026-07-27](2026-07-27-luxmed-api-analysis.md),
PRD §5.4
**Method:** source inspection of a fresh clone (with full git history), the
upstream issue/PR tracker, and the Google Play listing. No live Luxmed requests
were made for this review.

## 1. How upstream accesses the API

`dyrkin/luxmed-bot` is a hybrid client: it logs in through the **old mobile
API**, then performs everything else through the **web `NewPortal` API**.

Base URLs (`api/src/main/scala/com/lbs/api/ApiBase.scala`):

- old: `https://portalpacjenta.luxmed.pl/PatientPortalMobileAPI/api`
- new: `https://portalpacjenta.luxmed.pl/PatientPortal`

### 1.1 Authentication (three steps, then XSRF on demand)

1. `POST {old}/token`, form-encoded
   `client_id=<random UUID>, grant_type=password, username, password`.
   Headers: `X-Api-Client-Identifier: Android`, `User-Agent: okhttp/4.9.0`,
   `Custom-User-Agent: Patient Portal; 5.8.0; {static uuid}; Android; 33;
   Samsung Galaxy S23`. Response cookies are retained (the WAF cookies matter
   downstream). Upstream sends a random UUID as the form `client_id`
   (`ApiService.scala:227`); lm-bot sends `Android`. Both are accepted — the
   value is not validated.
2. `GET {new}/Account/LogInToApp?app=search&client=3&lang=pl` with
   `Authorization: <accessToken>` (raw, **no** `Bearer` prefix),
   `X-Requested-With: pl.luxmed.pp`, and the merged cookies plus an injected
   `GlobalLang=pl`.
3. `GET {new}/NewPortal/Page/Reservation` to harvest the `Authorization-Token`
   JWT. Upstream searches, in order: cookies → `LogInToApp` response header →
   reservation-page response header → regex over the page body → fall back to
   the OAuth access token. The whole login is retried once after 3 s because
   the token is sometimes missing on the first attempt.

`Session = (accessToken, tokenType, jwtToken, cookies)`. Old-API calls send
`Authorization: bearer <accessToken>`; `NewPortal` calls send
`authorization-token: Bearer <jwt>` plus the cookie jar.

### 1.2 Session lifecycle

There is **no refresh grant**: `refresh_token` is decoded and never used. On
`SessionExpiredException`, `SessionSupport.withSession` drops the stored
session and performs a full password login again, under a per-account mutex.
There is no request spacing or rate limiting of any kind.

### 1.3 Endpoints in use

| Surface | Endpoint | Use |
|---|---|---|
| old | `token` | password grant |
| old | `Events`, `DELETE events/Visit/{id}` | history, cancel reservation |
| new | `Account/LogInToApp` | bootstrap |
| new | `NewPortal/Page/Reservation` | JWT harvest |
| new | `security/getforgerytoken` | XSRF token |
| new | `NewPortal/Dictionary/{cities,serviceVariantsGroups,facilitiesAndDoctors}` | dictionaries |
| new | `NewPortal/terms/index`, `NewPortal/terms/oneDayTerms` | search |
| new | `NewPortal/reservation/{lockterm,releaseterm,confirm,changeterm}` | mutations |
| new | `NewPortal/{Referrals/module,Rehabilitation/*,RehabilitationCart/StartSession}` | rehab (added 2026-08-22) |

Lock/confirm/release/changeterm send the `xsrf-token` header with session
cookies merged with the cookies returned by the XSRF call.

## 2. Changes since `c970447b` (the commit lm-bot's client was ported from)

| Date | Commit | Change |
|---|---|---|
| 2026-06-10 | `c970447b` | (pinned by lm-bot) null-doctor NPE fix |
| 2026-08-22 | `f258e0a` (#119) | **`Custom-User-Agent` app version `4.42.0` → `5.8.0`** on both APIs; rehab flows and `terms/index` params |
| 2026-08-22 | `a840461` | test fixture fix only |
| 2026-09-03 | `a5b7abc` (#120) | **30 s connection + 30 s read timeouts**; Pekko dispatcher moved to a fixed 64-thread pool |

Nothing has changed in the core auth sequence, endpoint paths, XSRF flow, or
request/response shapes. `master` has had no commit since 2026-09-03.

## 3. The August 2026 version-floor move

Luxmed raised its enforced minimum app version again:

- [Issue #116](https://github.com/dyrkin/luxmed-bot/issues/116) (2026-08-04):
  multiple users' bots failed at **booking acceptance** with
  `Obecnie zainstalowana wersja aplikacji nie jest wspierana przez nowy system
  Portalu Pacjenta…`, while running `4.42.0`. The reporter noted it is a
  recurrence of #89 (March 2025).
- [Issue #118](https://github.com/dyrkin/luxmed-bot/issues/118) (2026-08-17)
  shows the same message on the `NewPortal` API for a self-hosted instance.
- [PR #117](https://github.com/dyrkin/luxmed-bot/pull/117) (2026-08-06)
  proposed exactly the `4.42.0 → 5.8.0` header bump but was closed unmerged on
  2026-08-28. The same change landed through PR #119 on 2026-08-22, and #116
  was closed that day with the reporter confirming the fix.
- As of 2026-09-20, upstream has had no further version-rejection reports;
  `5.8.0` is the production-verified value. The Play listing is already on
  `5.11.0` (released 2026-09-10), so the floor will likely move again.

The failure hit reservation-mutating `NewPortal` calls while the password grant
still worked, so `POST /token` succeeding is not evidence that booking works.

## 4. Implications for lm-bot

1. **Default app version bumped to `5.8.0`.** lm-bot's default was `4.44.0`,
   measured during the July spike. Luxmed's floor is known to be above
   `4.42.0`; whether it is above `4.44.0` is **not verified**. `5.8.0` is the
   only value with production evidence at this shape set, so it is the default;
   `AppVersion` now rejects anything below it at config-parse time. The current
   store version (`5.11.0`) is a candidate for the next bump once verified.
2. **Shapes and endpoints remain valid.** The port from `c970447b` needs no
   revisit; only rehab endpoints were added upstream, and rehab is out of scope.
3. **Read-timeout gap.** Upstream added explicit timeouts in September because
   stalled requests starved its thread pools. lm-bot's transport sets a 15 s
   connect timeout but no per-request read timeout; with per-account
   serialization, one stalled request blocks that account. This mirrors
   upstream's 30 s choice and should be added.
4. **MFA unchanged.** Upstream issue #113 is still open with no comments and no
   solution, consistent with the spike's finding that the mobile API does not
   challenge.
5. **Detection works.** lm-bot classifies the exact error string from #116/#118
   as `LuxmedError.VersionRejected`, so a future floor move surfaces as a typed
   error rather than a mystery.

## 5. Unverified / follow-ups

- Whether `4.44.0` would still be accepted; only `5.8.0` and above are known-good.
- Whether sending the current store version (`5.11.0`) changes server behaviour
  versus `5.8.0`.
- No live conformance run against the real API was performed for this review;
  the next real-API run should confirm the new default end-to-end (including
  lock/release, which was last exercised live on 2026-07-28).
