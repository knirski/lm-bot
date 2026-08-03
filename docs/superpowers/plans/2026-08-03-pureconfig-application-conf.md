# PureConfig application.conf Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make PureConfig the single typed configuration boundary, add production and development HOCON resources with environment overrides, collapse the runtime into one backend module, and use `FiniteDuration` for session TTLs.

**Architecture:** `Config.load(env, resourceName)` composes an allow-listed environment source over an explicitly selected resource source and derives the configuration product with Scala 3 PureConfig derivation. One backend `Main` owns both production and development composition; a typed configuration choice selects the mock Luxmed boundary and development account seeder, while a production guard rejects non-live startup before side effects.

**Tech Stack:** Scala 3.8.4, sbt 2, PureConfig 0.17.10 `pureconfig-core`, Typesafe Config/HOCON, MUnit, embedded PostgreSQL, existing Tapir/Gears services.

## Global Constraints

- Environment variables retain their existing names and remain the highest-precedence overrides, including `EMBEDDED_PG`.
- Production defaults contain no credentials or encryption keys.
- Development-only credentials and the fixed development key live only in `application-dev.conf`.
- `Config` derives `ConfigReader` with `pureconfig.generic.derivation.default`; custom readers are limited to validated domain types.
- `Config.fromEnv` remains deterministic and returns `Either[List[String], Config]`; no launcher uses `loadOrThrow`.
- Session/auth TTL APIs use `scala.concurrent.duration.FiniteDuration`; Java durations remain only at explicit foreign-library boundaries.
- Production rejects non-live Luxmed configuration before opening a database or HTTP server.
- Run `sbt scalafmtAll` for Scala, `nix fmt` for Nix, `sbt backend/testFull`, and `nix flake check` before completion.

---

### Task 1: Establish resource-backed configuration sources

**Files:**
- Create: `backend/src/main/resources/application.conf`
- Create: `backend/src/main/resources/application-dev.conf`
- Modify: `backend/src/main/scala/lmbot/backend/config/Config.scala`
- Test: `backend/src/test/scala/lmbot/backend/ConfigTest.scala`

**Interfaces:**
- Produces `Config.fromEnv(env: Map[String, String], resourceName: String = "application.conf"): Either[List[String], Config]`.
- `application.conf` contains production defaults and no secrets.
- `application-dev.conf` contains complete safe local defaults, including `embeddedPg = true`, `liveLuxmedApi = false`, local database credentials, admin bootstrap values, `cookieSecure = false`, and the development master key.

- [ ] **Step 1: Write failing source-selection and precedence tests**

Add tests that use explicit resource names and assert the complete behavior:

```scala
test("production resource supplies non-secret defaults"):
  val result = Config.fromEnv(requiredOnly, "application.conf")
  assertEquals(result.map(_.httpHost), Right("0.0.0.0"))
  assertEquals(result.map(_.httpPort.value), Right(8080))
  assertEquals(result.map(_.cookieSecure), Right(true))

test("development resource supplies local defaults"):
  val result = Config.fromEnv(Map.empty, "application-dev.conf")
  assertEquals(result.map(_.dbUrl), Right("jdbc:postgresql://localhost:15432/lmbot"))
  assertEquals(result.map(_.embeddedPg), Right(true))
  assertEquals(result.map(_.liveLuxmedApi), Right(false))

test("environment values override the selected resource"):
  val result = Config.fromEnv(
    requiredOnly.updated("HTTP_PORT", "9000"),
    "application.conf"
  )
  assertEquals(result.map(_.httpPort.value), Right(9000))
```

- [ ] **Step 2: Run the focused tests and verify the expected failure**

Run: `sbt "backend/testOnly lmbot.backend.ConfigTest"`

Expected: compilation or assertion failure because resource-backed loading and
the development resource do not exist yet.

- [ ] **Step 3: Add the two HOCON resources**

Use the canonical field names. Production contains:

```hocon
httpHost = "0.0.0.0"
httpPort = 8080
cookieSecure = true
sessionTtl = 7 days
liveLuxmedApi = false
embeddedPg = false
luxmedAppVersion = "4.44.0"
```

Development additionally supplies `dbUrl`, `dbUser`, `dbPassword`,
`adminUsername`, `adminPassword`, and `masterKey`, with `httpHost =
"127.0.0.1"`, `cookieSecure = false`, `liveLuxmedApi = false`, and
`embeddedPg = true`.

- [ ] **Step 4: Implement explicit source composition**

Replace the hand-built HOCON cursor reads with these boundaries:

```scala
def fromEnv(
    env: Map[String, String],
    resourceName: String = "application.conf"
): Either[List[String], Config] =
  val overrides = ConfigSource.fromConfig(environmentConfig(env))
  val defaults = ConfigSource.resources(resourceName)
  overrides.withFallback(defaults).load[Config]
    .left.map(_.toList.map(_.description))
```

`environmentConfig` must use an explicit mapping from the existing variable
names to model paths, filter empty optional values as current behavior requires,
preserve empty `LIVE_LUXMED_API` and `LUXMED_APP_VERSION` for validation, and
translate `SESSION_TTL_DAYS=7` to the literal HOCON value `7 days`. Values must
be inserted with `ConfigFactory.parseMap` so substitution-looking secrets stay
literal. The default resource must be loaded only through the selected
resource name, never through classpath ordering.

- [ ] **Step 5: Run the focused tests and verify they pass**

Run: `sbt "backend/testOnly lmbot.backend.ConfigTest"`

Expected: all source, default, and precedence tests pass; existing validation
tests may remain red until Task 2 installs domain readers.

- [ ] **Step 6: Commit the task**

```bash
git add backend/src/main/resources/application.conf \
  backend/src/main/resources/application-dev.conf \
  backend/src/main/scala/lmbot/backend/config/Config.scala \
  backend/src/test/scala/lmbot/backend/ConfigTest.scala
git commit -m "feat: load backend configuration from pureconfig sources"
```

### Task 2: Derive the model and migrate session TTL to FiniteDuration

**Files:**
- Modify: `backend/src/main/scala/lmbot/backend/config/Config.scala`
- Modify: `backend/src/main/scala/lmbot/backend/config/Port.scala`
- Modify: `backend/src/main/scala/lmbot/backend/config/AppVersion.scala`
- Modify: `backend/src/main/scala/lmbot/backend/config/MasterKey.scala`
- Modify: `backend/src/main/scala/lmbot/backend/auth/AuthService.scala`
- Modify: `backend/src/main/scala/lmbot/backend/http/AuthRoutes.scala`
- Modify: `backend/src/main/scala/lmbot/backend/http/SessionCookie.scala`
- Modify: relevant backend tests that construct session TTLs

**Interfaces:**
- `Config` is declared with `derives ConfigReader` and imports `pureconfig.generic.derivation.default.*`.
- `Config.sessionTtl: FiniteDuration`.
- `AuthService`, `AuthRoutes`, and `SessionCookie.issue` accept `FiniteDuration`.

- [ ] **Step 1: Write failing reader and duration tests**

Add tests asserting the actual parsed types and failures:

```scala
test("session TTL is a finite Scala duration"):
  val Right(config) = Config.fromEnv(requiredOnly, "application.conf")
  assertEquals(config.sessionTtl, 7.days)

test("zero and negative session TTLs are rejected"):
  assert(Config.fromEnv(requiredOnly.updated("SESSION_TTL_DAYS", "0")).isLeft)
  assert(Config.fromEnv(requiredOnly.updated("SESSION_TTL_DAYS", "-1")).isLeft)
```

Add reader tests for valid and invalid `Secret`, `Port`, `AppVersion`, and
`MasterKey` values through `Config.fromEnv`, not by testing implementation
helpers in isolation.

- [ ] **Step 2: Run the tests and verify the expected failure**

Run: `sbt "backend/testOnly lmbot.backend.ConfigTest"`

Expected: compilation fails because the model still uses Java duration and no
PureConfig readers/derivation exist.

- [ ] **Step 3: Add companion readers and native product derivation**

Use `ConfigReader.fromCursor`/cursor conversion and `cur.failed(...)` for
validated domain values. Keep all diagnostics free of secret input. Declare:

```scala
import pureconfig.ConfigReader
import pureconfig.generic.derivation.default.*

case class Config(..., sessionTtl: FiniteDuration, ...) derives ConfigReader
```

The duration reader consumes the HOCON duration string, and a post-load
validation step rejects values shorter than one day while preserving the
existing `Either[List[String], Config]` error surface.

- [ ] **Step 4: Migrate session/auth APIs**

Replace Java duration imports in `AuthService`, `AuthRoutes`, and
`SessionCookie`. Convert the finite duration to the existing Java timestamp at
the boundary with `now().plusNanos(sessionTtl.toNanos)` and continue emitting
cookie `Max-Age` via `ttl.toSeconds`.

- [ ] **Step 5: Run focused tests and the affected auth suites**

Run: `sbt "backend/testOnly lmbot.backend.ConfigTest lmbot.backend.AuthServiceTest lmbot.backend.HttpApiTest lmbot.backend.AccountHttpApiTest lmbot.backend.MonitorHttpApiTest"`

Expected: all pass with no Java/Scala duration mismatch.

- [ ] **Step 6: Commit the task**

```bash
git add backend/src/main/scala/lmbot/backend/config \
  backend/src/main/scala/lmbot/backend/auth/AuthService.scala \
  backend/src/main/scala/lmbot/backend/http/AuthRoutes.scala \
  backend/src/main/scala/lmbot/backend/http/SessionCookie.scala \
  backend/src/test/scala/lmbot/backend
git commit -m "refactor: derive config and use finite session durations"
```

### Task 3: Collapse backend-dev into the backend composition root

**Files:**
- Move: `backend-dev/src/main/scala/lmbot/backend/dev/DevMain.scala` to `backend/src/main/scala/lmbot/backend/dev/DevMain.scala`
- Move: `backend-dev/src/main/scala/lmbot/backend/dev/MockAccountSeed.scala` to `backend/src/main/scala/lmbot/backend/dev/MockAccountSeed.scala`
- Move: `backend-dev/src/main/scala/lmbot/backend/dev/MockLuxmedServer.scala` to `backend/src/main/scala/lmbot/backend/dev/MockLuxmedServer.scala`
- Move: `backend-dev/src/main/resources/mock-luxmed/*` to `backend/src/main/resources/mock-luxmed/*`
- Move: `backend-dev/src/test/scala/lmbot/backend/dev/*` to `backend/src/test/scala/lmbot/backend/dev/*`
- Modify: `backend/src/main/scala/lmbot/backend/Main.scala`
- Modify: `backend/src/test/scala/lmbot/backend/MainTest.scala`
- Modify: `backend/src/test/scala/lmbot/backend/dev/*`
- Modify: `build.sbt`

**Interfaces:**
- One `backend` project provides `lmbot.backend.Main` as its only launcher.
- `Main` receives the selected resource/profile, parses one `Config`, and chooses `MockLuxmedServer`/`MockAccountSeed` when `liveLuxmedApi` is false.
- `Main.requireLiveLuxmedApi` remains pure and production-safe.

- [ ] **Step 1: Write failing one-launcher composition tests**

Add tests asserting that a development resource selects the mock boundary and
seeder, while a live configuration selects production and the no-op seeder.
Keep server/database startup behind existing injectable seams.

- [ ] **Step 2: Run the dev composition tests and verify the expected failure**

Run: `sbt "backend/testOnly lmbot.backend.MainTest lmbot.backend.dev.DevMainTest lmbot.backend.dev.MainCompositionTest"`

Expected: failure because the launcher still lives in a separate project and
does not select a resource.

- [ ] **Step 3: Move development sources/resources/tests into backend**

Use file moves, preserve package names, and update only imports/resource lookup
paths needed for the new location. Do not duplicate mock fixtures.

- [ ] **Step 4: Implement one resource-aware launcher**

`Main.main` must select `application.conf` by default and accept the
development resource through the `LMBOT_CONFIG_RESOURCE` environment value that
`startDev` injects. Parse configuration before starting the
mock server, embedded PostgreSQL, migrations, or HTTP server. Reuse the
existing `BackendApplication.start` wiring and pass either the selected mock
Luxmed config plus `MockAccountSeed`, or production Luxmed config plus
`AccountSeeder.noop`.

- [ ] **Step 5: Remove the backend-dev SBT project and old env defaults**

Delete the `backendDev` project block and its old defaults table from
`build.sbt`. Make the single backend `run` scope inject only
`LMBOT_CONFIG_RESOURCE=application-dev.conf`; `Config.fromEnv(env)` reads that
selector and otherwise defaults to `application.conf`. Keep frontend asset
linking and source watching working for the single backend project.

- [ ] **Step 6: Run composition tests and verify they pass**

Run: `sbt "backend/testOnly lmbot.backend.MainTest lmbot.backend.dev.DevMainTest lmbot.backend.dev.MainCompositionTest"`

Expected: all pass, including the production guard and development mock
selection.

- [ ] **Step 7: Commit the task**

```bash
git add build.sbt backend/src/main backend/src/test
git commit -m "refactor: unify production and development backend launchers"
```

### Task 4: Update documentation and run the complete verification suite

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-08-03-pureconfig-application-conf-design.md`
- Modify: `docs/superpowers/plans/2026-08-03-pureconfig-application-conf.md`

- [x] **Step 1: Update configuration documentation**

Document that defaults come from the selected HOCON resource, environment
variables override them, `startDev` selects `application-dev.conf`, and
production uses `application.conf` with `LIVE_LUXMED_API=true` required.
Document `FiniteDuration` only as an implementation detail; keep operator
configuration expressed in days and existing variable names.

- [x] **Step 2: Format and run the focused checks**

Run:

```bash
sbt scalafmtAll
sbt scalafmtCheckAll
sbt scalafmtSbtCheck
git diff --check
```

Expected: formatting and whitespace checks exit successfully.

- [x] **Step 3: Run the full backend suite**

Run: `sbt backend/testFull`

Expected: all backend tests pass with the embedded PostgreSQL suites included.

- [x] **Step 4: Run repository verification**

Run: `nix flake check`

Expected: formatting, dead-code, statix, typos, shellcheck, actionlint, and
other configured checks pass without disabling or excluding any suite.

- [x] **Step 5: Commit documentation and verification updates**

```bash
git add README.md docs/superpowers/specs/2026-08-03-pureconfig-application-conf-design.md \
  docs/superpowers/plans/2026-08-03-pureconfig-application-conf.md
git commit -m "docs: document unified pureconfig configuration"
```
