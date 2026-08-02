# Real Luxmed API Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the local development server use deterministic mock Luxmed data by default, with `LIVE_LUXMED_API=true` selecting the real Luxmed API.

**Architecture:** Add an explicit `liveLuxmedApi` configuration value whose default is `false`. The composition starts a path-routed loopback Luxmed server and seeds a normal encrypted mock account when the value is false; the existing production transport and account flow are used unchanged when it is true. The mock implementation is packaged with the backend but is inert unless `LIVE_LUXMED_API=false`, while the committed mock values are non-secret development fixtures.

**Tech Stack:** Scala 3.8.4, sbt 2, JDK `HttpServer`, embedded PostgreSQL, Flyway, Magnum, AES-256-GCM, sttp Luxmed transport, existing JSON fixtures, MUnit.

## Global Constraints

- `startDev` must run inside the flake devShell with Temurin 25 and Node 26+.
- Use `sbt testFull`, never bare `sbt test`, for verification.
- `LIVE_LUXMED_API=false` is the application default and selects the mock API; `LIVE_LUXMED_API=true` is the only mode that selects live Luxmed calls.
- Mock account credentials, sessions, and device identity must use the same AES-256-GCM persistence paths as real linked accounts.
- The mock server must route by request path, not FIFO order, because the browser may issue dictionary requests concurrently or repeat them.
- Secrets and bearer/session values must never be logged or returned by the seed/control code.
- No `scala.concurrent.Future` or JavaScript `Promise` may be introduced in application signatures.

---

### Task 1: Add and document the explicit API-mode configuration

**Files:**
- Modify: `backend/src/main/scala/lmbot/backend/config/Config.scala`
- Modify: `backend/src/test/scala/lmbot/backend/ConfigTest.scala`
- Modify: `build.sbt`
- Modify: `README.md`

**Interfaces:**
- Produces `Config.liveLuxmedApi: Boolean`.
- Reads environment variable `LIVE_LUXMED_API`; absent means `false`.
- `startDev` supplies `LIVE_LUXMED_API=false` unless the caller already supplied a value.

- [ ] **Step 1: Write failing configuration tests**

Add tests proving that a minimal environment produces `liveLuxmedApi == false`, that `LIVE_LUXMED_API=true` produces `true`, that `LIVE_LUXMED_API=false` produces `false`, and that a non-boolean value is rejected with an error naming `LIVE_LUXMED_API`.

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
sbt "backend/testOnly lmbot.backend.ConfigTest"
```

Expected: compilation or assertion failure because `Config` has no `liveLuxmedApi` field and no parser for the new variable.

- [ ] **Step 3: Implement the minimal parser and dev default**

Add `liveLuxmedApi: Boolean` to `Config`, parse it through the existing structural PureConfig path with a default of `false`, and add the field to the constructed config. Add `LIVE_LUXMED_API` to the `Compile / envVars` map in `build.sbt` using `sys.env.getOrElse("LIVE_LUXMED_API", "false")`. Update the README configuration table and `startDev` examples to show:

```bash
sbt startDev
LIVE_LUXMED_API=true sbt startDev
```

- [ ] **Step 4: Run the focused tests and formatting**

Run:

```bash
sbt "backend/testOnly lmbot.backend.ConfigTest"
sbt scalafmtAll
```

Expected: all `ConfigTest` cases pass and the formatter makes no unrelated changes.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/scala/lmbot/backend/config/Config.scala backend/src/test/scala/lmbot/backend/ConfigTest.scala build.sbt README.md
git commit -m "feat: add real Luxmed API development toggle"
```

### Task 2: Build the reusable path-routed mock Luxmed server

**Files:**
- Create: `backend/src/main/scala/lmbot/backend/dev/MockLuxmedServer.scala`
- Create: `backend/src/main/scala/lmbot/backend/dev/MockLuxmedResponses.scala`
- Create: `backend/src/main/resources/mock-luxmed/cities.json`
- Create: `backend/src/main/resources/mock-luxmed/service-variants.json`
- Create: `backend/src/main/resources/mock-luxmed/facilities-and-doctors.json`
- Create: `backend/src/main/resources/mock-luxmed/terms-dual-datetime.json`
- Create: `backend/src/main/resources/mock-luxmed/forgery-token.json`
- Create: `backend/src/test/scala/lmbot/backend/dev/MockLuxmedServerTest.scala`

**Interfaces:**
- `MockLuxmedServer.start(host: String = "127.0.0.1"): MockLuxmedServer` starts a random-port loopback server.
- `MockLuxmedServer.oldApi: sttp.model.Uri` and `newApi: sttp.model.Uri` provide the two base URLs consumed by `LuxmedConfig`.
- `MockLuxmedServer.close(): Unit` stops the server.
- Requests to the OAuth token path return deterministic password/refresh grants; bootstrap and dictionary paths return the existing measured fixtures.

- [ ] **Step 1: Write failing route tests**

Start the server in a test, send real sttp requests to the token, bootstrap, cities, service variants, facilities/doctors, terms, and forgery-token paths, and assert status/body shape. Send an unknown path and assert `404`. Send dictionary requests in an arbitrary order to prove routing is path-based rather than queue-based.

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
sbt "backend/testOnly lmbot.backend.dev.MockLuxmedServerTest"
```

Expected: compilation failure because the dev mock server does not exist.

- [ ] **Step 3: Implement the mock server and response helpers**

Move only the safe, committed dictionary/terms/XSRF fixtures needed by the browser path into main resources. Implement the JDK `HttpServer` with a virtual-thread executor, read request bodies, route using the raw request path, return literal OAuth/bootstrap responses, and serve fixture resources. Count password and refresh grants for test observability, but expose counts only to tests and never expose token values in logs.

- [ ] **Step 4: Run the focused test**

Run:

```bash
sbt "backend/testOnly lmbot.backend.dev.MockLuxmedServerTest"
```

Expected: all route and ordering tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/scala/lmbot/backend/dev backend/src/main/resources/mock-luxmed backend/src/test/scala/lmbot/backend/dev/MockLuxmedServerTest.scala
git commit -m "feat: add path-routed mock Luxmed server"
```

### Task 3: Add idempotent encrypted mock-account seeding

**Files:**
- Create: `backend/src/main/scala/lmbot/backend/dev/MockAccountSeed.scala`
- Create: `backend/src/test/scala/lmbot/backend/dev/MockAccountSeedTest.scala`
- Modify: `backend/src/main/scala/lmbot/backend/db/AccountRepo.scala`

**Interfaces:**
- `MockAccountSeed.ensure(owner: UserId, accounts: AccountRepo, crypto: AesGcm, now: () => Instant): Unit` creates the deterministic account only when the owner does not already have the reserved mock label.
- The seed writes a valid `LuxmedAccountRow` with encrypted username, password, device UUID, and a valid encrypted `LuxmedSession`; it never stores plaintext secrets.
- Re-running `ensure` returns without creating a duplicate or changing an existing row.

- [ ] **Step 1: Write failing seed tests**

Using the existing `PostgresSuite`, create an owner, run `ensure`, assert one account is visible with active status and a non-null encrypted session, decrypt each value with the existing purpose-bound context, assert the session decodes, run `ensure` again, and assert the row count and ciphertexts are unchanged. Assert that a user-owned account with the reserved label is respected rather than overwritten.

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
sbt "backend/testOnly lmbot.backend.dev.MockAccountSeedTest"
```

Expected: compilation failure because the seed and required repository lookup do not exist.

- [ ] **Step 3: Implement the seed**

Add an owner-scoped label lookup to `AccountRepo`. Reserve an account id, use fixed non-production mock values, construct a valid `LuxmedSession` with `CookieJar`, `TokenType.Bearer`, and deterministic short-lived tokens, encrypt username/password/device/session with the existing `EncryptionContext` purposes, and insert an active row. Use the injected clock for timestamps and make the existing-label check happen before reserving an id.

- [ ] **Step 4: Run the focused test**

Run:

```bash
sbt "backend/testOnly lmbot.backend.dev.MockAccountSeedTest"
```

Expected: all encryption, idempotence, and ownership tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/scala/lmbot/backend/dev/MockAccountSeed.scala backend/src/main/scala/lmbot/backend/db/AccountRepo.scala backend/src/test/scala/lmbot/backend/dev/MockAccountSeedTest.scala
git commit -m "feat: seed encrypted mock Luxmed account"
```

### Task 4: Wire `Main` and `startDev` to select mock or real Luxmed

**Files:**
- Modify: `backend/src/main/scala/lmbot/backend/Main.scala`
- Create: `backend/src/test/scala/lmbot/backend/MainCompositionTest.scala`
- Modify: `build.sbt`

**Interfaces:**
- `LIVE_LUXMED_API=false` starts `MockLuxmedServer`, points `LuxmedConfig` at its loopback URLs, and seeds the admin-owned mock account after migrations/bootstrap.
- `LIVE_LUXMED_API=true` does not start the mock server, uses `LuxmedConfig.production`, and does not seed fixture accounts.
- The server and embedded database are closed by shutdown hooks in both modes.

- [ ] **Step 1: Write failing composition tests**

Add a test-scope composition harness that starts the ordinary HTTP server against an embedded database. Assert the default/mock path exposes the seeded account through `/api/accounts` after login and that dictionary lookup reaches the local mock server. Assert the real-mode path constructs the production URI and does not seed an account; do not make a live network request.

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
sbt "backend/testOnly lmbot.backend.MainCompositionTest"
```

Expected: failure because `Main` has no mode-aware composition and does not seed or start the mock boundary.

- [ ] **Step 3: Implement mode-aware composition**

Extract the existing composition wiring into a small private/main-visible function so tests can exercise the same routes/services. Select `MockLuxmedServer` and `LuxmedConfig(mock.oldApi, mock.newApi, ...)` when `config.liveLuxmedApi` is false; otherwise retain `LuxmedConfig.production`. Run `MockAccountSeed.ensure` only in mock mode after migrations and admin bootstrap. Keep `startDev` on `~backend/run`; its existing `Compile / envVars` supplies `LIVE_LUXMED_API=false` unless the caller overrides it, so `LIVE_LUXMED_API=true sbt startDev` selects the live endpoint.

- [ ] **Step 4: Run focused composition tests**

Run:

```bash
sbt "backend/testOnly lmbot.backend.MainCompositionTest"
sbt backend/fastLinkJS
```

Expected: mock startup exposes the seeded account and dictionary data, real-mode selection is verified without a live call, and frontend assets still link.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/scala/lmbot/backend/Main.scala backend/src/test/scala/lmbot/backend/MainCompositionTest.scala build.sbt
git commit -m "feat: default startDev to mock Luxmed data"
```

### Task 5: Full verification and documentation cleanup

**Files:**
- Modify: `README.md` if command/config wording needs final alignment
- Modify: `docs/superpowers/specs/2026-07-27-lm-bot-prd-design.md` to amend the Plan 4 testing boundary and configuration section to document `LIVE_LUXMED_API=false` mock mode and `true` live mode
- Create: `docs/superpowers/reports/2026-08-02-real-luxmed-api-toggle.md`

- [ ] **Step 1: Run the full required checks**

Run:

```bash
nix flake check
sbt testFull
sbt scalafmtCheckAll
sbt scalafmtSbtCheck
git diff --check
```

Expected: all checks pass with the full test suites actually executing.

- [ ] **Step 2: Exercise both development modes**

Run the server with the default and inspect the browser/API flow using the mock account. Then stop it and run:

```bash
LIVE_LUXMED_API=true sbt startDev
```

Verify startup selects the production endpoints without logging credentials or starting the mock server; stop before any account-linking request reaches Luxmed.

- [ ] **Step 3: Write the completion report**

Record changed files, exact verification commands/results, skipped checks (if any), and the behavior of both values of `LIVE_LUXMED_API`. Do not claim success for a skipped or zero-test run.

- [ ] **Step 4: Commit**

```bash
git add README.md docs/superpowers/specs/2026-07-27-lm-bot-prd-design.md docs/superpowers/reports/2026-08-02-real-luxmed-api-toggle.md
git commit -m "docs: document local mock Luxmed development mode"
```
