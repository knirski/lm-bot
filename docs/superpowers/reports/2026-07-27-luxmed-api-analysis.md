# Luxmed Patient Portal API Analysis

**Date:** 2026-07-27
**Source:** Spike investigation per [Plan 2](../plans/2026-07-27-lm-bot-02-2fa-spike.md)
**Scope:** Authentication flows, JWT structure, device trust model, and API surface for both the old mobile API and the new web API

---

## Overview

Luxmed's Patient Portal exposes two API surfaces: the **legacy mobile API** (used by the Android app up to ~mid-2026) and the **new web API** (the current Patient Portal web application). Both authenticate through the same backend identity provider but use different transport mechanisms and have different feature sets.

**For Plan 3, the target is the old mobile API** per spec §5.4 (matching dyrkin/luxmed-bot's approach). The new web API is documented here for reference and future migration.

---

## 1. Old Mobile API (`/PatientPortalMobileAPI/api`)

### 1.1 Base URL

```
https://portalpacjenta.luxmed.pl/PatientPortalMobileAPI/api
```

### 1.2 Authentication — Token Endpoint

**Endpoint:** `POST /api/token`

#### Password Grant (login)

```
POST https://portalpacjenta.luxmed.pl/PatientPortalMobileAPI/api/token
Content-Type: application/x-www-form-urlencoded
Api-Version: 2.0
X-Api-Client-Identifier: Android
User-Agent: okhttp/4.9.0
Custom-User-Agent: Patient Portal; 4.44.0; {device_uuid}; Android; 33; Samsung Galaxy S23
Accept: application/json, text/plain, */*
Accept-Language: pl;q=1.0, pl;q=0.9, en;q=0.8

grant_type=password
username={email_or_login}
password={password}
client_id=Android
```

**Success response (200):**
```json
{
  "access_token": "{JWT}",
  "token_type": "bearer",
  "expires_in": 599,
  "refresh_token": "{uuid}"
}
```

**Error — version too old (409):**
```json
{
  "Errors": [{
    "ErrorCode": 301,
    "Message": "The currently installed version of the application is not supported by the new Patient Portal system. Please update the application to the latest version to use it.",
    "AdditionalData": {
      "ShopUrl": "market://details?id=pl.luxmed.pp",
      "FallbackUrl": "http://play.google.com/store/apps/details?id=pl.luxmed.pp",
      "Title": "Update the application"
    }
  }]
}
```

**Notes:**
- The `Custom-User-Agent` header format is: `Patient Portal; {APP_VERSION}; {DEVICE_UUID}; Android; {API_LEVEL}; {DEVICE_MODEL}`
- `APP_VERSION` minimum: `4.44.0` for refresh grant (password grant works at `4.42.0`)
- The device UUID is **not validated** — any value works, and the API always returns `mfadevicestatus: "Trusted"`
- Content-Type must be `application/x-www-form-urlencoded` (not JSON)

#### Refresh Token Grant

```
POST https://portalpacjenta.luxmed.pl/PatientPortalMobileAPI/api/token
Content-Type: application/x-www-form-urlencoded
Api-Version: 2.0
X-Api-Client-Identifier: Android
User-Agent: okhttp/4.9.0
Custom-User-Agent: Patient Portal; 4.44.0; {device_uuid}; Android; 33; Samsung Galaxy S23

grant_type=refresh_token
refresh_token={uuid}
client_id=Android
```

**Success response (200):** Same shape as password grant — new `access_token` + new `refresh_token`.

**Error — token consumed/stale (401):**
```json
{
  "Errors": [{
    "ErrorCode": 1,
    "Message": "You have been logged out due to inactivity. Please log in again."
  }]
}
```

**Critical: refresh token rotates on every use.** The old `refresh_token` is consumed and a new one is issued. The stored session must be updated atomically on every refresh or the chain breaks irretrievably.

### 1.3 Auth Mechanism

- **Transport:** Bearer token in `Authorization: Bearer {access_token}` header
- **Token type:** JWT (HS256-signed)
- **Token TTL:** ~600 seconds (10 minutes)
- **Refresh token:** UUID (v4), single-use, rotating
- **Cookies:** No auth cookies — only Imperva WAF cookies (`visid_incap`, `incap_ses`)
- **MFA enforcement:** ✅ **None** — the endpoint does not challenge. Device trust status is always `"Trusted"`.

### 1.4 MFA / Device Trust

| Property | Value |
|---|---|
| `mfadevicestatus` (JWT) | `"Trusted"` — always, regardless of device identity |
| Challenge trigger | Not observed on this API endpoint |
| Device UUID validation | None — any UUID, including brand-new random ones, succeeds |
| `HasAccessToMFA` feature toggle | Present in JWT `feature_toggle` array but not enforced |
| Account-level MFA setting | Likely opt-in; not enforced on this API |

### 1.5 Known Endpoints (from luxmed-bot)

| Endpoint | Method | Description |
|---|---|---|
| `/api/token` | POST | Auth (password + refresh grants) |
| `/api/terms/index` | POST | Search available slots |
| `/api/lockterm` | POST | Lock a time slot |
| `/api/confirm` | POST | Confirm booking |
| `/api/releaseterm` | POST | Release a locked slot |
| `/api/dictionaries` | GET | Reference data (facilities, specializations) |

> ⚠️ **Do not build against this table.** These five paths were **not exercised**
> during the spike, and they do not match the paths in dyrkin/luxmed-bot, which
> is a working client: that project uses `NewPortal/terms/index`,
> `NewPortal/reservation/lockterm`, `NewPortal/reservation/confirm` and
> `NewPortal/reservation/releaseterm` — under `/PatientPortal`, not
> `/PatientPortalMobileAPI/api` — and requires an XSRF token plus merged cookies
> for the mutating ones. Spec §5.4 records the verified set. Treat the rows above
> as an untested hypothesis about mobile-API equivalents.

---

## 2. New Web API (`/PatientPortal`)

### 2.1 Base URL

```
https://portalpacjenta.luxmed.pl/PatientPortal
```

### 2.2 Authentication — Login Endpoint

**Endpoint:** `POST /Account/Login`

```
POST https://portalpacjenta.luxmed.pl/PatientPortal/Account/Login
Content-Type: application/x-www-form-urlencoded
User-Agent: Mozilla/5.0 (Linux; Android 13; Galaxy S23) AppleWebKit/537.36
Accept: application/json, text/plain, */*
X-Requested-With: XMLHttpRequest
Cookie: PatientPortalDeviceId={uuid}

login={email_or_login}
password={password}
```

**Success response (200):**
```json
{
  "succeded": true,
  "errorMessage": null,
  "showCannotLogin": false,
  "returnUrl": null,
  "token": "{JWT}",
  "errorCode": null
}
```

**Response cookies:**

| Cookie | Value | Expiry | Flags |
|---|---|---|---|
| `ASP.NET_SessionId` | Session ID | Session | HttpOnly, Secure, SameSite=Lax |
| `RefreshToken` | UUID | ~40 min | HttpOnly, Secure |
| `Authorization-Token` | JWT | ~10 min | Secure |
| `UserAdditionalInfo` | JWT | ~24 h | Secure |
| `PatientPortalCookieMonit` | `1` | ~10 yr | — |
| `GlobalLang` | `pl` | ~1 yr | — |
| `LXToken` | UUID | ~7 d | HttpOnly, Secure |
| `visid_incap_2269135` | WAF session | ~1 yr | HttpOnly, Secure, Domain=.luxmed.pl |
| `incap_ses_683_2269135` | WAF request | Session | Domain=.luxmed.pl |

### 2.3 Auth Mechanism

- **Transport:** Cookie-based — `Authorization-Token` cookie contains the JWT; `RefreshToken` cookie for session extension
- **Token type:** JWT (HS256-signed)
- **Token TTL:** ~600 seconds (10 minutes) — derived from JWT `exp` - `iat`
- **Refresh token:** UUID, set as `RefreshToken` cookie with ~40min expiry
- **Device identity:** `PatientPortalDeviceId` cookie (set on first GET to login page, 1-year expiry) — tracked in JWT as `mfadeviceid`
- **MFA enforcement:** ✅ **None** — login succeeds even with `mfadevicestatus: "Untrusted"` and `DeviceTrustStatus: 2`
- **Error shape:** `{"succeded": false, "errorMessage": "Wypełnij login i hasło", ...}` — note the typo "succeded"

### 2.4 MFA / Device Trust

| Property | Value |
|---|---|
| `mfadevicestatus` (JWT) | `"Untrusted"` for new devices |
| `mfadeviceid` (JWT) | The `PatientPortalDeviceId` cookie value |
| `DeviceTrustStatus` (UserAdditionalInfo) | `2` (meaning: untrusted/pending) |
| Challenge trigger | Not observed — login succeeds at `DeviceTrustStatus: 2` |
| Device trust escalation | Not tested — may become `"Trusted"` after some interaction threshold |
| `HasAccessToMFA` feature toggle | Present in JWT `feature_toggle` array but not enforced |

**Interpretation of `DeviceTrustStatus` values:**
- `2` — Untrusted / pending (observed on first login)
- The status `1` likely means "trusted" and `0`/`2` means various untrusted states
- Since login succeeds at status `2`, the trust status is likely informational or used for risk scoring rather than gating

---

## 3. JWT Structure

### 3.1 Header (common to both APIs)

```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

### 3.2 Claims (old mobile API)

```json
{
  "session_id": "uuid",
  "unique_name": "string (username/login)",
  "given_name": "string",
  "family_name": "string",
  "gender": "Male|Female",
  "birthdate": "epoch_seconds_as_string",
  "phone_no": "string (phone number)",
  "email": "string (email address)",
  "lx_role": "Beneficiary|...",
  "lws": "True|False",
  "vip_level": "0|...",
  "medical_chats_acc": "True|False",
  "has_pesel": "True|False",
  "encodedpatientid": "base64_string",
  "role": "RegisteredUser|...",
  "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/system": "3",
  "accountid": "uuid",
  "mfadevicestatus": "Trusted",
  "is_technical_user": "True|False",
  "lx_token": "uuid",
  "lx_segment_id": "2|...",
  "mobile_client_id": "Android",
  "feature_toggle": ["string", "..."],
  "nbf": 1785177968,
  "exp": 1785178568,
  "iat": 1785177968,
  "iss": "pp-id",
  "aud": ["pp-prd", "pp-prd"]
}
```

### 3.3 Claims (new web API — differences from old)

| Claim | Old API | New API |
|---|---|---|
| `mfadevicestatus` | `"Trusted"` | `"Untrusted"` |
| `mfadeviceid` | absent | `"uuid"` (the `PatientPortalDeviceId`) |
| `mobile_client_id` | `"Android"` | absent |
| `http://schemas.../claims/system` | `"3"` | `"0"` |
| `aud` | `["pp-prd", "pp-prd"]` (array) | `"pp-prd"` (string) |

### 3.4 JWT Claim Reference

| Claim | Type | Description |
|---|---|---|
| `session_id` | UUID | Backend session identifier |
| `unique_name` | string | User login / username |
| `given_name` | string | First name (uppercased) |
| `family_name` | string | Last name (uppercased) |
| `gender` | string | `"Male"` or `"Female"` |
| `birthdate` | string | Unix epoch seconds as string |
| `phone_no` | string | Phone number with country prefix |
| `email` | string | Email address |
| `lx_role` | string | Role in Luxmed system (e.g. `"Beneficiary"`) |
| `lws` | string | `"True"` if employee/LWS account |
| `vip_level` | string | VIP tier (`"0"` = none) |
| `medical_chats_acc` | string | `"True"` if medical chat enabled |
| `has_pesel` | string | `"True"` if PESEL number on file |
| `encodedpatientid` | string | Opaque base64 patient identifier |
| `role` | string | Application role (`"RegisteredUser"`) |
| `claims/system` | string | System claim (different values per API version) |
| `accountid` | UUID | Account identifier |
| `mfadevicestatus` | string | `"Trusted"` or `"Untrusted"` |
| `is_technical_user` | string | `"True"` for technical/service accounts |
| `lx_token` | UUID | Internal Luxmed token (also set as `LXToken` cookie on new API) |
| `lx_segment_id` | string | Segment identifier |
| `mobile_client_id` | string | Client type (`"Android"` — old API only) |
| `mfadeviceid` | UUID | Device identity cookie value (new API only) |
| `feature_toggle` | string[] | Feature flag array |
| `nbf` | number | Not before (epoch) |
| `exp` | number | Expiry (epoch, iat + 600) |
| `iat` | number | Issued at (epoch) |
| `iss` | string | Issuer (`"pp-id"`) |
| `aud` | string or string[] | Audience (`"pp-prd"` or `["pp-prd", "pp-prd"]`) |

### 3.5 Known Feature Toggles

Observed values in `feature_toggle`:

| Toggle | Appears in | Purpose |
|---|---|---|
| `HasAccessToMFA` | Both APIs | MFA feature availability |
| `HasAccessToNewDashboard` | Both APIs | New dashboard UI |
| `HasAccessToZowieChat` | New API only | Chat support widget |
| `CostReturnProcess` | Both APIs | Cost return feature |
| `AccessOnlinePayments` | Both APIs | Online payments |
| `POZDeclarationProcess` | Both APIs | POZ declaration |
| `InboxModuleAccess` | Both APIs | Inbox/messages |
| `MobileAccessOnlinePayments` | Both APIs | Mobile payments |
| `MedicalPackageVerificationModule` | Both APIs | Package verification |
| `WebSurvey` | Both APIs | Surveys |
| `RedirectCbmTelemedicineToDelocalizedSearch` | Both APIs | Search redirect |
| `CCSv2Access` | Both APIs | CCS v2 access |
| `UseOccupationalMedicineRestApi` | Both APIs | Occupational medicine API |
| `ShowVisitManageButtons` | Both APIs | Visit management UI |
| `NewHorizonPayments` | Both APIs | New payment system |
| `DigitalCareHubExtraAccess` | New API only | Digital Care Hub |
| `Dental-Diagram` | Both APIs | Dental diagram feature |
| `PNMSearch` | Both APIs | PBM search |
| `RehabilitationAutoLoadDays` | Both APIs | Rehab auto-load |

---

## 4. API Comparison Summary

| Aspect | Old Mobile API | New Web API |
|---|---|---|
| **Base URL** | `/PatientPortalMobileAPI/api` | `/PatientPortal` |
| **Auth transport** | Bearer token (Authorization header) | Cookie-based (Authorization-Token + RefreshToken) |
| **Login endpoint** | `POST /api/token` (password grant) | `POST /Account/Login` |
| **Token type** | Bearer JWT | Cookie JWT |
| **Token TTL** | ~600s (599 in response, 600 in JWT) | ~600s |
| **Refresh** | `POST /api/token` (refresh_token grant), rotates | `RefreshToken` cookie, rotates |
| **Device identity** | Optional `Custom-User-Agent` UUID (not validated) | Mandatory `PatientPortalDeviceId` cookie |
| **MFA enforcement** | None (`mfadevicestatus: "Trusted"`) | None (`mfadevicestatus: "Untrusted"`) |
| **Maturity** | Being deprecated (version check added) | Current platform |
| **Known endpoints** | `/api/token`, `/api/terms/index`, `/api/lockterm`, `/api/confirm`, `/api/releaseterm`, `/api/dictionaries` | Unknown (not mapped) |

---

## 5. Recommendations for Plan 3

### 5.1 Target API: Old Mobile API

Proceed with the old mobile API as specified in PRD §5.4. The spike confirmed it works without MFA complications.

### 5.2 Configuration Values

| Parameter | Recommended Value | Notes |
|---|---|---|
| `APP_VERSION` | `4.44.0` | Minimum for refresh grant; password grant works at `4.42.0` |
| Token refresh interval | 300s (5 min) | Well before the ~600s expiry |
| Device UUID | Random per deployment or per account | Not validated by the API, used only in `Custom-User-Agent` header |

### 5.3 Session Persistence

- Store the `access_token`, `refresh_token`, and `expires_at` encrypted at rest
- Update all three atomically on each successful refresh (token rotates)
- If a 401 is received on refresh (token consumed), fall back to full password re-authentication
- `expires_in` is ~600s but should be treated as a lower bound — refresh proactively at ~300s

### 5.4 Auth Flow Pseudocode

```
function authenticate(email, password):
    POST /api/token with grant_type=password
    return { access_token, refresh_token, expires_in }

function refresh(refresh_token):
    POST /api/token with grant_type=refresh_token
    // refresh_token rotates — update stored session atomically
    return { access_token, new_refresh_token, expires_in }

function ensure_valid_session(stored_session):
    if stored_session.expires_at - now < 60s:
        return refresh(stored_session.refresh_token)
    return stored_session
```

### 5.5 Future Risk

The old mobile API is showing signs of deprecation:
1. The `/api/token` endpoint returns 409 with a version-update message when `APP_VERSION` is too old
2. The error message explicitly says "not supported by the new Patient Portal system"
3. The new web API represents the current platform direction

If the old API is fully deprecated, Plan 3's client will need to migrate to the new web API's cookie-based auth model, which is a significant architectural change. This risk should be documented and monitored.

---

## 6. Reference: Raw Recorded Responses

See [`spike/out/`](../../../spike/out/) for raw (redacted) request/response pairs. Run `./spike/redact.sh > spike/out/REDACTED.txt` to produce a redacted summary.

**Login attempts used during spike:** 3 / 12 (well within the safety cap).

**Account health:** Confirmed healthy — no lockout or 429s observed.
