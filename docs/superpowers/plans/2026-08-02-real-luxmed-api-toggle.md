# Separate Development Mock From Production Artifact Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep deterministic mock Luxmed code and fixtures out of the production backend artifact while preserving mock-first `startDev` behavior and one shared application composition.

**Architecture:** Add a `backend-dev` JVM project that depends on `backend` and owns the mock server, fixtures, encrypted account seeder, and development entrypoint. Extract the existing database/bootstrap/routes/server wiring into a production `BackendApplication` that accepts a `LuxmedConfig` and an `AccountSeeder`; production passes the live boundary and a no-op seeder, while `backend-dev` passes the selected boundary and mock seeder. Production `Main` fails fast unless `LIVE_LUXMED_API=true`; `startDev` runs `backend-dev` and defaults the flag to `false`.

**Tech Stack:** Scala 3.8.4, sbt 2, JDK `HttpServer`, embedded PostgreSQL, Flyway, Magnum, AES-256-GCM, sttp Luxmed transport, MUnit, existing Scala.js asset linking.

## Global Constraints

- Work inside the flake devShell with Temurin 25 and Node 26+.
- Use `sbt testFull`, never bare `sbt test`, for verification.
- Production `backend` must not contain mock classes or mock resources on its compile/runtime classpath.
- `backend-dev` owns `MockLuxmedServer`, `MockAccountSeed`, mock fixtures, and `startDev` environment defaults.
- `BackendApplication` is the only shared wiring path for migrations, admin bootstrap, routes, static assets, and shutdown.
- The production entrypoint must reject `LIVE_LUXMED_API=false`; the `backend-dev` entrypoint may select mock or live mode.
- Mock account credentials, sessions, and device identity use the same AES-256-GCM persistence paths as real linked accounts.
- The mock server routes by request path, not FIFO order, because browser dictionary requests may arrive concurrently or in any order.
- Secrets and bearer/session values must never be logged or returned by seed/control code.
- No `scala.concurrent.Future` or JavaScript `Promise` may be introduced in application signatures.

---

## File and Responsibility Map

- Create `backend/src/main/scala/lmbot/backend/AccountSeeder.scala`: production-only seeding hook with a no-op implementation; contains no mock knowledge.
- Create `backend/src/main/scala/lmbot/backend/BackendApplication.scala`: shared runtime composition and `AutoCloseable` lifecycle for the database, HTTP server, and embedded PostgreSQL.
- Modify `backend/src/main/scala/lmbot/backend/Main.scala`: production-only entrypoint that validates live mode and delegates to `BackendApplication`.
- Modify `build.sbt`: remove local mock/startDev settings from `backend`, add the `backendDev` project, and point `startDev` at it.
- Create `backend-dev/src/main/scala/lmbot/backend/dev/DevMain.scala`: local entrypoint that chooses mock/live Luxmed boundary and delegates to `BackendApplication`.
- Move `MockLuxmedServer.scala`, `MockAccountSeed.scala`, `mock-luxmed/`, and their tests from `backend` to `backend-dev`.
- Move `MainCompositionTest.scala` to `backend-dev/src/test`; it tests dev boundary selection and must not remain in production tests.
- Create `backend/src/test/scala/lmbot/backend/BackendApplicationTest.scala`: verify the shared composition invokes the supplied seeder after admin bootstrap without importing dev code.
- Modify `README.md`: document that `startDev` uses the separate development launcher and that production requires `LIVE_LUXMED_API=true`.

### Task 1: Define and test the production composition seam

**Files:**
- Create: `backend/src/main/scala/lmbot/backend/AccountSeeder.scala`
- Create: `backend/src/main/scala/lmbot/backend/BackendApplication.scala`
- Create: `backend/src/test/scala/lmbot/backend/BackendApplicationTest.scala`
- Modify: `backend/src/main/scala/lmbot/backend/Main.scala`

**Interfaces:**
- `AccountSeeder.ensure(owner: UserId, accounts: AccountRepo, crypto: AesGcm): Unit` is the only optional development hook exposed by production composition.
- `AccountSeeder.noop` performs no work.
- `BackendApplication.start(config: Config, luxmedConfig: LuxmedConfig, accountSeeder: AccountSeeder): BackendApplication` performs migrations, admin bootstrap, account seeding, route wiring, and server startup.
- `BackendApplication.close(): Unit` closes the HTTP server, datasource, and embedded development database exactly once.

- [ ] **Step 1: Write the failing composition test**

Create an embedded-PostgreSQL test that supplies a recording `AccountSeeder`, starts `BackendApplication` with a loopback-safe `LuxmedConfig`, and asserts that the seeder receives the bootstrapped admin owner after migrations and admin creation. The test imports only production `lmbot.backend` types and does not reference `backend-dev` or mock fixtures.

The test shape is:

~~~scala
test("runs the supplied account seeder after admin bootstrap"):
  val seen = AtomicReference[Option[UserId]](None)
  val seeder = new AccountSeeder:
    override def ensure(owner: UserId, accounts: AccountRepo, crypto: AesGcm): Unit =
      seen.set(Some(owner))
  val application = BackendApplication.start(configWithAdmin, LuxmedConfig.production(appVersion, deviceUuid), seeder)
  try assert(seen.get().nonEmpty)
  finally application.close()
~~~

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

~~~bash
sbt --jvm-client --batch "backend/testOnly lmbot.backend.BackendApplicationTest"
~~~

Expected: compilation failure because `AccountSeeder` and `BackendApplication` do not exist.

- [ ] **Step 3: Extract the current Main wiring**

Move embedded PostgreSQL startup, datasource/transactor creation, Flyway migration, admin bootstrap, auth/account/dictionary/monitor service construction, static and API route composition, HTTP server startup, and lifecycle ownership from `Main.main` into `BackendApplication.start`. Call `accountSeeder.ensure` only after `AdminBootstrap.run` has established or found the admin owner. Preserve cookie, session, encryption, and Luxmed client construction unchanged.

The returned application retains its owned resources and makes `close` idempotent. It must not import `MockLuxmedServer`, `MockAccountSeed`, or any `backend-dev` package.

- [ ] **Step 4: Run the focused test and verify it passes**

Run:

~~~bash
sbt --jvm-client --batch "backend/testOnly lmbot.backend.BackendApplicationTest"
~~~

Expected: the recording seeder observes exactly one admin owner and the application closes cleanly.

- [ ] **Step 5: Commit**

~~~bash
git add backend/src/main/scala/lmbot/backend/AccountSeeder.scala backend/src/main/scala/lmbot/backend/BackendApplication.scala backend/src/test/scala/lmbot/backend/BackendApplicationTest.scala backend/src/main/scala/lmbot/backend/Main.scala
git commit -m "refactor: extract shared backend application composition"
~~~

### Task 2: Make the production entrypoint live-only

**Files:**
- Modify: `backend/src/main/scala/lmbot/backend/Main.scala`
- Create: `backend/src/test/scala/lmbot/backend/MainTest.scala`

**Interfaces:**
- Production `Main.main` parses `Config`, rejects `liveLuxmedApi == false` with an actionable error, and otherwise calls `BackendApplication.start(config, LuxmedConfig.production(...), AccountSeeder.noop)`.
- `Main.requireLiveLuxmedApi(config: Config): Either[String, Unit]` is package-visible and has no effects.
- No production source imports `lmbot.backend.dev` after this task.

- [ ] **Step 1: Add the failing live-only guard test**

Add a package-visible validation function used by `Main` and test it with both `liveLuxmedApi = false` and `liveLuxmedApi = true`. The false case returns an error mentioning `LIVE_LUXMED_API=true`; the true case returns `Right(())` without opening a network connection.

The test shape is:

~~~scala
test("production rejects mock Luxmed mode"):
  assertEquals(Main.requireLiveLuxmedApi(configWith(liveLuxmedApi = false)), Left("LIVE_LUXMED_API=true is required in production"))

test("production accepts live Luxmed mode"):
  assertEquals(Main.requireLiveLuxmedApi(configWith(liveLuxmedApi = true)), Right(()))
~~~

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

~~~bash
sbt --jvm-client --batch "backend/testOnly lmbot.backend.MainTest"
~~~

Expected: compilation or assertion failure because production mode validation does not exist.

- [ ] **Step 3: Implement the guard and production delegation**

Keep environment parsing in `Config`; add only the entrypoint guard needed to ensure a production process cannot silently run without the development project. Log the validation error and exit before opening a database or HTTP server. The true branch delegates to `BackendApplication` with `LuxmedConfig.production` and `AccountSeeder.noop`.

- [ ] **Step 4: Run production backend tests**

Run:

~~~bash
sbt --jvm-client --batch "backend/testOnly lmbot.backend.ConfigTest lmbot.backend.BackendApplicationTest"
~~~

Expected: selected tests pass and the backend source tree has no `lmbot.backend.dev` imports.

- [ ] **Step 5: Commit**

~~~bash
git add backend/src/main/scala/lmbot/backend/Main.scala backend/src/test/scala/lmbot/backend/MainTest.scala
git commit -m "fix: require live Luxmed mode in production entrypoint"
~~~

### Task 3: Create backend-dev and move all mock implementation there

**Files:**
- Modify: `build.sbt`
- Create: `backend-dev/src/main/scala/lmbot/backend/dev/DevMain.scala`
- Move: `backend/src/main/scala/lmbot/backend/dev/MockLuxmedServer.scala` to `backend-dev/src/main/scala/lmbot/backend/dev/MockLuxmedServer.scala`
- Move: `backend/src/main/scala/lmbot/backend/dev/MockAccountSeed.scala` to `backend-dev/src/main/scala/lmbot/backend/dev/MockAccountSeed.scala`
- Move: `backend/src/main/resources/mock-luxmed/` to `backend-dev/src/main/resources/mock-luxmed/`
- Move: `backend/src/test/scala/lmbot/backend/dev/MockLuxmedServerTest.scala` to `backend-dev/src/test/scala/lmbot/backend/dev/MockLuxmedServerTest.scala`
- Move: `backend/src/test/scala/lmbot/backend/dev/MockAccountSeedTest.scala` to `backend-dev/src/test/scala/lmbot/backend/dev/MockAccountSeedTest.scala`
- Move: `backend/src/test/scala/lmbot/backend/MainCompositionTest.scala` to `backend-dev/src/test/scala/lmbot/backend/dev/MainCompositionTest.scala`

**Interfaces:**
- `backendDev` depends on `backend` and shares its production dependencies transitively.
- `DevMain.main` parses the same `Config`, starts `MockLuxmedServer` only when `liveLuxmedApi` is false, selects `LuxmedConfig`, wraps `MockAccountSeed` as `AccountSeeder`, and delegates to `BackendApplication.start`.
- `DevMain.luxmedConfig` remains package-visible for boundary-selection tests; no production `Main` selector is duplicated.

- [ ] **Step 1: Add the project and migration tests**

Create the `backendDev` sbt project and move the mock tests/resources before moving implementation. Update test package declarations/imports only as needed. Run dev test compilation to capture the expected missing-symbol failures.

- [ ] **Step 2: Run focused dev compilation and verify it fails**

Run:

~~~bash
sbt --jvm-client --batch "backendDev/Test/compile"
~~~

Expected: failure until the moved mock implementation and `DevMain` are present.

- [ ] **Step 3: Move the mock implementation and fixtures**

Move the server, seed utility, tests, and `mock-luxmed` resources into `backend-dev`. Preserve path routing, deterministic values, encryption, atomic insertion, and injected-clock tests. Do not copy the files: the old `backend/src/main` and `backend/src/test` paths must be removed.

Make `MockAccountSeed` extend `AccountSeeder` for the production hook while retaining its four-argument overload with an injected clock for deterministic tests. The hook implementation delegates to that overload with the development clock.

- [ ] **Step 4: Implement the development entrypoint**

Implement `DevMain` around the shared application:

~~~scala
val mock = if config.liveLuxmedApi then None else Some(MockLuxmedServer.start())
val luxmedConfig = mock match
  case Some(server) => LuxmedConfig(server.oldApi, server.newApi, config.luxmedAppVersion, UUID.randomUUID())
  case None => LuxmedConfig.production(config.luxmedAppVersion, UUID.randomUUID())
val accountSeeder = mock.fold(AccountSeeder.noop)(_ => MockAccountSeed)
val application = BackendApplication.start(config, luxmedConfig, accountSeeder)
~~~

Install one shutdown hook that closes the shared application and then the optional mock server. Keep startup logs explicit about live versus mock mode and never log credentials or token values.

- [ ] **Step 5: Run dev tests and verify the production classpath is clean**

Run:

~~~bash
sbt --jvm-client --batch "backendDev/testFull"
if rg -n --glob '*.scala' 'lmbot\.backend\.dev|mock-luxmed' backend/src/main backend/src/test; then exit 1; else exit 0; fi
~~~

Expected: all dev tests pass and the production source tree contains no mock implementation or fixture references.

- [ ] **Step 6: Commit**

~~~bash
git add build.sbt backend-dev backend/src/main/scala/lmbot/backend/dev backend/src/main/resources/mock-luxmed backend/src/test/scala/lmbot/backend/dev backend/src/test/scala/lmbot/backend/MainCompositionTest.scala
git commit -m "refactor: move Luxmed mocks into backend-dev"
~~~

### Task 4: Move startDev settings and remove build duplication

**Files:**
- Modify: `build.sbt`
- Modify: `README.md`

**Interfaces:**
- `startDev` runs `~backendDev/run`.
- Only `backendDev / Compile / envVars` supplies embedded PostgreSQL, local credentials, `COOKIE_SECURE=false`, port/host defaults, the local master key, and `LIVE_LUXMED_API=false` fallback.
- The production `backend` project has no local mock environment defaults or mock resource generator settings.

- [ ] **Step 1: Move local settings without duplicating them**

Move current local `Compile / fork`, `Compile / envVars`, source-watch settings, and relevant dev comments from `backend` to `backendDev`. Make `backendDev` watch backend, frontend, and shared sources. Ensure backend resource generation still triggers frontend `fastLinkJS` through the dependency when `backendDev/run` starts.

- [ ] **Step 2: Update the launcher command**

Change the root `startDev` command from `~backend/run` to `~backendDev/run`. Set `backendDev / Compile / mainClass` to `lmbot.backend.dev.DevMain` and keep production `backend / Compile / mainClass` as `lmbot.backend.Main`.

- [ ] **Step 3: Update documentation**

Keep these commands:

~~~bash
sbt startDev
LIVE_LUXMED_API=true sbt startDev
~~~

Explain that the first uses the `backend-dev` launcher and that production deployment runs the production backend with `LIVE_LUXMED_API=true`; do not suggest that mock fixtures are part of the production artifact.

- [ ] **Step 4: Run launcher/build checks**

Run:

~~~bash
sbt --jvm-client --batch "backend/compile; backendDev/compile"
sbt --jvm-client --batch backend/fastLinkJS
~~~

Expected: production and dev projects compile independently, and frontend linking still succeeds.

- [ ] **Step 5: Commit**

~~~bash
git add build.sbt README.md
git commit -m "build: run startDev from backend-dev"
~~~

### Task 5: Full verification and PR documentation

**Files:**
- Modify: `README.md` for the final launcher and artifact-boundary wording
- Modify: `docs/superpowers/specs/2026-07-27-lm-bot-prd-design.md` if implementation exposes a design mismatch
- Modify: `docs/superpowers/plans/2026-08-02-real-luxmed-api-toggle.md` to record implementation differences, if any

- [ ] **Step 1: Run all required checks with real test counts**

Run directly from the repository root using the standard cache:

~~~bash
nix flake check
sbt --jvm-client --batch testFull
sbt --jvm-client --batch "scalafmtCheckAll; scalafmtSbtCheck"
git diff --check
if rg -n --glob '*.scala' 'scala\.concurrent' shared/src/main backend/src/main frontend/src/main | rg -v '/bridge/'; then exit 1; else exit 0; fi
~~~

Expected: all checks pass, `testFull` reports non-zero executed tests, and no production source references the dev project.

- [ ] **Step 2: Verify artifact separation**

Run:

~~~bash
sbt --jvm-client --batch backend/package
find target -type f -name '*.jar' -print
~~~

Inspect the backend artifact and assert that it contains neither `lmbot/backend/dev/` classes nor `mock-luxmed/` resources. The corresponding `backendDev` classpath may contain them.

- [ ] **Step 3: Verify both launcher modes without contacting Luxmed**

Run the dev boundary-selection tests and inspect startup configuration without sending live requests. The default must select loopback mock endpoints and seed only through the dev project; `LIVE_LUXMED_API=true` must select production endpoints and no mock server.

- [ ] **Step 4: Update the PR description and review summary**

Describe the new `backend-dev` artifact boundary, shared `BackendApplication`, production live-only guard, exact verification results, and any skipped browser/startup smoke test. Do not claim a manual smoke test was run if it was not.

- [ ] **Step 5: Commit final documentation and push the branch**

~~~bash
git add README.md docs/superpowers/specs/2026-07-27-lm-bot-prd-design.md docs/superpowers/plans/2026-08-02-real-luxmed-api-toggle.md
git commit -m "docs: document separated Luxmed development launcher"
git push
~~~
