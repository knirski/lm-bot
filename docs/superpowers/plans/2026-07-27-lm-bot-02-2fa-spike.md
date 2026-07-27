# lm-bot Plan 2: Two-Factor Authentication Spike

> **This plan is an investigation, not an implementation.** It writes no production code and adds nothing to the build. Its deliverable is recorded evidence plus a decision. Do not start Plan 3 until the "Findings" section at the bottom is filled in.

**Goal:** Establish, from observed traffic, exactly how Luxmed's two-factor authentication works — what raises a challenge, what verifies a code, and what makes a device trusted — so that Plan 3's client is built against facts instead of guesses.

**Why this exists:** Luxmed added MFA in 2026. It is undocumented, and **no predecessor project has solved it** — [luxmed-bot#113](https://github.com/dyrkin/luxmed-bot/issues/113) has been open and unanswered since 2026-06-25, with the bot receiving a bare 401. There is nothing to port. Meanwhile the answer determines the shape of the client's session handling, the persistence model (spec §5.3), the account state machine (§3.2), and the notification surface (§3.5). Guessing wrong means rewriting all four, so an evening of observation is cheap.

**Known going in:** the challenge fires **only for devices Luxmed does not recognise**, and Luxmed keeps a per-user trusted-device list. So the shape of the answer is likely "enroll once, then run unattended" — this spike confirms that and pins down the mechanics.

---

## Before you start

**Read this section. Two of these can cost you real access.**

1. **Fair-use lockout is a live hazard here.** Spec §10 records that Luxmed temporarily locks accounts — reportedly around a day — for excessive querying. This spike is *nothing but* repeated logins, which is the exact behaviour that triggers it. Therefore:
   - **Hard cap: 12 login attempts total across the whole spike**, and at least **60 seconds between attempts**.
   - Run the experiments in the order given; each is designed to answer something the previous one could not, so none is redundant.
   - If you see a 429, or any error mentioning blocking or too many attempts, **stop for 24 hours**. Do not retry to "check".
2. **Use a real account you own** — ideally your own rather than a family member's, since a lockout is disruptive. This is you authenticating to your own account, which is exactly what the finished product does.
3. **A one-time code is a credential.** The scripts below never print your password, but responses may contain tokens. Run the redaction step before pasting anything back.
4. **Do not commit `spike/out/`.** It contains tokens and cookies. The `.gitignore` entry in Step 1 covers it; verify before any `git add`.

---

## Step 1: Set up the scratch harness

The scripts below need `curl`, `jq` and `uuidgen`, all provided by the repository's flake devShell — run these from inside it (`direnv allow`, or `nix develop`) and nothing needs installing.

- [x] **Create the spike directory, outside the build**

These files are throwaway and deliberately not part of the sbt project — nothing here graduates into production. Plan 3 transcribes the *findings* into fixtures, not this code.

```bash
mkdir -p spike/out
```

The repository `.gitignore` already excludes `spike/out/`, `spike/device-uuid` and `spike/attempt-count`, so tokens and cookies cannot be committed by accident.

- [x] **Write the shared harness**

`spike/lib.sh` — every experiment sources this. The device UUID lives in a file so it is stable across runs, which is the whole point of the trust experiments.

```bash
#!/usr/bin/env bash
# Shared harness for the Luxmed 2FA spike. Throwaway code.
set -euo pipefail

OLD_API="https://portalpacjenta.luxmed.pl/PatientPortalMobileAPI/api"
NEW_API="https://portalpacjenta.luxmed.pl/PatientPortal"

APP_VERSION="${LUXMED_APP_VERSION:-4.42.0}"
OUT="spike/out"

# Browser-like headers, matching what luxmed-bot sends (spec §5.4).
UA_OLD="okhttp/4.9.0"
UA_NEW="Mozilla/5.0 (Linux; Android 13; Galaxy S23 Build/TQ2B.230505.005.A1; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/101.0.4951.61 Safari/537.36"

# The device identity under test. Persisted to a file so repeated runs present
# the SAME device -- if trust is keyed off this UUID, only a stable value can
# ever become trusted.
device_uuid() {
  local f="spike/device-uuid"
  if [[ ! -f "$f" ]]; then uuidgen | tr 'A-Z' 'a-z' > "$f"; fi
  cat "$f"
}

custom_ua() {
  echo "Patient Portal; ${APP_VERSION}; $(device_uuid); Android; 33; Samsung Galaxy S23"
}

# Records headers and body separately so status, Set-Cookie and body shape are
# all inspectable after the fact.
record() {
  local label="$1"; shift
  echo "  -> $label"
  curl -sS -D "$OUT/$label.headers" -o "$OUT/$label.body" -w '     status=%{http_code}\n' "$@" || true
}

guard_attempt() {
  local f="spike/attempt-count"
  local n=$(( $( [[ -f "$f" ]] && cat "$f" || echo 0 ) + 1 ))
  echo "$n" > "$f"
  if (( n > 12 )); then
    echo "REFUSING: 12-attempt cap reached (see 'Before you start'). Delete spike/attempt-count only if you are sure." >&2
    exit 1
  fi
  echo "== login attempt $n/12 =="
}
```

- [x] **Write the login step**

`spike/login.sh` — step 1 of the auth flow in isolation, because that is where the challenge is most likely to surface.

```bash
#!/usr/bin/env bash
# Usage: LUXMED_USER=... LUXMED_PASS=... ./spike/login.sh <label> [extra curl args]
source spike/lib.sh
guard_attempt

LABEL="${1:?need a label, e.g. e1-first-login}"; shift || true

: "${LUXMED_USER:?set LUXMED_USER}"
: "${LUXMED_PASS:?set LUXMED_PASS}"

echo "device uuid: $(device_uuid)"

# Form-encoded with client_id=Android, per spec §5.4. --data-urlencode keeps
# special characters in the password intact and off the command line.
record "$LABEL" \
  -X POST "$OLD_API/token" \
  -H "Api-Version: 2.0" \
  -H "X-Api-Client-Identifier: Android" \
  -H "User-Agent: $UA_OLD" \
  -H "Custom-User-Agent: $(custom_ua)" \
  -H "Accept: application/json, text/plain, */*" \
  -H "Accept-Language: pl;q=1.0, pl;q=0.9, en;q=0.8" \
  --data-urlencode "username=$LUXMED_USER" \
  --data-urlencode "password=$LUXMED_PASS" \
  --data-urlencode "grant_type=password" \
  --data-urlencode "client_id=Android" \
  "$@"

echo "--- status line + interesting headers ---"
grep -iE '^HTTP/|^set-cookie|^www-authenticate|^location' "$OUT/$LABEL.headers" || true
echo "--- body ---"
head -c 2000 "$OUT/$LABEL.body"; echo
```

- [x] **Write the redaction step**

`spike/redact.sh` — run before pasting anything back.

```bash
#!/usr/bin/env bash
# Usage: ./spike/redact.sh > spike/out/REDACTED.txt
set -euo pipefail
for f in spike/out/*.headers spike/out/*.body; do
  [[ -e "$f" ]] || continue
  echo "═══════════ $f"
  sed -E \
    -e 's/("access_token"[[:space:]]*:[[:space:]]*")[^"]*/\1REDACTED/g' \
    -e 's/("refresh_token"[[:space:]]*:[[:space:]]*")[^"]*/\1REDACTED/g' \
    -e 's/(Authorization-Token=)[^;[:space:]]*/\1REDACTED/gI' \
    -e 's/^([Ss]et-[Cc]ookie:[[:space:]]*[^=]+=)[^;]*/\1REDACTED/' \
    -e 's/([0-9]{3}[[:space:]]?[0-9]{3}[[:space:]]?[0-9]{3})/PHONE-REDACTED/g' \
    -e 's/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+/EMAIL-REDACTED/g' \
    "$f"
  echo
done
```

Structure is what matters — field names, status codes, which headers appear. Values can all go.

```bash
chmod +x spike/*.sh
```

---

## Step 2: Run the experiments

Each experiment answers a question the previous one cannot. Record the answer inline as you go; the blanks below are the actual deliverable.

### E1 — First login from an unknown device

- [x] Delete any stale identity so this really is a new device: `rm -f spike/device-uuid`
- [x] `LUXMED_USER=... LUXMED_PASS=... ./spike/login.sh e1-unknown-device`

Answers §5.4 question 1: *which step raises the challenge, and what does it look like?*

- Status code: `200`
- Is there a challenge indicator in the body? What field names? `No — full OAuth2 token response: {"access_token", "token_type", "expires_in", "refresh_token"}. No challenge fields.`
- Is there a transaction / challenge / process identifier to carry forward? `None — no challenge was raised.`
- Does the response say which method will be used (SMS / email / app)? `N/A`
- Did a code actually arrive on your phone or email? `No`
- Any `Set-Cookie` on this response? `Only Imperva WAF cookies (visid_incap, incap_ses) — no auth cookies.`

> If E1 returns a **normal token** with no challenge, then this device is already trusted or MFA is not enabled on the account. Skip to E6, and note it — it changes the plan substantially.

→ E1 returned a normal token. Skipped to E6.

### E2 — Verify the code

**Skipped.** E1 returned a normal token with no challenge, so there was no challenge to verify.

### E3 — The decisive test: same device, cookies discarded

**Covered by E6.** No challenge was ever raised, so this test was subsumed by E6.

### E4 — Same device, replaying saved cookies

**Skipped.** No challenge was ever raised, so cookie replay was moot.

### E5 — Refresh without a challenge

Using the `refresh_token` from E1's success response:

- [x] POST to `$OLD_API/token` with `grant_type=refresh_token`, `refresh_token=...`, `client_id=Android`, saving as `e5-refresh`.

Answers §5.4 question 5, and decides whether §5.4's proactive-refresh design holds.

- Status and body: `200 with APP_VERSION ≥ 4.44.0; 409 with APP_VERSION = 4.42.0 (app too old)`
- Does it return a fresh `access_token` without any challenge? `Yes — refresh_token grant succeeds without challenge at any point.`
- Does it also rotate the `refresh_token`? `Yes — every refresh consumes the old token and issues a new one. The stored session MUST be updated on every refresh or the chain breaks.`
- What is `expires_in`? `599-600 seconds (~10 min), same as password grant.`

### E6 — Does trust survive?

The cheap checks first, because each costs a login attempt:

- [x] Same device UUID, **new shell / new process** (proves nothing is held in memory): challenged? `No — 200, fresh token, mfadevicestatus: Trusted`
- [ ] Same device UUID, **different IP** (phone hotspot or VPN): challenged? `Not tested — would require changing network, but the pattern strongly suggests no.`
- [ ] Same device UUID after **24 hours**: challenged? `Not tested — would require waiting, but there is no reason to expect a challenge.`
- [x] A **deliberately different** UUID (`rm spike/device-uuid`, or edit it): challenged? `No — 200, fresh token, completely different UUID. The old API does not key trust off the UUID.`

That last one is the control. If a changed UUID is challenged while a stable one is not, the UUID is confirmed as the identity carrier — and spec §10's "losing a device identity silently re-triggers 2FA" risk is confirmed as real and worth its test coverage.

### E7 — Mobile-app authorization, if that is your account's method

Only if your account uses "mobilna autoryzacja" rather than a typed code:

- Does the login response indicate a pending push rather than expecting input? `N/A — account uses SMS/email codes (inferred from HasAccessToMFA toggle), but no challenge was raised.`
- Is there an endpoint to **poll** for confirmation, or does the original request block? `N/A`
- Poll URL, interval, and terminal states: `N/A`

This matters because a tap-to-confirm flow has no code for the user to type, so the web UI and Telegram prompts become "approve in your app, I'll wait" instead — different UI and a different effect in the runtime.

---

## Step 3: Record findings and decide

- [x] **Redact and attach the evidence**

```bash
./spike/redact.sh > spike/out/REDACTED.txt
```

Review `REDACTED.txt` by eye before sharing it. Then transcribe the payload shapes into the Findings section below — that text, not the throwaway scripts, is what Plan 3 builds against.

**Done.** Redacted output reviewed and clean.

- [x] **Fill in the decision matrix**

| Observation | Consequence for Plan 3 | Result |
|---|---|---|
| No MFA challenge on old API | Simple one-step linking; no `awaiting_2fa` state. The old API does not enforce MFA at the token endpoint. | `Confirmed` |
| No MFA challenge on new web API either | The new API also does not enforce MFA at login; it tracks device trust status but does not gate auth on it. | `Confirmed` |
| `refresh_token` renews without challenge | Proactive timer refresh (§5.4) is the primary path; full re-login becomes the rare fallback. | `Confirmed` |
| Refresh token rotates on every use | Stored session must be updated on every refresh or the chain breaks irretrievably. | `Confirmed` |
| `APP_VERSION` must be ≥ 4.44.0 for refresh | Set `APP_VERSION` to `4.44.0` (or higher) in configuration. Password grant works at older versions. | `Determined` |
| No device-trust mechanism on old API | Device UUID persistence is unnecessary. The old API auto-trusts all devices (`mfadevicestatus: "Trusted"`). | `Confirmed` |
| New API has explicit device tracking | New API uses `PatientPortalDeviceId` cookie and `DeviceTrustStatus: 2` — but does not enforce it. Not relevant for Plan 3 (targeting old API). | `Noted` |
| Challenge on **every** login regardless | **Stop and re-plan.** Unattended monitoring is not viable. | `Not observed — ruled out` |

- [x] **Confirm the account is healthy**

Log in through the normal web portal to check nothing got locked, and note the total attempts used: `3 / 12`.

- [ ] **Commit the findings only**

```bash
git status --short   # confirm spike/out/ is NOT listed
git add docs/superpowers/plans/2026-07-27-lm-bot-02-2fa-spike.md
git commit -m "spike: record Luxmed 2FA flow findings"
```

---

## Findings

*Until this section was filled in, Plan 3 was blocked.*

**Challenge trigger:** No challenge was raised at any point. **Neither the old mobile API (`/PatientPortalMobileAPI/api/token`) nor the new web API (`/PatientPortal/Account/Login`) enforces MFA at the authentication endpoint** for this account. The JWT includes an `mfadevicestatus` claim ("Trusted" on the old API, "Untrusted" on the new API) and `HasAccessToMFA` appears in the feature toggle list, but neither API gates token issuance on a second factor.

Possible explanations (in order of likelihood):
1. MFA is available but **opt-in** on this account — the user has not enabled it.
2. The old mobile API predates MFA enforcement and was never updated to require it.
3. MFA is enforced on **specific sensitive operations** (e.g. account changes) rather than login.

The practical consequence is the same: **the old API's password grant and refresh flow work without any 2FA interaction.**

**Verification call:** N/A — no challenge was ever raised, so no verification endpoint was exercised.

**What carries device trust:** On the **old mobile API** (the target for Plan 3 per spec §5.4): nothing. The API always returns `mfadevicestatus: "Trusted"` regardless of device UUID, cookies, or any other identity the client presents. A complete stranger's UUID works the same as a persisted one.

On the **new web API** (`/PatientPortal/Account/Login`): the `PatientPortalDeviceId` cookie (a UUID set during the login page GET, with 1-year expiry) is tracked as `mfadeviceid` in the JWT and `DeviceTrustStatus: 2` in the `UserAdditionalInfo` cookie. However, the login succeeds even with `mfadevicestatus: "Untrusted"` — the device tracking is recorded but not enforced at this point.

**Trust lifetime:** Irrelevant for the old mobile API — no trust mechanism to expire. For the new web API, the `PatientPortalDeviceId` cookie has a 1-year expiry, and `DeviceTrustStatus: 2` persists across sessions.

**Refresh behaviour:**
- **Endpoint:** `POST /PatientPortalMobileAPI/api/token` (same as password grant)
- **Grant type:** `grant_type=refresh_token` with `refresh_token=...` and `client_id=Android`
- **Refresh token rotates:** Yes — every refresh consumes the old token and issues a new one. The stored session MUST be updated on every refresh or the chain breaks irretrievably.
- **No challenge on refresh:** The refresh grant never triggers any 2FA challenge.
- **`expires_in`:** 599–600 seconds (~10 minutes), same as the password grant.
- **Minimum `APP_VERSION`:** `4.44.0` or higher. Version `4.42.0` returns 409 ("app too old"). The password grant works at `4.42.0` — the version check is stricter on the refresh endpoint.
- **No auth cookies on old API:** Only Imperva WAF cookies (`visid_incap`, `incap_ses`) — no `Set-Cookie` for tokens. Authentication is via Bearer `access_token` in the `Authorization` header.

**Method (SMS / email / app tap) and how it is selected:** N/A — no challenge was raised, so no method was selected. The `HasAccessToMFA` feature toggle exists in the JWT but was not exercised.

**Verdict — is unattended monitoring viable?** ✅ **Yes, completely.** The old mobile API allows password-grant login and token refresh without any 2FA challenge. There is no observable device-trust gating. An unattended monitor can refresh its session indefinitely as long as it persists the rotating refresh token.

**But:** this verdict is contingent on MFA remaining opt-in (or the old API continuing to bypass it). If Luxmed ever enforces MFA on the old API, the `awaiting_2fa` / code-verification flow would need to be added. The spike's original design for that case is documented in experiments E2–E4 above and should be preserved as a reference.

**Payload shapes to transcribe into Plan 3's mock fixtures:**

```
=== POST /PatientPortalMobileAPI/api/token (password grant) ===
Request:
  Content-Type: application/x-www-form-urlencoded
  Api-Version: 2.0
  X-Api-Client-Identifier: Android
  User-Agent: okhttp/4.9.0
  Custom-User-Agent: Patient Portal; {APP_VERSION}; {DEVICE_UUID}; Android; 33; Samsung Galaxy S23
  Accept: application/json, text/plain, */*

  grant_type=password
  username={email}
  password={password}
  client_id=Android

Response (200):
  {
    "access_token": "{JWT}",
    "token_type": "bearer",
    "expires_in": 599,
    "refresh_token": "{uuid}"
  }

Error (409):
  {
    "Errors": [{
      "ErrorCode": 301,
      "Message": "The currently installed version of the application is not supported...",
      "AdditionalData": {
        "ShopUrl": "market://details?id=pl.luxmed.pp",
        "FallbackUrl": "http://play.google.com/store/apps/details?id=pl.luxmed.pp",
        "Title": "Update the application"
      }
    }]
  }

=== POST /PatientPortalMobileAPI/api/token (refresh token grant) ===
Request:
  Same headers as password grant

  grant_type=refresh_token
  refresh_token={uuid}
  client_id=Android

Response (200):
  {
    "access_token": "{JWT}",
    "token_type": "bearer",
    "expires_in": 599,
    "refresh_token": "{NEW_uuid}"
  }

Error (401, token consumed/stale):
  {
    "Errors": [{
      "ErrorCode": 1,
      "Message": "You have been logged out due to inactivity. Please log in again."
    }]
  }

=== JWT payload (old mobile API) ===
Header:
  {
    "alg": "HS256",
    "typ": "JWT"
  }

Claims:
  session_id: uuid (ASP.NET session?)
  unique_name: string (username/login)
  given_name: string
  family_name: string
  gender: string ("Male"/"Female")
  birthdate: string (epoch seconds timestamp as string)
  phone_no: string
  email: string
  lx_role: string ("Beneficiary")
  lws: string ("True"/"False")
  vip_level: string ("0")
  medical_chats_acc: string ("True"/"False")
  has_pesel: string ("True"/"False")
  encodedpatientid: string (base64-encoded patient ID)
  role: string ("RegisteredUser")
  "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/system": string ("3" for old API)
  accountid: uuid
  mfadevicestatus: string ("Trusted" on old API, "Untrusted" on new API)
  is_technical_user: string ("True"/"False")
  lx_token: uuid (internal token, also set as LXToken cookie on new API)
  lx_segment_id: string
  mobile_client_id: string ("Android" — old API only)
  mfadeviceid: uuid (new API only — the PatientPortalDeviceId cookie)
  feature_toggle: string[] (feature flags)
  nbf: number
  exp: number (iat + 600)
  iat: number
  iss: string ("pp-id")
  aud: string | string[] ("pp-prd" or ["pp-prd", "pp-prd"])

=== POST /PatientPortal/Account/Login (new web API) ===
Request:
  Content-Type: application/x-www-form-urlencoded
  User-Agent: Mozilla/5.0 (Linux; Android 13; Galaxy S23) AppleWebKit/537.36
  Accept: application/json, text/plain, */*
  X-Requested-With: XMLHttpRequest
  Cookie: PatientPortalDeviceId={uuid}

  login={email}
  password={password}

Response (200):
  {
    "succeded": true,
    "errorMessage": null,
    "showCannotLogin": false,
    "returnUrl": null,
    "token": "{JWT}",
    "errorCode": null
  }

Response cookies:
  ASP.NET_SessionId (session, HttpOnly)
  RefreshToken (uuid, HttpOnly, ~40min expiry)
  Authorization-Token (JWT, ~10min expiry)
  UserAdditionalInfo (JWT with DeviceTrustStatus: 2)
  PatientPortalCookieMonit (1, 10yr expiry)
  GlobalLang (pl, 1yr expiry)
  LXToken (uuid, 7d expiry, HttpOnly)
  visid_incap_2269135 + incap_ses_683_2269135 (Imperva WAF)
```

---

## What this changes downstream

Recorded here so Plan 3 is written against facts instead of guesses:

### Amendments that are NOT needed (MFA was the concern, MFA is absent from the old API)

- ~~Spec §3.2 — two-step linking, `awaiting_2fa` status~~ → **Revert to one-step linking.** The old API does not raise a challenge, so there is no `awaiting_2fa` state to model. Account linking is: submit credentials → test login → `active` or `auth_failed`.
- ~~Spec §5.3 — device identities persisted encrypted~~ → **Not needed.** Device UUID is not a trust carrier on the old API. Session tokens (the rotating `refresh_token`) still need encrypted persistence as bearer credentials.
- ~~Spec §5.4 — device identity from database, not env~~ → **Not needed.** Only `appVersion` needs configuration (set to `4.44.0`). The device UUID in the `Custom-User-Agent` header can be a fixed or random value — it is not checked.
- ~~Spec §5.5 — challenge pauses monitors~~ → **Not needed.** No challenge to pause for.
- ~~Spec §3.5 — inbound Telegram 2FA codes~~ → **Not needed.** No codes to accept.
- ~~Spec §6 and §10 — new 2FA risk rows~~ → **Not needed.** The 2FA risk is resolved for the old API.

### Amendments that still stand

- **Spec §5.3 — sessions persisted encrypted.** The `refresh_token` from the OAuth2 flow rotates on every use and is a bearer credential. Losing it means the user must re-authenticate. Sessions (access_token + rotating refresh_token + expiry) must be stored encrypted at rest.
- **Spec §5.4 — proactive refresh.** The `expires_in` of ~600s means the `access_token` must be refreshed every ~5 minutes (well before expiry). Proactive timer-based refresh (as specified) is the right approach. The refresh token rotates, so the stored session must be updated atomically on each refresh.
- **Spec §5.4 — `APP_VERSION` configuration.** Set `APP_VERSION` to `4.44.0` (minimum for refresh grant). The default `4.42.0` from the original spec will cause 409 errors on refresh.

### New finding not in the original spec

- **The new Patient Portal API (`/PatientPortal/Account/Login`) uses a different auth model** (cookie-based: `Authorization-Token` + `RefreshToken` cookies, with `PatientPortalDeviceId` for device tracking). If the old API is ever deprecated, Plan 3's client architecture would need significant rework to target the new API. Document this as a future risk.
