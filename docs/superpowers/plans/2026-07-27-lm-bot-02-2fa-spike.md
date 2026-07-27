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

- [ ] **Create the spike directory, outside the build**

These files are throwaway and deliberately not part of the sbt project — nothing here graduates into production. Plan 3 transcribes the *findings* into fixtures, not this code.

```bash
mkdir -p spike/out
```

The repository `.gitignore` already excludes `spike/out/`, `spike/device-uuid` and `spike/attempt-count`, so tokens and cookies cannot be committed by accident.

- [ ] **Write the shared harness**

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

- [ ] **Write the login step**

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

- [ ] **Write the redaction step**

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

- [ ] Delete any stale identity so this really is a new device: `rm -f spike/device-uuid`
- [ ] `LUXMED_USER=... LUXMED_PASS=... ./spike/login.sh e1-unknown-device`

Answers §5.4 question 1: *which step raises the challenge, and what does it look like?*

- Status code: `______`
- Is there a challenge indicator in the body? What field names? `______`
- Is there a transaction / challenge / process identifier to carry forward? `______`
- Does the response say which method will be used (SMS / email / app)? `______`
- Did a code actually arrive on your phone or email? `______`
- Any `Set-Cookie` on this response? `______`

> If E1 returns a **normal token** with no challenge, then this device is already trusted or MFA is not enabled on the account. Skip to E6, and note it — it changes the plan substantially.

### E2 — Verify the code

- [ ] Find the verification endpoint. It was not in luxmed-bot, so it has to be discovered. Cheapest route first: open the Portal Pacjenta **web** login in a browser with DevTools → Network, log in, and watch which request carries the code you type. Record the URL, method, and request body verbatim.
- [ ] Reproduce that call with `curl`, carrying whatever identifier E1 returned, and save it as `e2-verify`.

Answers §5.4 question 2: *what verifies the code, and what comes back?*

- Verification URL and method: `______`
- Request body shape: `______`
- Does it need the challenge id from E1, or the credentials again, or both? `______`
- Success response — does it directly return `access_token`, or must `/token` be replayed afterwards? `______`
- Response to a **wrong** code (use one deliberately, it costs no login attempt): `______`
- Any `Set-Cookie` that looks like a device-trust marker? `______`

### E3 — The decisive test: same device, cookies discarded

Wait 60 seconds, then:

- [ ] `LUXMED_USER=... LUXMED_PASS=... ./spike/login.sh e3-same-device-no-cookies`

Note this sends **no cookies** — so if it succeeds without a challenge, trust is carried by the device UUID itself, which is the best possible outcome for us.

- Challenged again? `______`
- If not challenged: trust is carried by the **`Custom-User-Agent` UUID**. → Persist the UUID (already specified, §5.3).
- If challenged: trust is *not* in the UUID alone. Continue to E4.

### E4 — Same device, replaying saved cookies

Wait 60 seconds, then repeat the login adding the cookie jar captured during E2:

- [ ] `... ./spike/login.sh e4-same-device-with-cookies -b spike/out/e2-verify.headers`

(If the jar needs assembling by hand from `Set-Cookie` lines, do that — the point is to present whatever E2 handed back.)

- Challenged again? `______`
- If **not** challenged here but challenged in E3: trust lives in a **cookie**. → That cookie must be persisted encrypted and replayed, and its `Max-Age` / `Expires` is the real trust lifetime. Record it: `______`

### E5 — Refresh without a challenge

Using the `refresh_token` from E2's success response:

- [ ] POST to `$OLD_API/token` with `grant_type=refresh_token`, `refresh_token=...`, `client_id=Android`, saving as `e5-refresh`.

Answers §5.4 question 5, and decides whether §5.4's proactive-refresh design holds.

- Status and body: `______`
- Does it return a fresh `access_token` without any challenge? `______`
- Does it also rotate the `refresh_token`? (If so, the stored session must be updated on every refresh or the chain breaks.) `______`
- What is `expires_in`? `______`

### E6 — Does trust survive?

The cheap checks first, because each costs a login attempt:

- [ ] Same device UUID, **new shell / new process** (proves nothing is held in memory): challenged? `______`
- [ ] Same device UUID, **different IP** (phone hotspot or VPN): challenged? `______`
- [ ] Same device UUID after **24 hours**: challenged? `______`
- [ ] A **deliberately different** UUID (`rm spike/device-uuid`, or edit it): challenged? `______`

That last one is the control. If a changed UUID is challenged while a stable one is not, the UUID is confirmed as the identity carrier — and spec §10's "losing a device identity silently re-triggers 2FA" risk is confirmed as real and worth its test coverage.

### E7 — Mobile-app authorization, if that is your account's method

Only if your account uses "mobilna autoryzacja" rather than a typed code:

- Does the login response indicate a pending push rather than expecting input? `______`
- Is there an endpoint to **poll** for confirmation, or does the original request block? `______`
- Poll URL, interval, and terminal states: `______`

This matters because a tap-to-confirm flow has no code for the user to type, so the web UI and Telegram prompts become "approve in your app, I'll wait" instead — different UI and a different effect in the runtime.

---

## Step 3: Record findings and decide

- [ ] **Redact and attach the evidence**

```bash
./spike/redact.sh > spike/out/REDACTED.txt
```

Review `REDACTED.txt` by eye before sharing it. Then transcribe the payload shapes into the Findings section below — that text, not the throwaway scripts, is what Plan 3 builds against.

- [ ] **Fill in the decision matrix**

| Observation | Consequence for Plan 3 | Result |
|---|---|---|
| Stable UUID alone earns trust | Persist device UUID; linking is a one-time enrollment. Best case; spec §3.2 stands as written. | `____` |
| A cookie carries trust | Persist the cookie encrypted alongside the session; trust lifetime is its expiry. §5.3 already persists the jar, so this is a small change. | `____` |
| `refresh_token` renews without challenge | Proactive timer refresh (§5.4) is the primary path; full re-login becomes the rare fallback. | `____` |
| Trust survives restart and IP change | Restart resumes silently, as §5.5 requires. | `____` |
| Trust expires after a known period | Schedule re-enrollment *before* expiry and prompt the user at a civilised hour rather than mid-poll. | `____` |
| Challenge on **every** login regardless | **Stop and re-plan.** Unattended monitoring is not viable; per the decision on 2026-07-27, this gets flagged rather than designed around. | `____` |

- [ ] **Confirm the account is healthy**

Log in through the normal web portal to check nothing got locked, and note the total attempts used: `____ / 12`.

- [ ] **Commit the findings only**

```bash
git status --short   # confirm spike/out/ is NOT listed
git add docs/superpowers/plans/2026-07-27-lm-bot-02-2fa-spike.md
git commit -m "spike: record Luxmed 2FA flow findings"
```

---

## Findings

*Fill this in from the experiments. Until it is filled in, Plan 3 is blocked.*

**Challenge trigger:** _______

**Verification call:** _______

**What carries device trust:** _______

**Trust lifetime:** _______

**Refresh behaviour:** _______

**Method (SMS / email / app tap) and how it is selected:** _______

**Verdict — is unattended monitoring viable?** _______

**Payload shapes to transcribe into Plan 3's mock fixtures:**

```
(paste redacted request/response pairs here)
```

---

## What this changes downstream

Recorded here so Plan 3 is written against the amended spec rather than the original:

- Spec §3.2 — two-step linking, `awaiting_2fa` status, stable device identity, and why `awaiting_2fa` must never be conflated with `auth_failed`.
- Spec §5.3 — sessions and device identities are now **persisted encrypted**, reversing the original in-memory-only rule.
- Spec §5.4 — device identity comes from the database, not env; only `appVersion` is configuration. Proactive refresh replaces lazy-on-401.
- Spec §5.5 — a challenge pauses monitors and resumes them automatically on completion, with no manual intervention.
- Spec §3.5 — inbound Telegram grows beyond `/start`: it also accepts 2FA codes, gated to the linked chat, an account actually in `awaiting_2fa`, and a short window.
- Spec §6 and §10 — persisted sessions are bearer credentials; two new risk rows.
