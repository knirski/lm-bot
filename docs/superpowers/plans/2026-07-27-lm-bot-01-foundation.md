# lm-bot Plan 1: Foundation & Auth Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a deployable lm-bot skeleton that a user can log into — proving the entire stack (sbt cross-build, Postgres/Flyway/Magnum, tapir-on-jdkhttp, Elm-on-Gears, Scala.js Wasm, Docker, CI) works end to end before any Luxmed code exists.

**Architecture:** Three modules. `shared` cross-compiles to JVM and Scala.js and owns the domain types, the Tapir endpoint descriptions, and the jsoniter codecs — it is the single source of truth for the API contract. `backend` interprets those endpoints with tapir's `Identity` interpreter on a `com.sun.net.httpserver` server running a virtual-thread executor, talks to Postgres through Magnum over blocking JDBC, and serves the linked frontend as static resources. `frontend` is a Scala.js/Wasm Laminar app structured as Elm-on-Gears: one `Var[AppState]`, one `Channel[Msg]`, one event-loop fiber, pure `update`.

**Tech Stack:** Scala 3.8.4, Gears 0.3.1, Tapir 1.13.29, sttp 3.11.0, jsoniter-scala 2.39.1, Laminar 17.2.1, Magnum 1.3.1, Flyway 11.8.2, PostgreSQL 17, MUnit 1.3.4, **sbt 2.0.4**, Scala.js 1.22.0. Development environment from `flake.nix` + direnv.

## Prerequisites

`flake.nix` and `.envrc` already exist at the repository root and are the source of truth for the toolchain — Temurin 25, the sbt launcher, Node 26, Metals, scalafmt, `psql`, and the `curl`/`jq`/`uuidgen` the Plan 2 spike needs. Nothing in this plan installs tools by hand.

- [ ] **Enter the shell**

```bash
direnv allow     # once per clone; or `nix develop` if you prefer it explicit
```

You should see the banner naming the JDK, sbt, and Node versions.

- [ ] **Confirm a container runtime is reachable**

The banner's `ctr` line must not be a warning. Testcontainers drives the Postgres used from Task 4 onward, and it speaks the Docker API.

**On this project's dev machine that runtime is rootless Podman, not Docker.** Testcontainers does not discover Podman's socket by itself, so the devShell exports `DOCKER_HOST`, `TESTCONTAINERS_RYUK_DISABLED=true`, and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` when it finds `$XDG_RUNTIME_DIR/podman/podman.sock`. Without that, Task 4 fails with "Could not find a valid Docker environment". If the warning appears:

```bash
systemctl --user start podman.socket
```

Because Ryuk (Testcontainers' reaper) is disabled under rootless Podman, a hard-killed JVM can leave containers behind. `podman ps` after a crash; prune if needed.

- [ ] **Sanity-check the toolchain**

```bash
node --version     # must be v26.x — Node 24/25 break Gears (spec §5.1)
java -version      # 21
sbt --script-version
```

## Global Constraints

Every task's requirements implicitly include this section. Values are copied verbatim from the spec.

- **Scala 3.8.4**, JVM **21+** backend. Scala.js **1.22.0** with the **WebAssembly** backend and a JSPI-capable runtime.
- **Gears 0.3.1 is the only async vocabulary.** `scala.concurrent.Future` and JS `Promise` are banned from application signatures; foreign async APIs are adapted only inside the `lmbot.frontend.bridge` package.
- **Errors are values** (Scala 3 union types / `Either`). Exceptions are reserved for genuine bugs; they crash the failing fiber, never the supervisor.
- **Airstream vocabulary is allowed in exactly one place:** the store `Var` and view projections (`Signal.map` / `distinct`). An `observe` or stream combinator anywhere else is a review flag.
- **Declarative leaf layers carry no control flow:** Tapir endpoint descriptions and Laminar view templates describe structure only.
- **No DI framework, no reflection:** plain classes wired by constructors; codecs and schemas derived at compile time.
- Internal passwords: **Argon2id**. Sessions: **opaque random tokens, stored hashed**, cookie **`HttpOnly` + `Secure` + `SameSite=Lax`**.
- Authorization lives in the **service layer**, not just the UI. Every resource is reachable only through its owning user.
- **Secrets are never logged.**
- TLS is terminated by the operator's reverse proxy, **not in-app**.
- Admin account is created from `ADMIN_USERNAME` / `ADMIN_PASSWORD` **only when the `users` table is empty**. No self-registration.
- All Luxmed-facing dates and times are interpreted in **`Europe/Warsaw`** regardless of server or browser time zone. (No Luxmed code in this plan; the helper is established here.)
- **Dev/test tooling must avoid Node 24 and 25** (V8 stack-overflow bug in nested async contexts) — use **Node 26+**.
- TDD throughout; CI runs everything on every push.

---

## File Structure

```
lm-bot/
├── flake.nix                                   # ALREADY EXISTS — pins the whole toolchain
├── .envrc                                      # ALREADY EXISTS — direnv: use flake
├── build.sbt                                   # cross-build, all three modules, pinned versions
├── project/build.properties                    # sbt 2.0.4
├── project/plugins.sbt                         # sbt-scalajs, sbt-assembly (no crossproject: no sbt 2 build)
├── .gitignore
├── docker-compose.yml                          # backend + postgres
├── Dockerfile                                  # builds frontend, then backend, then runs
├── .github/workflows/ci.yml                    # compile + test all modules on every push
│
├── shared/src/main/scala/lmbot/shared/
│   ├── domain/Role.scala                       # enum Role
│   ├── domain/UserView.scala                   # what the API exposes about a user
│   ├── api/ApiError.scala                      # error ADT + wire mapping
│   ├── api/AuthPayloads.scala                  # LoginRequest
│   ├── api/Codecs.scala                        # jsoniter codecs + tapir schemas
│   └── api/AuthEndpoints.scala                 # tapir endpoint descriptions
├── shared/src/test/scala/lmbot/shared/
│   ├── ApiErrorTest.scala
│   └── CodecRoundTripTest.scala
│
├── backend/src/main/resources/db/migration/
│   └── V1__init.sql                            # users, sessions
├── backend/src/main/resources/logback.xml
├── backend/src/main/scala/lmbot/backend/
│   ├── Main.scala                              # composition root
│   ├── config/Config.scala                     # env → validated config
│   ├── db/Database.scala                       # datasource + flyway + transactor
│   ├── db/Rows.scala                           # Magnum row types (persistence shapes)
│   ├── db/UserRepo.scala
│   ├── db/SessionRepo.scala
│   ├── auth/Passwords.scala                    # Argon2id
│   ├── auth/Tokens.scala                       # opaque token generation + hashing
│   ├── auth/AuthService.scala                  # login / authenticate / logout
│   ├── auth/AdminBootstrap.scala               # first-start admin creation
│   ├── http/SessionCookie.scala                # cookie construction
│   ├── http/AuthRoutes.scala                   # endpoint → logic wiring
│   ├── http/HealthRoutes.scala
│   ├── http/StaticRoutes.scala                 # serves the linked frontend
│   └── http/Server.scala                       # jdkhttp + virtual threads
├── backend/src/test/scala/lmbot/backend/
│   ├── support/PostgresSuite.scala             # Testcontainers harness
│   ├── PasswordsTest.scala
│   ├── TokensTest.scala
│   ├── ConfigTest.scala
│   ├── UserRepoTest.scala
│   ├── SessionRepoTest.scala
│   ├── AuthServiceTest.scala
│   ├── AdminBootstrapTest.scala
│   └── HttpApiTest.scala                       # in-process, real Postgres
│
└── frontend/src/main/scala/lmbot/frontend/
    ├── Main.scala                              # entry point, starts the loop
    ├── bridge/Bridge.scala                     # THE ONLY place std Future appears
    ├── elm/Effect.scala
    ├── elm/Runtime.scala                       # store + channel + event loop
    ├── AppState.scala
    ├── Msg.scala
    ├── Update.scala                            # pure
    ├── api/ApiClient.scala                     # tapir-derived client
    └── view/AppView.scala                      # Laminar, render-only
└── frontend/src/test/scala/lmbot/frontend/
    ├── UpdateTest.scala                        # pure, no DOM
    └── RuntimeTest.scala
└── frontend/index.html
```

**Responsibilities.** `shared/api` is declarative only — it names endpoints and shapes and never branches. `backend/db` maps rows to domain values and contains no policy; `backend/auth` holds all policy (does this password match, is this session live, is this user disabled) and is the only place that decides. `backend/http` translates between HTTP and services and holds no rules of its own. On the frontend, `Update.scala` is pure and holds every decision; `view/` only renders; `elm/Runtime.scala` is the sole place effects are executed; `bridge/` is the sole place `scala.concurrent.Future` is mentioned.

---

### Task 1: Cross-build skeleton and CI

**Files:**
- Create: `build.sbt`, `project/build.properties`, `project/plugins.sbt`, `.gitignore`, `.github/workflows/ci.yml`
- Create: `shared/src/main/scala/lmbot/shared/BuildInfo.scala`
- Test: `shared/src/test/scala/lmbot/shared/BuildInfoTest.scala`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: sbt projects `sharedJVM` and `sharedJS` (two projects over one shared source directory), `backend`, `frontend`, plus a `jsDep` helper and a `wasmConfig` linker function used by both Scala.js projects. Object `lmbot.shared.BuildInfo` with `val name: String = "lm-bot"`.

- [ ] **Step 1: Write the failing test**

`shared/src/test/scala/lmbot/shared/BuildInfoTest.scala`:

```scala
package lmbot.shared

class BuildInfoTest extends munit.FunSuite:
  test("build info carries the project name"):
    assertEquals(BuildInfo.name, "lm-bot")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt sharedJVM/testFull`
Expected: FAIL — sbt cannot load the build (no `build.sbt`), or `BuildInfo` is not found.

- [ ] **Step 3: Write the build definition and the minimal source**

`project/build.properties`:

```
sbt.version=2.0.4
```

`project/plugins.sbt` — **two plugins, both carrying their weight.** sbt 2 resolves plugins as `_sbt2_3` artifacts; both of these publish one (verified on Maven Central):

```scala
// Essential: there is no other way to build Scala.js.
addSbtPlugin("org.scala-js" % "sbt-scalajs"  % "1.22.0")
// Earns its place: produces the single fat jar the runtime image copies,
// instead of shipping a classpath plus a coursier cache (Task 12).
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.4.1")
```

**`sbt-crossproject` is deliberately absent — it is not published for sbt 2** (only `_2.12_1.0` exists). The cross-build is therefore hand-rolled below: two ordinary projects sharing one source directory, about six lines. That is a plugin the build is better off without, not a workaround.

Two sbt-2 consequences that follow from dropping it, both verified by building a probe before this plan was written:

- **`%%%` is not available.** It comes from the crossproject plugin's auto-import, and sbt-scalajs on sbt 2 does not surface it — even with an explicit `import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*`. Scala.js artifacts are named explicitly through a small `jsDep` helper. Both suffixes are pinned by this build anyway.
- **Output lives under `target/out/`.** sbt 2 centralises build output as `target/out/{jvm,sjs1}/scala-3.8.4/<project>/`, not `<project>/target/scala-3.8.4/`. Task 12's Dockerfile depends on this.

No `.nvmrc`: the Node version is pinned by `flake.nix` (see Prerequisites), and a second declaration would only drift from it.

`build.sbt`:

```scala
import org.scalajs.linker.interface.{ESVersion, ModuleKind}

val scala3 = "3.8.4"

ThisBuild / scalaVersion := scala3
ThisBuild / organization := "dev.knirski"
ThisBuild / version      := "0.1.0-SNAPSHOT"

lazy val v = new {
  val gears          = "0.3.1"
  val tapir          = "1.13.29"
  val sttp           = "3.11.0"
  val jsoniter       = "2.39.1"
  val laminar        = "17.2.1"
  val scalajsDom     = "2.8.1"
  val magnum         = "1.3.1"
  val flyway         = "11.8.2"
  val postgres       = "42.7.7"
  val hikari         = "7.1.0"
  val argon2         = "2.12"
  val logback        = "1.6.0"
  val munit          = "1.3.4"
  val testcontainers = "1.21.3"
}

/** Names a Scala.js artifact explicitly, since sbt 2 has no `%%%`. The suffix
  * encodes Scala.js 1.x + Scala 3, both pinned by this build.
  */
def jsDep(org: String, artifact: String, version: String): ModuleID =
  org % s"${artifact}_sjs1_3" % version

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Wunused:all",
    "-Xfatal-warnings",
    "-source:3.8"
  )
)

// The shared module's real sources live in one place; the two platform projects
// below both compile them. This is what sbt-crossproject would have generated,
// written out by hand because it has no sbt 2 build.
lazy val sharedSources    = Def.setting((ThisBuild / baseDirectory).value / "shared" / "src" / "main" / "scala")
lazy val sharedTestSources = Def.setting((ThisBuild / baseDirectory).value / "shared" / "src" / "test" / "scala")

lazy val sharedSettings = commonSettings ++ Seq(
  Compile / unmanagedSourceDirectories += sharedSources.value,
  Test / unmanagedSourceDirectories += sharedTestSources.value
)

lazy val sharedJVM = project
  .in(file("shared/.jvm"))
  .settings(sharedSettings)
  .settings(
    name := "lm-bot-shared",
    libraryDependencies ++= Seq(
      "ch.epfl.lamp"                          %% "gears"                 % v.gears,
      "com.softwaremill.sttp.tapir"           %% "tapir-core"            % v.tapir,
      "com.softwaremill.sttp.tapir"           %% "tapir-jsoniter-scala"  % v.tapir,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core"   % v.jsoniter,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % v.jsoniter,
      "org.scalameta"                         %% "munit"                 % v.munit % Test
    )
  )

lazy val sharedJS = project
  .in(file("shared/.js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(sharedSettings)
  .settings(
    name := "lm-bot-shared-js",
    libraryDependencies ++= Seq(
      jsDep("ch.epfl.lamp", "gears", v.gears),
      jsDep("com.softwaremill.sttp.tapir", "tapir-core", v.tapir),
      jsDep("com.softwaremill.sttp.tapir", "tapir-jsoniter-scala", v.tapir),
      jsDep("com.github.plokhotnyuk.jsoniter-scala", "jsoniter-scala-core", v.jsoniter),
      jsDep("com.github.plokhotnyuk.jsoniter-scala", "jsoniter-scala-macros", v.jsoniter),
      jsDep("org.scalameta", "munit", v.munit) % Test
    ),
    scalaJSLinkerConfig ~= wasmConfig
  )

/** Gears on Scala.js needs the WebAssembly backend so JSPI can suspend (spec
  * §5.1). Three settings are required and all three are load-bearing:
  *
  *   - `withUseWebAssembly(true)` selects the Wasm backend.
  *     (`withExperimentalUseWebAssembly` is deprecated as of Scala.js 1.22.0.)
  *   - `ESVersion.ES2022` — Wasm refuses outright below it, with "The
  *     WebAssembly backend requires ECMAScript 2022 or later".
  *   - `withUseJSPI(true)` — **this one defaults to `false`**, and without it
  *     the linker rejects every `js.async`/`js.await` in Gears' internals with
  *     "Uses an async block without JSPI support in WebAssembly", or, if Wasm
  *     is off, "Uses an orphan await (outside of an async block) without
  *     targeting WebAssembly". Those two errors are the same missing flag wearing
  *     different hats. Gears cannot link on Scala.js without it, and no amount
  *     of restructuring the entry point substitutes for it.
  */
lazy val wasmConfig: org.scalajs.linker.interface.StandardConfig => org.scalajs.linker.interface.StandardConfig =
  _.withModuleKind(ModuleKind.ESModule)
    .withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true))
    .withWasmFeatures(_.withUseJSPI(true))

lazy val backend = project
  .in(file("backend"))
  .dependsOn(sharedJVM)
  .settings(commonSettings)
  .settings(
    name := "lm-bot-backend",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-jdkhttp-server" % v.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-files"          % v.tapir,
      "com.augustnagro"             %% "magnum"               % v.magnum,
      "org.flywaydb"                 % "flyway-core"          % v.flyway,
      "org.flywaydb"                 % "flyway-database-postgresql" % v.flyway,
      "org.postgresql"               % "postgresql"           % v.postgres,
      "com.zaxxer"                   % "HikariCP"             % v.hikari,
      "de.mkammerer"                 % "argon2-jvm"           % v.argon2,
      "ch.qos.logback"               % "logback-classic"      % v.logback,
      "org.scalameta"                %% "munit"                % v.munit          % Test,
      "org.testcontainers"           % "postgresql"           % v.testcontainers % Test,
      "com.softwaremill.sttp.client3" %% "core"               % v.sttp           % Test
    ),
    // Virtual threads and Testcontainers both want a real JVM 25+.
    javacOptions ++= Seq("-source", "25", "-target", "25"),
    Compile / mainClass := Some("lmbot.backend.Main")
  )

lazy val frontend = project
  .in(file("frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(sharedJS)
  .settings(commonSettings)
  .settings(
    name := "lm-bot-frontend",
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= wasmConfig,
    // No jsEnv override. Node 26 enables JSPI by default (WebAssembly.Suspending
    // is present with no flags) and *rejects* `--experimental-wasm-jspi` with
    // "node: bad option", so passing that flag would break every test.
    // flake.nix pins Node 26, so the default jsEnv is already correct.
    libraryDependencies ++= Seq(
      jsDep("ch.epfl.lamp", "gears", v.gears),
      jsDep("com.raquo", "laminar", v.laminar),
      jsDep("org.scala-js", "scalajs-dom", v.scalajsDom),
      jsDep("com.softwaremill.sttp.tapir", "tapir-sttp-client", v.tapir),
      jsDep("com.softwaremill.sttp.client3", "core", v.sttp),
      jsDep("org.scalameta", "munit", v.munit) % Test
    )
  )

lazy val root = project
  .in(file("."))
  .aggregate(sharedJVM, sharedJS, backend, frontend)
  .settings(name := "lm-bot", publish / skip := true)
```

`shared/src/main/scala/lmbot/shared/BuildInfo.scala`:

```scala
package lmbot.shared

object BuildInfo:
  val name: String = "lm-bot"
```

`.gitignore`:

```
target/
project/target/
project/project/
node_modules/
.bsp/
.metals/
.bloop/
*.log
.env
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt sharedJVM/testFull`
Expected: PASS — 1 test.

- [ ] **Step 5: Verify the whole build compiles, including the Wasm frontend**

Run: `sbt compile Test/compile frontend/fastLinkJS`
Expected: all four projects compile; `frontend/fastLinkJS` emits `main.wasm` under `target/out/sjs1/scala-3.8.4/lm-bot-frontend/lm-bot-frontend-fastopt/` (sbt 2 centralises output — see above).

**If linking fails, check `withUseJSPI(true)` is present in `wasmConfig` before concluding anything else.** It defaults to `false`, and its absence produces errors that read exactly like upstream incompatibilities — "Uses an async block without JSPI support in WebAssembly", or, with Wasm off, "Uses an orphan await (outside of an async block) without targeting WebAssembly" — with traces pointing deep into Gears (`WasmJSPISuspend`, `JsAsyncScheduler`, `NumberedLockImpl.lock`). Those traces are misleading: the flag is the cause and Gears is not at fault. This exact misdiagnosis has already cost one implementation attempt.

Verified working: Gears 0.3.1 on Scala.js 1.22.0 with all three flags set — `fastLinkJS` and `fullLinkJS` both emit `main.wasm`, and the `Runtime` and `Bridge` suites pass under Node 26.

**Linking successfully is not evidence that the frontend works.** JSPI has a second requirement that the linker cannot check: a suspension is legal only if the Wasm stack was entered through a `WebAssembly.promising` wrapper. A synchronous `@main` export and a DOM event callback both fail that test at *runtime*, with `SuspendError: trying to suspend without WebAssembly.promising`. Two consequences:

- Use `JsAsyncFromSync` in `Main`, **not** `UnsafeJsAsyncFromSync`. The latter's name is the warning: it assumes a surrounding async context, which `@main` does not provide, so the app renders and then hangs on its first suspension. Both variants link identically, so only Task 12 Step 7 catches the difference.
- With `JsAsyncFromSync` in place, the rest of the architecture — DOM handlers, `dispatch`, the event loop, effect-spawned fibers — is verified working in a browser. A stray `SuspendError` is not by itself grounds to suspect Gears or the design; see Task 12 Step 7.

If linking still fails *after* all three flags are confirmed, that is the risk called out in spec §5.1 and §10. Two rules then apply. Do not silently drop to the JS backend. And note that switching `ModuleKind` to `CommonJSModule` while leaving Gears in the source is **not** the documented fallback — it yields a build in which Gears' own async cannot link, forcing you to disable tests to stay green. The real fallback removes Gears from the frontend: keep Laminar and the Elm architecture, and run effects on `scala.concurrent.Future`. Either way, stop and report so the choice is deliberate.

- [ ] **Step 6: Add CI**

`.github/workflows/ci.yml`:

CI runs everything **inside the same flake devShell** used locally. That parity is worth more here than on an average project: the stack depends on an exact Node major, a JSPI-capable runtime, and a Scala.js Wasm linker, and "works on my machine" would otherwise be a real failure mode.

```yaml
name: CI

on:
  push:
  pull_request:

jobs:
  build:
    # GitHub's runners ship a working Docker, which Testcontainers finds on its
    # own. No postgres service is declared: Testcontainers starts its own.
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - uses: DeterminateSystems/nix-installer-action@main

      - uses: DeterminateSystems/magic-nix-cache-action@main

      - name: Cache sbt and coursier
        uses: actions/cache@v4
        with:
          path: |
            ~/.cache/coursier
            ~/.sbt
          key: ${{ runner.os }}-sbt-${{ hashFiles('build.sbt', 'project/**') }}

      # The devShell supplies the JDK, the sbt launcher and Node 26. It only
      # overrides DOCKER_HOST when a Podman socket exists, so on this runner it
      # correctly falls through to the host Docker.
      - name: Compile everything
        run: nix develop --command sbt compile Test/compile

      - name: Test
        run: nix develop --command sbt testFull

      - name: Link frontend (Wasm)
        run: nix develop --command sbt frontend/fullLinkJS

      - name: Check formatting
        run: nix develop --command scalafmt --list --mode diff-ref=HEAD
```

`scalafmt` comes from the devShell rather than an sbt plugin — same result, one fewer plugin. Add a `.scalafmt.conf` with `version = 3.11.4` and `runner.dialect = scala3` when you first run it.

**Why `testFull` and not `test`.** In sbt 2, `test` *is* `testQuick` — the rename was deliberate ([sbt#7685](https://github.com/sbt/sbt/issues/7685), [sbt#7686](https://github.com/sbt/sbt/issues/7686)) — so it runs only suites that failed before, were never run, or whose transitive dependencies changed. Staleness is judged by **content hashing** against a **global action cache in `~/.cache/sbt/v2/`**, which survives both `clean` and deleting `target/`.

The practical consequences are worth knowing before they confuse you:

- Re-running `sbt test` with no source edits prints `Passed: Total 0` and `[success]`. That is correct behaviour, not a failure — but it is easy to misread as "the suite passed", so never take a `Total 0` run as evidence that anything works.
- `touch`ing a file changes nothing, because only content is hashed.
- `testFull` ignores the cache and runs everything. CI uses it so that a green build proves the whole suite passed *for that commit*, independent of cache state. At 92 tests in about ten seconds, determinism is worth more than the saved time.
- The cache action above intentionally does **not** cache `~/.cache/sbt`. If you ever add it, keep CI on `testFull`, or a green run will stop meaning "the suite passes".

- [ ] **Step 7: Commit**

```bash
git add build.sbt project .gitignore .github shared
git commit -m "build: cross-compiled sbt skeleton with Wasm frontend and CI"
```

---

### Task 2: Shared domain types and the error ADT

**Files:**
- Create: `shared/src/main/scala/lmbot/shared/domain/Role.scala`
- Create: `shared/src/main/scala/lmbot/shared/domain/UserView.scala`
- Create: `shared/src/main/scala/lmbot/shared/api/ApiError.scala`
- Create: `shared/src/main/scala/lmbot/shared/api/AuthPayloads.scala`
- Test: `shared/src/test/scala/lmbot/shared/ApiErrorTest.scala`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `enum Role { case Admin, User }` with `Role.fromString(s: String): Option[Role]` and `Role.asString(r: Role): String`.
  - `case class UserView(id: Long, username: String, displayName: String, role: Role, telegramLinked: Boolean)`.
  - `enum ApiError(val status: Int, val code: String, val message: String)` with cases `Unauthorized`, `Forbidden`, `NotFound`, `Conflict(detail)`, `Validation(detail)`, and `ApiError.fromWire(status: Int, code: String, message: String): ApiError`.
  - `case class LoginRequest(username: String, password: String)`.

`ApiError` carries its own HTTP status so that a *single* Tapir error output can serve every endpoint in both directions (Task 3 depends on this) — no `oneOf` variant list to keep in sync as errors are added.

- [ ] **Step 1: Write the failing test**

`shared/src/test/scala/lmbot/shared/ApiErrorTest.scala`:

```scala
package lmbot.shared

import lmbot.shared.api.ApiError
import lmbot.shared.domain.Role

class ApiErrorTest extends munit.FunSuite:

  test("each error carries its HTTP status"):
    assertEquals(ApiError.Unauthorized.status, 401)
    assertEquals(ApiError.Forbidden.status, 403)
    assertEquals(ApiError.NotFound.status, 404)
    assertEquals(ApiError.Conflict("dup").status, 409)
    assertEquals(ApiError.Validation("bad").status, 422)

  test("detail-carrying errors expose the detail as the message"):
    assertEquals(ApiError.Conflict("username taken").message, "username taken")
    assertEquals(ApiError.Validation("too short").message, "too short")

  test("fromWire round-trips every case"):
    val all = List(
      ApiError.Unauthorized,
      ApiError.Forbidden,
      ApiError.NotFound,
      ApiError.Conflict("dup"),
      ApiError.Validation("bad")
    )
    all.foreach: e =>
      assertEquals(ApiError.fromWire(e.status, e.code, e.message), e)

  test("fromWire degrades unknown codes rather than throwing"):
    val unknown = ApiError.fromWire(418, "teapot", "short and stout")
    assertEquals(unknown.status, 500)
    assert(unknown.message.contains("teapot"))

  test("Role maps to and from its wire string"):
    assertEquals(Role.asString(Role.Admin), "admin")
    assertEquals(Role.asString(Role.User), "user")
    assertEquals(Role.fromString("admin"), Some(Role.Admin))
    assertEquals(Role.fromString("user"), Some(Role.User))
    assertEquals(Role.fromString("root"), None)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt sharedJVM/testOnly lmbot.shared.ApiErrorTest`
Expected: FAIL — `lmbot.shared.api.ApiError` and `lmbot.shared.domain.Role` do not exist.

- [ ] **Step 3: Write the implementation**

`shared/src/main/scala/lmbot/shared/domain/Role.scala`:

```scala
package lmbot.shared.domain

enum Role:
  case Admin, User

object Role:
  def asString(role: Role): String = role match
    case Admin => "admin"
    case User  => "user"

  def fromString(s: String): Option[Role] = s match
    case "admin" => Some(Admin)
    case "user"  => Some(User)
    case _       => None
```

`shared/src/main/scala/lmbot/shared/domain/UserView.scala`:

```scala
package lmbot.shared.domain

/** What the API is willing to say about a user. Deliberately excludes the
  * password hash and anything else the browser has no business seeing.
  */
case class UserView(
  id: Long,
  username: String,
  displayName: String,
  role: Role,
  telegramLinked: Boolean
)
```

`shared/src/main/scala/lmbot/shared/api/ApiError.scala` — note the `Unexpected` case: the test requires an unknown wire code to report status 500, and `Validation` is a 422, so a dedicated 500-valued case is needed to land it:

```scala
package lmbot.shared.api

/** Every failure the API can express, with the HTTP status baked in.
  *
  * Keeping the status on the error lets one Tapir error output serve every
  * endpoint (see AuthEndpoints.errorOut) instead of a `oneOf` variant list
  * that has to be extended in lockstep with this enum.
  */
enum ApiError(val status: Int, val code: String, val message: String):
  case Unauthorized               extends ApiError(401, "unauthorized", "Not authenticated")
  case Forbidden                  extends ApiError(403, "forbidden", "Not allowed")
  case NotFound                   extends ApiError(404, "not_found", "Not found")
  case Conflict(detail: String)   extends ApiError(409, "conflict", detail)
  case Validation(detail: String) extends ApiError(422, "validation", detail)
  case Unexpected(detail: String) extends ApiError(500, "unexpected", detail)

object ApiError:
  /** Rebuild an error from the wire. Unknown codes become `Unexpected` rather
    * than an exception: a server that grows a new error must not crash an old
    * client.
    */
  def fromWire(status: Int, code: String, message: String): ApiError = code match
    case "unauthorized" => Unauthorized
    case "forbidden"    => Forbidden
    case "not_found"    => NotFound
    case "conflict"     => Conflict(message)
    case "validation"   => Validation(message)
    case "unexpected"   => Unexpected(message)
    case other          => Unexpected(s"unrecognised error [$other/$status]: $message")
```

`shared/src/main/scala/lmbot/shared/api/AuthPayloads.scala` — `password` is hidden from `toString` because these values pass through logging-adjacent code and spec §6 requires credentials never be logged:

```scala
package lmbot.shared.api

case class LoginRequest(username: String, password: String):
  override def toString: String = s"LoginRequest($username, ***)"
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt sharedJVM/testOnly lmbot.shared.ApiErrorTest`
Expected: PASS — 5 tests.

- [ ] **Step 5: Verify it cross-compiles**

Run: `sbt sharedJS/Test/compile`
Expected: success. These types must be usable from the browser; a JVM-only import here would surface now.

- [ ] **Step 6: Commit**

```bash
git add shared
git commit -m "feat(shared): domain types and API error ADT"
```

---

### Task 3: Codecs and Tapir endpoint descriptions

**Files:**
- Create: `shared/src/main/scala/lmbot/shared/api/Codecs.scala`
- Create: `shared/src/main/scala/lmbot/shared/api/AuthEndpoints.scala`
- Test: `shared/src/test/scala/lmbot/shared/CodecRoundTripTest.scala`

**Interfaces:**
- Consumes: `Role`, `UserView`, `ApiError`, `LoginRequest` (Task 2).
- Produces:
  - `object Codecs` with `given JsonValueCodec[LoginRequest]`, `given JsonValueCodec[UserView]`, `given JsonValueCodec[ErrorBody]`, and the matching `given Schema[...]` instances.
  - `case class ErrorBody(code: String, message: String)`.
  - `object AuthEndpoints` with `sessionCookieName: String = "lmbot_session"`, and endpoints `login`, `me`, `logout`.

Endpoint types, which Tasks 8, 9 and 11 all bind against:
- `login: Endpoint[Unit, LoginRequest, ApiError, (UserView, Option[CookieValueWithMeta]), Any]`
- `me: Endpoint[Option[String], Unit, ApiError, UserView, Any]` (security input is the session cookie)
- `logout: Endpoint[Option[String], Unit, ApiError, Option[CookieValueWithMeta], Any]`

**The cookie outputs must be `setCookieOpt`, never `setCookie`.** In tapir, `setCookie(name)` is `setCookieOpt(name)` plus a decode that *fails* when the header is absent — and `Set-Cookie` is a forbidden response header for browser JavaScript, which the Fetch spec never exposes. Because the browser client (Task 9) is derived from these same endpoints, `setCookie` makes every **successful** login undecodable on the client: the server returns 200, the browser stores the cookie, and the client still fails with `DecodeResult.Missing` → "Cannot decode: Missing". The optional form decodes to `None` in the browser and `Some(...)` on the server, matching the real asymmetry. This cost a full round of debugging; it presents as a login that silently never completes.

- [ ] **Step 1: Write the failing test**

`shared/src/test/scala/lmbot/shared/CodecRoundTripTest.scala`:

```scala
package lmbot.shared

import com.github.plokhotnyuk.jsoniter_scala.core.*
import lmbot.shared.api.*
import lmbot.shared.api.Codecs.given
import lmbot.shared.domain.{Role, UserView}

class CodecRoundTripTest extends munit.FunSuite:

  test("LoginRequest round-trips"):
    val original = LoginRequest("krzysiek", "correct horse battery staple")
    val json     = writeToString(original)
    assertEquals(readFromString[LoginRequest](json), original)

  test("UserView round-trips for both roles"):
    List(Role.Admin, Role.User).foreach: role =>
      val original = UserView(7L, "mom", "Mom", role, telegramLinked = true)
      assertEquals(readFromString[UserView](writeToString(original)), original)

  test("ErrorBody round-trips"):
    val original = ErrorBody("conflict", "username taken")
    assertEquals(readFromString[ErrorBody](writeToString(original)), original)

  test("a login request does not serialise its password into the log-friendly toString"):
    // The wire format must carry the password; the *rendering* must not.
    val req = LoginRequest("krzysiek", "s3cret")
    assert(!req.toString.contains("s3cret"), s"password leaked in toString: ${req.toString}")

  test("endpoints are described with the expected methods and paths"):
    import sttp.tapir.*
    assertEquals(AuthEndpoints.login.showPathTemplate(), "/api/auth/login")
    assertEquals(AuthEndpoints.me.showPathTemplate(), "/api/auth/me")
    assertEquals(AuthEndpoints.logout.showPathTemplate(), "/api/auth/logout")
    assertEquals(AuthEndpoints.sessionCookieName, "lmbot_session")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt sharedJVM/testOnly lmbot.shared.CodecRoundTripTest`
Expected: FAIL — `Codecs` and `AuthEndpoints` do not exist.

- [ ] **Step 3: Write the implementation**

`shared/src/main/scala/lmbot/shared/api/Codecs.scala`:

```scala
package lmbot.shared.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import lmbot.shared.domain.{Role, UserView}
import sttp.tapir.Schema

/** The wire body for a failure. `ApiError` itself is not serialised directly:
  * the status travels as an HTTP status code, so only code and message go in
  * the body.
  */
case class ErrorBody(code: String, message: String)

object Codecs:
  private val config = CodecMakerConfig.withTransientDefault(false)

  given JsonValueCodec[Role]         = JsonCodecMaker.make(config)
  given JsonValueCodec[UserView]     = JsonCodecMaker.make(config)
  given JsonValueCodec[LoginRequest] = JsonCodecMaker.make(config)
  given JsonValueCodec[ErrorBody]    = JsonCodecMaker.make(config)

  given Schema[Role]         = Schema.derivedEnumeration[Role].defaultStringBased
  given Schema[UserView]     = Schema.derived
  given Schema[LoginRequest] = Schema.derived
  given Schema[ErrorBody]    = Schema.derived
```

`shared/src/main/scala/lmbot/shared/api/AuthEndpoints.scala`:

```scala
package lmbot.shared.api

import lmbot.shared.api.Codecs.given
import lmbot.shared.domain.UserView
import sttp.model.StatusCode
import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*

/** Endpoint descriptions only — no logic, per spec §5.7.4. Both the server
  * (Task 8) and the browser client (Task 9) are derived from these.
  */
object AuthEndpoints:

  val sessionCookieName: String = "lmbot_session"

  /** One error output for every endpoint. `ApiError` knows its own status, so
    * this maps cleanly in both directions without a `oneOf` variant list.
    */
  private val errorOut: EndpointOutput[ApiError] =
    statusCode
      .and(jsonBody[ErrorBody])
      .map[ApiError] { case (sc, body) => ApiError.fromWire(sc.code, body.code, body.message) } { e =>
        (StatusCode(e.status), ErrorBody(e.code, e.message))
      }

  private val base = endpoint.in("api" / "auth").errorOut(errorOut)

  /** The session cookie is `HttpOnly`, so browser JS cannot read it. The client
    * therefore always passes `None` here and lets the browser attach the real
    * cookie itself; the server reads whatever actually arrived.
    */
  private val securedBase = base.securityIn(cookie[Option[String]](sessionCookieName))

  /** `setCookieOpt`, not `setCookie`: the latter fails to decode when the header
    * is absent, and browser JS can never see `Set-Cookie` (forbidden response
    * header), so the derived client would reject every successful login.
    */
  val login: Endpoint[Unit, LoginRequest, ApiError, (UserView, Option[CookieValueWithMeta]), Any] =
    base.post
      .in("login")
      .in(jsonBody[LoginRequest])
      .out(jsonBody[UserView])
      .out(setCookieOpt(sessionCookieName))

  val me: Endpoint[Option[String], Unit, ApiError, UserView, Any] =
    securedBase.get
      .in("me")
      .out(jsonBody[UserView])

  val logout: Endpoint[Option[String], Unit, ApiError, Option[CookieValueWithMeta], Any] =
    securedBase.post
      .in("logout")
      .out(setCookieOpt(sessionCookieName))
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt sharedJVM/testOnly lmbot.shared.CodecRoundTripTest`
Expected: PASS — 5 tests.

- [ ] **Step 5: Verify the contract cross-compiles to Scala.js**

Run: `sbt sharedJS/Test/compile sharedJS/testFull`
Expected: success, tests pass under Node. This is the check that the API contract is genuinely shared rather than JVM-only.

- [ ] **Step 6: Commit**

```bash
git add shared
git commit -m "feat(shared): jsoniter codecs and tapir auth endpoint descriptions"
```

---

### Task 4: Schema migration and Magnum repositories

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__init.sql`
- Create: `backend/src/main/scala/lmbot/backend/db/Database.scala`
- Create: `backend/src/main/scala/lmbot/backend/db/Rows.scala`
- Create: `backend/src/main/scala/lmbot/backend/db/UserRepo.scala`
- Create: `backend/src/main/scala/lmbot/backend/db/SessionRepo.scala`
- Create: `backend/src/test/scala/lmbot/backend/support/PostgresSuite.scala`
- Test: `backend/src/test/scala/lmbot/backend/UserRepoTest.scala`, `backend/src/test/scala/lmbot/backend/SessionRepoTest.scala`

**Interfaces:**
- Consumes: `Role`, `UserView` (Task 2).
- Produces:
  - `Database.dataSource(url, user, password): HikariDataSource`, `Database.migrate(ds): Unit`, `Database.transactor(ds): Transactor`.
  - `UserRow`, `SessionRow` (persistence shapes; `role` stored as `String`, converted at the repo boundary so Magnum never leaks into `shared`).
  - `class UserRepo(xa: Transactor)` with `count(): Long`, `findByUsername(String): Option[UserRow]`, `findById(Long): Option[UserRow]`, `insert(username, displayName, passwordHash, role): UserRow`.
  - `class SessionRepo(xa: Transactor)` with `insert(tokenHash, userId, expiresAt): Unit`, `find(tokenHash): Option[SessionRow]`, `delete(tokenHash): Unit`, `deleteExpired(now): Int`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/scala/lmbot/backend/support/PostgresSuite.scala`:

```scala
package lmbot.backend.support

import com.augustnagro.magnum.{Transactor, transact, sql}
import com.zaxxer.hikari.HikariDataSource
import lmbot.backend.db.Database
import org.testcontainers.containers.PostgreSQLContainer

/** One container per suite, schema migrated once, tables truncated between
  * tests so each test starts from a known empty database.
  */
abstract class PostgresSuite extends munit.FunSuite:

  private var container: PostgreSQLContainer[?] = scala.compiletime.uninitialized
  private var ds: HikariDataSource              = scala.compiletime.uninitialized
  protected var xa: Transactor                  = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    container = new PostgreSQLContainer("postgres:17")
    container.start()
    ds = Database.dataSource(container.getJdbcUrl, container.getUsername, container.getPassword)
    Database.migrate(ds)
    xa = Database.transactor(ds)

  override def afterAll(): Unit =
    if ds != null then ds.close()
    if container != null then container.stop()

  override def beforeEach(context: BeforeEach): Unit =
    transact(xa):
      sql"truncate table sessions, users restart identity cascade".update.run()
    ()
```

`backend/src/test/scala/lmbot/backend/UserRepoTest.scala`:

```scala
package lmbot.backend

import lmbot.backend.db.UserRepo
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.Role

class UserRepoTest extends PostgresSuite:

  test("an empty database has no users"):
    assertEquals(UserRepo(xa).count(), 0L)

  test("an inserted user can be found by username and by id"):
    val repo   = UserRepo(xa)
    val stored = repo.insert("krzysiek", "Krzysiek", "hash-1", Role.Admin)

    assertEquals(repo.count(), 1L)
    assertEquals(repo.findByUsername("krzysiek").map(_.id), Some(stored.id))
    assertEquals(repo.findById(stored.id).map(_.username), Some("krzysiek"))
    assertEquals(stored.role, "admin")
    assertEquals(stored.disabled, false)
    assertEquals(stored.telegramChatId, None)

  test("usernames are unique"):
    val repo = UserRepo(xa)
    repo.insert("krzysiek", "Krzysiek", "hash-1", Role.Admin)
    intercept[Exception]:
      repo.insert("krzysiek", "Impostor", "hash-2", Role.User)

  test("an unknown username yields None rather than throwing"):
    assertEquals(UserRepo(xa).findByUsername("nobody"), None)
```

`backend/src/test/scala/lmbot/backend/SessionRepoTest.scala`:

```scala
package lmbot.backend

import lmbot.backend.db.{SessionRepo, UserRepo}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.Role

import java.time.{Duration, OffsetDateTime}

class SessionRepoTest extends PostgresSuite:

  private def aUser(): Long =
    UserRepo(xa).insert("krzysiek", "Krzysiek", "hash-1", Role.Admin).id

  test("a stored session is retrievable by its token hash"):
    val repo   = SessionRepo(xa)
    val userId = aUser()
    val expiry = OffsetDateTime.now().plusDays(7)

    repo.insert("token-hash-1", userId, expiry)

    val found = repo.find("token-hash-1")
    assertEquals(found.map(_.userId), Some(userId))

  test("an unknown token hash yields None"):
    assertEquals(SessionRepo(xa).find("nope"), None)

  test("deleting a session revokes it"):
    val repo   = SessionRepo(xa)
    val userId = aUser()
    repo.insert("token-hash-1", userId, OffsetDateTime.now().plusDays(7))

    repo.delete("token-hash-1")

    assertEquals(repo.find("token-hash-1"), None)

  test("deleteExpired removes only sessions already past their expiry"):
    val repo   = SessionRepo(xa)
    val userId = aUser()
    val now    = OffsetDateTime.now()
    repo.insert("stale", userId, now.minus(Duration.ofMinutes(1)))
    repo.insert("fresh", userId, now.plusDays(7))

    val removed = repo.deleteExpired(now)

    assertEquals(removed, 1)
    assertEquals(repo.find("stale"), None)
    assert(repo.find("fresh").isDefined)

  test("deleting a user cascades to their sessions"):
    val sessions = SessionRepo(xa)
    val users    = UserRepo(xa)
    val userId   = aUser()
    sessions.insert("token-hash-1", userId, OffsetDateTime.now().plusDays(7))

    users.deleteById(userId)

    assertEquals(sessions.find("token-hash-1"), None)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt "backend/testOnly lmbot.backend.UserRepoTest lmbot.backend.SessionRepoTest"`
Expected: FAIL — `Database`, `UserRepo`, `SessionRepo` do not exist.

- [ ] **Step 3: Write the migration**

`backend/src/main/resources/db/migration/V1__init.sql`:

```sql
create table users (
    id               bigserial   primary key,
    username         text        not null unique,
    display_name     text        not null,
    password_hash    text        not null,
    role             text        not null check (role in ('admin', 'user')),
    telegram_chat_id bigint,
    disabled         boolean     not null default false,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);

-- The opaque session token is never stored; only its hash is, so a database
-- leak does not hand over live sessions. Revocation is a row delete.
create table sessions (
    token_hash text        primary key,
    user_id    bigint      not null references users (id) on delete cascade,
    expires_at timestamptz not null,
    created_at timestamptz not null default now()
);

create index sessions_user_id_idx on sessions (user_id);
create index sessions_expires_at_idx on sessions (expires_at);
```

- [ ] **Step 4: Write the database wiring and repositories**

`backend/src/main/scala/lmbot/backend/db/Database.scala`:

```scala
package lmbot.backend.db

import com.augustnagro.magnum.Transactor
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.flywaydb.core.Flyway

object Database:

  def dataSource(url: String, user: String, password: String): HikariDataSource =
    val config = new HikariConfig()
    config.setJdbcUrl(url)
    config.setUsername(user)
    config.setPassword(password)
    // Family scale: a small pool is plenty. Blocking JDBC runs on virtual
    // threads, so the pool — not the thread count — is the real limit.
    config.setMaximumPoolSize(10)
    config.setPoolName("lmbot-pool")
    new HikariDataSource(config)

  def migrate(ds: HikariDataSource): Unit =
    Flyway.configure().dataSource(ds).load().migrate()
    ()

  def transactor(ds: HikariDataSource): Transactor = Transactor(ds)
```

`backend/src/main/scala/lmbot/backend/db/Rows.scala`:

```scala
package lmbot.backend.db

import com.augustnagro.magnum.{DbCodec, Id, PostgresDbType, SqlNameMapper, Table}

import java.time.OffsetDateTime

/** Persistence shapes. `role` is a `String` rather than the shared `Role` enum
  * on purpose: deriving a Magnum `DbCodec` for `Role` would drag a JVM-only
  * dependency into the cross-compiled `shared` module. Conversion happens at
  * the repository boundary instead.
  */
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class UserRow(
  @Id id: Long,
  username: String,
  displayName: String,
  passwordHash: String,
  role: String,
  telegramChatId: Option[Long],
  disabled: Boolean,
  createdAt: OffsetDateTime,
  updatedAt: OffsetDateTime
) derives DbCodec

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class SessionRow(
  @Id tokenHash: String,
  userId: Long,
  expiresAt: OffsetDateTime,
  createdAt: OffsetDateTime
) derives DbCodec
```

`backend/src/main/scala/lmbot/backend/db/UserRepo.scala`:

```scala
package lmbot.backend.db

import com.augustnagro.magnum.{Transactor, connect, sql, transact}
import lmbot.shared.domain.Role

class UserRepo(xa: Transactor):

  def count(): Long = connect(xa):
    sql"select count(*) from users".query[Long].run().head

  def findByUsername(username: String): Option[UserRow] = connect(xa):
    sql"select * from users where username = $username".query[UserRow].run().headOption

  def findById(id: Long): Option[UserRow] = connect(xa):
    sql"select * from users where id = $id".query[UserRow].run().headOption

  def insert(username: String, displayName: String, passwordHash: String, role: Role): UserRow =
    val roleStr = Role.asString(role)
    transact(xa):
      sql"""insert into users (username, display_name, password_hash, role)
            values ($username, $displayName, $passwordHash, $roleStr)
            returning *"""
        .query[UserRow]
        .run()
        .head

  def deleteById(id: Long): Unit = transact(xa):
    sql"delete from users where id = $id".update.run()
    ()
```

`backend/src/main/scala/lmbot/backend/db/SessionRepo.scala`:

```scala
package lmbot.backend.db

import com.augustnagro.magnum.{Transactor, connect, sql, transact}

import java.time.OffsetDateTime

class SessionRepo(xa: Transactor):

  def insert(tokenHash: String, userId: Long, expiresAt: OffsetDateTime): Unit = transact(xa):
    sql"""insert into sessions (token_hash, user_id, expires_at)
          values ($tokenHash, $userId, $expiresAt)""".update.run()
    ()

  def find(tokenHash: String): Option[SessionRow] = connect(xa):
    sql"select * from sessions where token_hash = $tokenHash".query[SessionRow].run().headOption

  def delete(tokenHash: String): Unit = transact(xa):
    sql"delete from sessions where token_hash = $tokenHash".update.run()
    ()

  def deleteExpired(now: OffsetDateTime): Int = transact(xa):
    sql"delete from sessions where expires_at < $now".update.run()
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `sbt "backend/testOnly lmbot.backend.UserRepoTest lmbot.backend.SessionRepoTest"`
Expected: PASS — 9 tests.

A container runtime must be reachable. If this fails with "Could not find a valid Docker environment", you are outside the devShell — it is what points Testcontainers at rootless Podman (see Prerequisites). Re-enter with `direnv allow` or `nix develop` and confirm `$DOCKER_HOST` is set.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(backend): users/sessions schema and Magnum repositories"
```

---

### Task 5: Password hashing and session tokens

**Files:**
- Create: `backend/src/main/scala/lmbot/backend/auth/Passwords.scala`
- Create: `backend/src/main/scala/lmbot/backend/auth/Tokens.scala`
- Test: `backend/src/test/scala/lmbot/backend/PasswordsTest.scala`, `backend/src/test/scala/lmbot/backend/TokensTest.scala`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `object Passwords` with `hash(plain: String): String` and `verify(hash: String, plain: String): Boolean`.
  - `object Tokens` with `generate(): String` (opaque, URL-safe, 256 bits of entropy) and `hash(token: String): String` (SHA-256, URL-safe base64).

- [ ] **Step 1: Write the failing tests**

`backend/src/test/scala/lmbot/backend/PasswordsTest.scala`:

```scala
package lmbot.backend

import lmbot.backend.auth.Passwords

class PasswordsTest extends munit.FunSuite:

  test("a hashed password verifies against its plaintext"):
    val hash = Passwords.hash("correct horse battery staple")
    assert(Passwords.verify(hash, "correct horse battery staple"))

  test("a wrong password does not verify"):
    val hash = Passwords.hash("correct horse battery staple")
    assert(!Passwords.verify(hash, "Correct horse battery staple"))
    assert(!Passwords.verify(hash, ""))

  test("the hash is Argon2id and does not contain the plaintext"):
    val hash = Passwords.hash("s3cret")
    assert(hash.startsWith("$argon2id$"), s"not argon2id: $hash")
    assert(!hash.contains("s3cret"))

  test("hashing the same password twice yields different hashes (unique salts)"):
    assertNotEquals(Passwords.hash("same"), Passwords.hash("same"))

  test("verify returns false for a malformed hash instead of throwing"):
    assert(!Passwords.verify("not-a-hash", "whatever"))
```

`backend/src/test/scala/lmbot/backend/TokensTest.scala`:

```scala
package lmbot.backend

import lmbot.backend.auth.Tokens

class TokensTest extends munit.FunSuite:

  test("generated tokens are long, URL-safe and unique"):
    val tokens = List.fill(100)(Tokens.generate())
    assertEquals(tokens.distinct.size, 100)
    tokens.foreach: t =>
      assert(t.length >= 40, s"suspiciously short token: $t")
      assert(t.matches("^[A-Za-z0-9_-]+$"), s"not URL-safe: $t")

  test("hashing is deterministic"):
    val token = Tokens.generate()
    assertEquals(Tokens.hash(token), Tokens.hash(token))

  test("the hash differs from the token, so a database leak reveals no live session"):
    val token = Tokens.generate()
    assertNotEquals(Tokens.hash(token), token)

  test("different tokens hash differently"):
    assertNotEquals(Tokens.hash(Tokens.generate()), Tokens.hash(Tokens.generate()))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt "backend/testOnly lmbot.backend.PasswordsTest lmbot.backend.TokensTest"`
Expected: FAIL — `Passwords` and `Tokens` do not exist.

- [ ] **Step 3: Write the implementation**

`backend/src/main/scala/lmbot/backend/auth/Passwords.scala`:

```scala
package lmbot.backend.auth

import de.mkammerer.argon2.{Argon2, Argon2Factory}
import de.mkammerer.argon2.Argon2Factory.Argon2Types

import scala.util.Try

object Passwords:
  private val argon2: Argon2 = Argon2Factory.create(Argon2Types.ARGON2id)

  // OWASP-style baseline: 3 passes over 64 MiB, one lane. Family scale means
  // logins are rare, so favour cost over throughput.
  private val Iterations  = 3
  private val MemoryKiB   = 65536
  private val Parallelism = 1

  def hash(plain: String): String =
    val chars = plain.toCharArray
    try argon2.hash(Iterations, MemoryKiB, Parallelism, chars)
    finally argon2.wipeArray(chars)

  /** Returns false — never throws — for a malformed stored hash, so a corrupt
    * row denies access rather than crashing the request.
    */
  def verify(hash: String, plain: String): Boolean =
    val chars = plain.toCharArray
    try Try(argon2.verify(hash, chars)).getOrElse(false)
    finally argon2.wipeArray(chars)
```

`backend/src/main/scala/lmbot/backend/auth/Tokens.scala`:

```scala
package lmbot.backend.auth

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

object Tokens:
  private val rng     = new SecureRandom()
  private val encoder = Base64.getUrlEncoder.withoutPadding

  /** 256 bits of entropy, URL-safe so it can live in a cookie unescaped. The
    * token is opaque: it carries no user identity and cannot be forged.
    */
  def generate(): String =
    val bytes = new Array[Byte](32)
    rng.nextBytes(bytes)
    encoder.encodeToString(bytes)

  /** Only the hash is persisted (see V1__init.sql). SHA-256 is right here
    * rather than Argon2: the input is already high-entropy random, so there is
    * nothing to slow down a guesser about, and lookups stay cheap.
    */
  def hash(token: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8))
    encoder.encodeToString(digest)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbt "backend/testOnly lmbot.backend.PasswordsTest lmbot.backend.TokensTest"`
Expected: PASS — 9 tests.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(backend): Argon2id password hashing and opaque session tokens"
```

---

### Task 6: AuthService

**Files:**
- Create: `backend/src/main/scala/lmbot/backend/auth/AuthService.scala`
- Test: `backend/src/test/scala/lmbot/backend/AuthServiceTest.scala`

**Interfaces:**
- Consumes: `UserRepo`, `SessionRepo`, `UserRow` (Task 4); `Passwords`, `Tokens` (Task 5); `ApiError`, `Role`, `UserView` (Task 2).
- Produces:
  - `case class AuthedUser(id: Long, username: String, displayName: String, role: Role, telegramLinked: Boolean)` with `def toView: UserView`.
  - `class AuthService(users: UserRepo, sessions: SessionRepo, sessionTtl: Duration, now: () => OffsetDateTime)` with:
    - `login(username: String, password: String): Either[ApiError, (UserView, String)]` — the `String` is the raw token for the cookie.
    - `authenticate(token: Option[String]): Either[ApiError, AuthedUser]`
    - `logout(token: Option[String]): Unit`

This is where authorization policy lives (spec §6: "Authorization in the service layer, not just the UI").

- [ ] **Step 1: Write the failing test**

`backend/src/test/scala/lmbot/backend/AuthServiceTest.scala`:

```scala
package lmbot.backend

import com.augustnagro.magnum.{sql, transact}
import lmbot.backend.auth.{AuthService, Passwords, Tokens}
import lmbot.backend.db.{SessionRepo, UserRepo}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.api.ApiError
import lmbot.shared.domain.Role

import java.time.{Duration, OffsetDateTime}

class AuthServiceTest extends PostgresSuite:

  private val ttl = Duration.ofDays(7)

  private def service(now: () => OffsetDateTime = () => OffsetDateTime.now()): AuthService =
    AuthService(UserRepo(xa), SessionRepo(xa), ttl, now)

  private def aUser(
    username: String = "krzysiek",
    password: String = "s3cret",
    role: Role = Role.User
  ): Long =
    UserRepo(xa).insert(username, "Krzysiek", Passwords.hash(password), role).id

  test("login with correct credentials returns the user and a token"):
    aUser()
    val result = service().login("krzysiek", "s3cret")

    result match
      case Right((view, token)) =>
        assertEquals(view.username, "krzysiek")
        assertEquals(view.role, Role.User)
        assert(token.nonEmpty)
      case Left(e) => fail(s"expected success, got $e")

  test("login stores only the hash of the token, never the token"):
    aUser()
    val Right((_, token)) = service().login("krzysiek", "s3cret"): @unchecked

    assert(SessionRepo(xa).find(Tokens.hash(token)).isDefined)
    assertEquals(SessionRepo(xa).find(token), None)

  test("login with a wrong password is Unauthorized"):
    aUser()
    assertEquals(service().login("krzysiek", "wrong"), Left(ApiError.Unauthorized))

  test("login for an unknown user is Unauthorized, not NotFound"):
    // Distinguishing the two would let an attacker enumerate usernames.
    assertEquals(service().login("ghost", "s3cret"), Left(ApiError.Unauthorized))

  test("a disabled user cannot log in even with the right password"):
    val id = aUser()
    transact(xa):
      sql"update users set disabled = true where id = $id".update.run()

    assertEquals(service().login("krzysiek", "s3cret"), Left(ApiError.Forbidden))

  test("authenticate accepts a token minted by login"):
    aUser(role = Role.Admin)
    val svc = service()
    val Right((_, token)) = svc.login("krzysiek", "s3cret"): @unchecked

    svc.authenticate(Some(token)) match
      case Right(user) =>
        assertEquals(user.username, "krzysiek")
        assertEquals(user.role, Role.Admin)
      case Left(e) => fail(s"expected success, got $e")

  test("authenticate rejects a missing, unknown or malformed token"):
    val svc = service()
    assertEquals(svc.authenticate(None), Left(ApiError.Unauthorized))
    assertEquals(svc.authenticate(Some("")), Left(ApiError.Unauthorized))
    assertEquals(svc.authenticate(Some("not-a-real-token")), Left(ApiError.Unauthorized))

  test("authenticate rejects an expired session and cleans it up"):
    aUser()
    val issued  = OffsetDateTime.now()
    val svc     = service(() => issued)
    val Right((_, token)) = svc.login("krzysiek", "s3cret"): @unchecked

    // Same repos, but "now" is past the TTL.
    val later = AuthService(UserRepo(xa), SessionRepo(xa), ttl, () => issued.plus(ttl).plusMinutes(1))
    assertEquals(later.authenticate(Some(token)), Left(ApiError.Unauthorized))
    assertEquals(SessionRepo(xa).find(Tokens.hash(token)), None)

  test("authenticate rejects a token whose user was disabled after login"):
    val id  = aUser()
    val svc = service()
    val Right((_, token)) = svc.login("krzysiek", "s3cret"): @unchecked

    transact(xa):
      sql"update users set disabled = true where id = $id".update.run()

    assertEquals(svc.authenticate(Some(token)), Left(ApiError.Forbidden))

  test("logout revokes the session"):
    aUser()
    val svc = service()
    val Right((_, token)) = svc.login("krzysiek", "s3cret"): @unchecked

    svc.logout(Some(token))

    assertEquals(svc.authenticate(Some(token)), Left(ApiError.Unauthorized))

  test("logout of an absent or unknown token is a no-op"):
    val svc = service()
    svc.logout(None)
    svc.logout(Some("never-existed"))
    // Reaching here without an exception is the assertion.
    assert(true)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "backend/testOnly lmbot.backend.AuthServiceTest"`
Expected: FAIL — `lmbot.backend.auth.AuthService` does not exist.

- [ ] **Step 3: Write the implementation**

`backend/src/main/scala/lmbot/backend/auth/AuthService.scala`:

```scala
package lmbot.backend.auth

import lmbot.backend.db.{SessionRepo, UserRepo, UserRow}
import lmbot.shared.api.ApiError
import lmbot.shared.domain.{Role, UserView}

import java.time.{Duration, OffsetDateTime}

case class AuthedUser(
  id: Long,
  username: String,
  displayName: String,
  role: Role,
  telegramLinked: Boolean
):
  def toView: UserView = UserView(id, username, displayName, role, telegramLinked)

/** All authentication and account-state policy. The HTTP layer asks questions
  * here and never decides anything itself (spec §6).
  */
class AuthService(
  users: UserRepo,
  sessions: SessionRepo,
  sessionTtl: Duration,
  now: () => OffsetDateTime
):

  def login(username: String, password: String): Either[ApiError, (UserView, String)] =
    for
      row  <- users.findByUsername(username).toRight(ApiError.Unauthorized)
      // Verify before checking `disabled` so that a disabled account and a
      // wrong password take the same work; the distinction is only revealed
      // to someone who already knows the password.
      _    <- Either.cond(Passwords.verify(row.passwordHash, password), (), ApiError.Unauthorized)
      _    <- Either.cond(!row.disabled, (), ApiError.Forbidden)
      user <- toAuthed(row)
    yield
      val token = Tokens.generate()
      sessions.insert(Tokens.hash(token), user.id, now().plus(sessionTtl))
      (user.toView, token)

  def authenticate(token: Option[String]): Either[ApiError, AuthedUser] =
    for
      raw     <- token.filter(_.nonEmpty).toRight(ApiError.Unauthorized)
      session <- sessions.find(Tokens.hash(raw)).toRight(ApiError.Unauthorized)
      _       <- Either.cond(session.expiresAt.isAfter(now()), (), expire(Tokens.hash(raw)))
      row     <- users.findById(session.userId).toRight(ApiError.Unauthorized)
      _       <- Either.cond(!row.disabled, (), ApiError.Forbidden)
      user    <- toAuthed(row)
    yield user

  def logout(token: Option[String]): Unit =
    token.filter(_.nonEmpty).foreach(raw => sessions.delete(Tokens.hash(raw)))

  /** Drop the dead session on the way past, so expired rows do not accumulate
    * purely because nobody swept them.
    */
  private def expire(tokenHash: String): ApiError =
    sessions.delete(tokenHash)
    ApiError.Unauthorized

  private def toAuthed(row: UserRow): Either[ApiError, AuthedUser] =
    Role
      .fromString(row.role)
      .toRight(ApiError.Unexpected(s"user ${row.id} has unrecognised role"))
      .map: role =>
        AuthedUser(row.id, row.username, row.displayName, role, row.telegramChatId.isDefined)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt "backend/testOnly lmbot.backend.AuthServiceTest"`
Expected: PASS — 11 tests.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(backend): AuthService with session lifecycle and account-state policy"
```

---

### Task 7: Config and admin bootstrap

**Files:**
- Create: `backend/src/main/scala/lmbot/backend/config/Config.scala`
- Create: `backend/src/main/scala/lmbot/backend/auth/AdminBootstrap.scala`
- Test: `backend/src/test/scala/lmbot/backend/ConfigTest.scala`, `backend/src/test/scala/lmbot/backend/AdminBootstrapTest.scala`

**Interfaces:**
- Consumes: `UserRepo` (Task 4), `Passwords` (Task 5), `Role` (Task 2).
- Produces:
  - `case class Config(dbUrl, dbUser, dbPassword, httpHost, httpPort, cookieSecure, sessionTtl, adminUsername, adminPassword)` with `Config.fromEnv(env: Map[String, String]): Either[List[String], Config]`.
  - `class AdminBootstrap(users: UserRepo)` with `run(adminUsername: Option[String], adminPassword: Option[String]): AdminBootstrap.Outcome`, where `Outcome` is `Created(username)` / `SkippedUsersExist` / `MissingCredentials`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/scala/lmbot/backend/ConfigTest.scala`:

```scala
package lmbot.backend

import lmbot.backend.config.{Config, Secret}

class ConfigTest extends munit.FunSuite:

  private val minimal = Map(
    "DATABASE_URL"      -> "jdbc:postgresql://localhost:5432/lmbot",
    "DATABASE_USER"     -> "lmbot",
    "DATABASE_PASSWORD" -> "secret"
  )

  test("a minimal environment yields a config with sensible defaults"):
    Config.fromEnv(minimal) match
      case Right(c) =>
        assertEquals(c.dbUrl, "jdbc:postgresql://localhost:5432/lmbot")
        assertEquals(c.httpHost, "0.0.0.0")
        assertEquals(c.httpPort, 8080)
        assertEquals(c.cookieSecure, true)
        assertEquals(c.sessionTtl.toDays, 7L)
        assertEquals(c.adminUsername, None)
      case Left(errs) => fail(s"expected success, got $errs")

  test("missing required variables are all reported at once"):
    Config.fromEnv(Map.empty) match
      case Right(c) => fail(s"expected failure, got $c")
      case Left(errors) =>
        assert(errors.exists(_.contains("DATABASE_URL")))
        assert(errors.exists(_.contains("DATABASE_USER")))
        assert(errors.exists(_.contains("DATABASE_PASSWORD")))

  test("port and secure-cookie flag are overridable"):
    val env = minimal ++ Map("HTTP_PORT" -> "9000", "COOKIE_SECURE" -> "false", "HTTP_HOST" -> "127.0.0.1")
    Config.fromEnv(env) match
      case Right(c) =>
        assertEquals(c.httpPort, 9000)
        assertEquals(c.cookieSecure, false)
        assertEquals(c.httpHost, "127.0.0.1")
      case Left(errs) => fail(s"expected success, got $errs")

  test("a non-numeric port is rejected"):
    Config.fromEnv(minimal + ("HTTP_PORT" -> "eighty")) match
      case Right(c)     => fail(s"expected failure, got $c")
      case Left(errors) => assert(errors.exists(_.contains("HTTP_PORT")))

  test("admin bootstrap credentials are picked up when both are present"):
    val env = minimal ++ Map("ADMIN_USERNAME" -> "root", "ADMIN_PASSWORD" -> "hunter2")
    Config.fromEnv(env) match
      case Right(c) =>
        assertEquals(c.adminUsername, Some("root"))
        assertEquals(c.adminPassword, Some(Secret("hunter2")))
      case Left(errs) => fail(s"expected success, got $errs")

  test("secrets are wrapped so their value is reachable but not rendered"):
    val Right(c) = Config.fromEnv(minimal): @unchecked
    assertEquals(c.dbPassword.value, "secret")
    assertEquals(c.dbPassword.toString, "***")

  test("config never renders secrets in toString"):
    val Right(c) = Config.fromEnv(minimal ++ Map("ADMIN_PASSWORD" -> "hunter2")): @unchecked
    val rendered = c.toString
    assert(!rendered.contains("secret"), s"db password leaked: $rendered")
    assert(!rendered.contains("hunter2"), s"admin password leaked: $rendered")
```

`backend/src/test/scala/lmbot/backend/AdminBootstrapTest.scala`:

```scala
package lmbot.backend

import lmbot.backend.auth.{AdminBootstrap, Passwords}
import lmbot.backend.db.UserRepo
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.Role

class AdminBootstrapTest extends PostgresSuite:

  test("on an empty database the admin is created from the environment"):
    val users   = UserRepo(xa)
    val outcome = AdminBootstrap(users).run(Some("root"), Some("hunter2"))

    assertEquals(outcome, AdminBootstrap.Outcome.Created("root"))
    val created = users.findByUsername("root")
    assertEquals(created.map(_.role), Some("admin"))
    assert(created.exists(r => Passwords.verify(r.passwordHash, "hunter2")))

  test("the admin password is stored hashed, not in plaintext"):
    val users = UserRepo(xa)
    AdminBootstrap(users).run(Some("root"), Some("hunter2"))

    assert(!users.findByUsername("root").exists(_.passwordHash.contains("hunter2")))

  test("bootstrap is skipped when any user already exists"):
    val users = UserRepo(xa)
    users.insert("krzysiek", "Krzysiek", Passwords.hash("x"), Role.User)

    val outcome = AdminBootstrap(users).run(Some("root"), Some("hunter2"))

    assertEquals(outcome, AdminBootstrap.Outcome.SkippedUsersExist)
    assertEquals(users.findByUsername("root"), None)
    assertEquals(users.count(), 1L)

  test("missing credentials on an empty database are reported, not guessed at"):
    val users = UserRepo(xa)
    assertEquals(users.count(), 0L)

    assertEquals(AdminBootstrap(users).run(None, None), AdminBootstrap.Outcome.MissingCredentials)
    assertEquals(AdminBootstrap(users).run(Some("root"), None), AdminBootstrap.Outcome.MissingCredentials)
    assertEquals(AdminBootstrap(users).run(None, Some("hunter2")), AdminBootstrap.Outcome.MissingCredentials)
    assertEquals(users.count(), 0L)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `sbt "backend/testOnly lmbot.backend.ConfigTest lmbot.backend.AdminBootstrapTest"`
Expected: FAIL — `Config` and `AdminBootstrap` do not exist.

- [ ] **Step 3: Write the implementation**

`backend/src/main/scala/lmbot/backend/config/Config.scala`:

```scala
package lmbot.backend.config

import java.time.Duration

/** Configuration is env-only (spec §9). Secrets are wrapped so that an
  * accidental interpolation of the config into a log line cannot leak them.
  */
final case class Secret(value: String):
  override def toString: String = "***"

case class Config(
  dbUrl: String,
  dbUser: String,
  dbPassword: Secret,
  httpHost: String,
  httpPort: Int,
  cookieSecure: Boolean,
  sessionTtl: Duration,
  adminUsername: Option[String],
  adminPassword: Option[Secret]
)

object Config:

  def fromEnv(env: Map[String, String]): Either[List[String], Config] =
    val errors = List.newBuilder[String]

    def required(key: String): String =
      env.get(key).filter(_.nonEmpty) match
        case Some(v) => v
        case None    => errors += s"$key is required"; ""

    def int(key: String, default: Int): Int =
      env.get(key).filter(_.nonEmpty) match
        case None => default
        case Some(v) =>
          v.toIntOption match
            case Some(i) => i
            case None    => errors += s"$key must be a number, got '$v'"; default

    def bool(key: String, default: Boolean): Boolean =
      env.get(key).filter(_.nonEmpty) match
        case None => default
        case Some(v) =>
          v.toBooleanOption match
            case Some(b) => b
            case None    => errors += s"$key must be true or false, got '$v'"; default

    val dbUrl      = required("DATABASE_URL")
    val dbUser     = required("DATABASE_USER")
    val dbPassword = required("DATABASE_PASSWORD")
    val host       = env.get("HTTP_HOST").filter(_.nonEmpty).getOrElse("0.0.0.0")
    val port       = int("HTTP_PORT", 8080)
    // Secure by default: the operator terminates TLS in front of us (spec §6).
    // Only a deliberate override turns it off, for plain-HTTP local dev.
    val secure     = bool("COOKIE_SECURE", true)
    val ttlDays    = int("SESSION_TTL_DAYS", 7)

    val built = errors.result()
    if built.nonEmpty then Left(built)
    else
      Right(
        Config(
          dbUrl = dbUrl,
          dbUser = dbUser,
          dbPassword = Secret(dbPassword),
          httpHost = host,
          httpPort = port,
          cookieSecure = secure,
          sessionTtl = Duration.ofDays(ttlDays.toLong),
          adminUsername = env.get("ADMIN_USERNAME").filter(_.nonEmpty),
          adminPassword = env.get("ADMIN_PASSWORD").filter(_.nonEmpty).map(Secret.apply)
        )
      )
```

`backend/src/main/scala/lmbot/backend/auth/AdminBootstrap.scala`:

```scala
package lmbot.backend.auth

import lmbot.backend.db.UserRepo
import lmbot.shared.domain.Role

/** Creates the first admin account, and only ever the first: the credentials
  * are read exclusively when the `users` table is empty (spec §2), so leaving
  * them in the environment cannot silently reset or re-create an admin.
  */
class AdminBootstrap(users: UserRepo):
  import AdminBootstrap.Outcome

  def run(adminUsername: Option[String], adminPassword: Option[String]): Outcome =
    if users.count() > 0 then Outcome.SkippedUsersExist
    else
      (adminUsername, adminPassword) match
        case (Some(username), Some(password)) =>
          users.insert(username, username, Passwords.hash(password), Role.Admin)
          Outcome.Created(username)
        case _ => Outcome.MissingCredentials

object AdminBootstrap:
  enum Outcome:
    case Created(username: String)
    case SkippedUsersExist
    case MissingCredentials
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbt "backend/testOnly lmbot.backend.ConfigTest lmbot.backend.AdminBootstrapTest"`
Expected: PASS — 11 tests.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(backend): env config with secret masking and first-start admin bootstrap"
```

---

### Task 8: HTTP server, routes and the session cookie

**Files:**
- Create: `backend/src/main/scala/lmbot/backend/http/SessionCookie.scala`
- Create: `backend/src/main/scala/lmbot/backend/http/AuthRoutes.scala`
- Create: `backend/src/main/scala/lmbot/backend/http/HealthRoutes.scala`
- Create: `backend/src/main/scala/lmbot/backend/http/Server.scala`
- Create: `backend/src/main/resources/logback.xml`
- Test: `backend/src/test/scala/lmbot/backend/HttpApiTest.scala`

**Interfaces:**
- Consumes: `AuthEndpoints`, `ApiError`, `LoginRequest`, `UserView` (Tasks 2–3); `AuthService`, `AuthedUser` (Task 6); `Config` (Task 7).
- Produces:
  - `object SessionCookie` with `issue(token: String, secure: Boolean, ttl: Duration): CookieValueWithMeta` and `clear(secure: Boolean): CookieValueWithMeta`.
  - `class AuthRoutes(auth: AuthService, cookieSecure: Boolean, sessionTtl: Duration)` with `endpoints: List[ServerEndpoint[Any, Identity]]`.
  - `object HealthRoutes` with `endpoints: List[ServerEndpoint[Any, Identity]]` serving `GET /health` → `"ok"`.
  - `object Server` with `start(host: String, port: Int, endpoints: List[ServerEndpoint[Any, Identity]]): HttpServer`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/scala/lmbot/backend/HttpApiTest.scala`:

```scala
package lmbot.backend

import com.augustnagro.magnum.{sql, transact}
import lmbot.backend.auth.{AuthService, Passwords}
import lmbot.backend.db.{SessionRepo, UserRepo}
import lmbot.backend.http.{AuthRoutes, HealthRoutes, Server}
import lmbot.backend.support.PostgresSuite
import lmbot.shared.domain.Role
import sttp.client3.*
import sttp.model.StatusCode

import java.time.{Duration, OffsetDateTime}

/** Drives the real server over real HTTP against real Postgres. */
class HttpApiTest extends PostgresSuite:

  private val ttl = Duration.ofDays(7)
  private var server: com.sun.net.httpserver.HttpServer = scala.compiletime.uninitialized
  private var baseUri: Uri                              = scala.compiletime.uninitialized
  private val http                                      = HttpClientSyncBackend()

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    val auth  = AuthService(UserRepo(xa), SessionRepo(xa), ttl, () => OffsetDateTime.now())
    val routes = AuthRoutes(auth, cookieSecure = false, sessionTtl = ttl)
    // Port 0 lets the OS choose, so tests never collide.
    server  = Server.start("127.0.0.1", 0, HealthRoutes.endpoints ++ routes.endpoints)
    baseUri = uri"http://127.0.0.1:${server.getAddress.getPort}"

  override def afterEach(context: AfterEach): Unit =
    if server != null then server.stop(0)

  private def aUser(username: String = "krzysiek", password: String = "s3cret"): Long =
    UserRepo(xa).insert(username, "Krzysiek", Passwords.hash(password), Role.User).id

  private def login(username: String, password: String) =
    basicRequest
      .post(uri"$baseUri/api/auth/login")
      .body(s"""{"username":"$username","password":"$password"}""")
      .contentType("application/json")
      .send(http)

  test("health responds ok without authentication"):
    val r = basicRequest.get(uri"$baseUri/health").send(http)
    assertEquals(r.code, StatusCode.Ok)
    assertEquals(r.body, Right("ok"))

  test("login succeeds and sets an HttpOnly SameSite=Lax session cookie"):
    aUser()
    val r = login("krzysiek", "s3cret")

    assertEquals(r.code, StatusCode.Ok)
    assert(r.body.exists(_.contains("krzysiek")))

    val setCookie = r.headers("Set-Cookie").mkString("; ")
    assert(setCookie.contains("lmbot_session="), s"no session cookie: $setCookie")
    assert(setCookie.toLowerCase.contains("httponly"), s"not HttpOnly: $setCookie")
    assert(setCookie.contains("SameSite=Lax"), s"not SameSite=Lax: $setCookie")

  test("login never echoes the password back"):
    aUser()
    val r = login("krzysiek", "s3cret")
    assert(!r.body.exists(_.contains("s3cret")))

  test("login with a wrong password is 401"):
    aUser()
    assertEquals(login("krzysiek", "nope").code, StatusCode.Unauthorized)

  test("me is 401 without a cookie"):
    assertEquals(basicRequest.get(uri"$baseUri/api/auth/me").send(http).code, StatusCode.Unauthorized)

  test("me returns the current user when the session cookie is presented"):
    aUser()
    val token = sessionCookieValue(login("krzysiek", "s3cret"))

    val r = basicRequest
      .get(uri"$baseUri/api/auth/me")
      .cookie("lmbot_session", token)
      .send(http)

    assertEquals(r.code, StatusCode.Ok)
    assert(r.body.exists(_.contains("krzysiek")))

  test("me is 403 once the user is disabled, even with a valid cookie"):
    val id    = aUser()
    val token = sessionCookieValue(login("krzysiek", "s3cret"))
    transact(xa):
      sql"update users set disabled = true where id = $id".update.run()

    val r = basicRequest.get(uri"$baseUri/api/auth/me").cookie("lmbot_session", token).send(http)
    assertEquals(r.code, StatusCode.Forbidden)

  test("logout clears the cookie and invalidates the session"):
    aUser()
    val token = sessionCookieValue(login("krzysiek", "s3cret"))

    val out = basicRequest.post(uri"$baseUri/api/auth/logout").cookie("lmbot_session", token).send(http)
    assertEquals(out.code, StatusCode.Ok)
    assert(out.headers("Set-Cookie").mkString.contains("Max-Age=0"))

    val after = basicRequest.get(uri"$baseUri/api/auth/me").cookie("lmbot_session", token).send(http)
    assertEquals(after.code, StatusCode.Unauthorized)

  test("errors come back as the shared ErrorBody shape"):
    aUser()
    val r = login("krzysiek", "nope")
    assert(r.body.isLeft)
    val body = r.body.swap.getOrElse("")
    assert(body.contains("\"code\""), s"unexpected error body: $body")
    assert(body.contains("unauthorized"), s"unexpected error body: $body")

  private def sessionCookieValue(response: Response[Either[String, String]]): String =
    response
      .headers("Set-Cookie")
      .flatMap(_.split(";").headOption)
      .collectFirst { case kv if kv.startsWith("lmbot_session=") => kv.drop("lmbot_session=".length) }
      .getOrElse(fail("no session cookie in login response"))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "backend/testOnly lmbot.backend.HttpApiTest"`
Expected: FAIL — `SessionCookie`, `AuthRoutes`, `HealthRoutes`, `Server` do not exist.

- [ ] **Step 3: Write the implementation**

`backend/src/main/scala/lmbot/backend/http/SessionCookie.scala`:

```scala
package lmbot.backend.http

import sttp.model.headers.Cookie.SameSite
import sttp.model.headers.CookieValueWithMeta

import java.time.Duration

object SessionCookie:

  /** `HttpOnly` keeps the token out of reach of page scripts, `SameSite=Lax`
    * blocks cross-site submission, `Secure` is on unless local dev turns it
    * off. Spec §6.
    */
  def issue(token: String, secure: Boolean, ttl: Duration): CookieValueWithMeta =
    CookieValueWithMeta.unsafeApply(
      value = token,
      maxAge = Some(ttl.toSeconds),
      path = Some("/"),
      secure = secure,
      httpOnly = true,
      sameSite = Some(SameSite.Lax)
    )

  /** An empty value with `Max-Age=0` is what tells the browser to drop the
    * cookie; the server-side session is deleted separately.
    */
  def clear(secure: Boolean): CookieValueWithMeta =
    CookieValueWithMeta.unsafeApply(
      value = "",
      maxAge = Some(0L),
      path = Some("/"),
      secure = secure,
      httpOnly = true,
      sameSite = Some(SameSite.Lax)
    )
```

`backend/src/main/scala/lmbot/backend/http/AuthRoutes.scala`:

```scala
package lmbot.backend.http

import lmbot.backend.auth.{AuthService, AuthedUser}
import lmbot.shared.api.{ApiError, AuthEndpoints}
import lmbot.shared.domain.UserView
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint

import java.time.Duration

/** Translates HTTP to service calls and back. No policy lives here. */
class AuthRoutes(auth: AuthService, cookieSecure: Boolean, sessionTtl: Duration):

  private val loginRoute: ServerEndpoint[Any, Identity] =
    AuthEndpoints.login.serverLogicPure[Identity] { req =>
      auth
        .login(req.username, req.password)
        .map { (view, token) => (view, Some(SessionCookie.issue(token, cookieSecure, sessionTtl))) }
    }

  private val meRoute: ServerEndpoint[Any, Identity] =
    AuthEndpoints.me
      .serverSecurityLogicPure[AuthedUser, Identity](auth.authenticate)
      .serverLogicPure[UserView](user => _ => Right(user.toView))

  private val logoutRoute: ServerEndpoint[Any, Identity] =
    AuthEndpoints.logout
      // Logout must work even for a session the server no longer likes, so it
      // takes the raw cookie rather than an authenticated principal.
      .serverSecurityLogicPure[Option[String], Identity](Right(_))
      .serverLogicPure { token => _ =>
        auth.logout(token)
        Right(Some(SessionCookie.clear(cookieSecure)))
      }

  val endpoints: List[ServerEndpoint[Any, Identity]] = List(loginRoute, meRoute, logoutRoute)
```

`backend/src/main/scala/lmbot/backend/http/HealthRoutes.scala`:

```scala
package lmbot.backend.http

import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

object HealthRoutes:

  private val health: ServerEndpoint[Any, Identity] =
    endpoint.get
      .in("health")
      .out(stringBody)
      .serverLogicPure[Identity](_ => Right("ok"))

  val endpoints: List[ServerEndpoint[Any, Identity]] = List(health)
```

`backend/src/main/scala/lmbot/backend/http/Server.scala`:

```scala
package lmbot.backend.http

import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.jdkhttp.{HttpServer, JdkHttpServer}

import java.util.concurrent.Executors

object Server:

  /** jdkhttp defaults to a single calling thread, which would serialise every
    * request. A virtual-thread-per-task executor is what makes the blocking
    * style in the services safe: handlers block freely, and Gears is used
    * inside them (spec §5.1).
    */
  def start(host: String, port: Int, endpoints: List[ServerEndpoint[Any, Identity]]): HttpServer =
    JdkHttpServer()
      .host(host)
      .port(port)
      .executor(Executors.newVirtualThreadPerTaskExecutor())
      .addEndpoints(endpoints)
      .start()
```

`backend/src/main/resources/logback.xml`:

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>

    <!-- Keep JDBC and HTTP internals from logging parameter values, which
         would defeat the secret masking done in application code. -->
    <logger name="com.zaxxer.hikari" level="WARN"/>
    <logger name="org.postgresql" level="WARN"/>
</configuration>
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt "backend/testOnly lmbot.backend.HttpApiTest"`
Expected: PASS — 9 tests.

- [ ] **Step 5: Run the whole backend suite**

Run: `sbt backend/testFull`
Expected: all backend tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(backend): jdkhttp server on virtual threads with auth and health routes"
```

---

### Task 9: The Gears bridge and the derived API client

**Files:**
- Create: `frontend/src/main/scala/lmbot/frontend/bridge/Bridge.scala`
- Create: `frontend/src/main/scala/lmbot/frontend/api/ApiClient.scala`
- Test: `frontend/src/test/scala/lmbot/frontend/BridgeTest.scala`

**Interfaces:**
- Consumes: `AuthEndpoints`, `ApiError`, `LoginRequest`, `UserView` (Tasks 2–3).
- Produces:
  - `object Bridge` with `def await[T](f: => scala.concurrent.Future[T])(using Async): Either[Throwable, T]`.
  - `class ApiClient(baseUri: Uri)` with `login(req: LoginRequest)(using Async): Either[ApiError, UserView]`, `me()(using Async): Either[ApiError, UserView]`, `logout()(using Async): Either[ApiError, Unit]`.

This is the **only** file in the frontend permitted to name `scala.concurrent.Future` (spec §5.7.1). Everything above it speaks Gears.

- [ ] **Step 1: Write the failing test**

`frontend/src/test/scala/lmbot/frontend/BridgeTest.scala`:

```scala
package lmbot.frontend

import gears.async.*
import gears.async.default.given
import lmbot.frontend.bridge.Bridge

import scala.concurrent.Future as StdFuture

class BridgeTest extends munit.FunSuite:

  // On Scala.js, `Async.fromSync` is backed by JsAsyncFromSync, whose
  // `Output[T]` is `scala.concurrent.Future[T]`. MUnit accepts a Future return
  // directly, so tests hand it straight back rather than blocking — JSPI
  // cannot block here. This is also why `Async.blocking` is not used: it needs
  // `FromSync.Blocking`, which only the JVM provides.
  test("a successful std Future becomes a Right"):
    Async.fromSync:
      assertEquals(Bridge.await(StdFuture.successful(42)), Right(42))

  test("a failed std Future becomes a Left carrying the throwable"):
    Async.fromSync:
      val boom = new RuntimeException("boom")
      Bridge.await(StdFuture.failed(boom)) match
        case Left(e)  => assertEquals(e.getMessage, "boom")
        case Right(v) => fail(s"expected Left, got $v")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt frontend/testFull`
Expected: FAIL — `lmbot.frontend.bridge.Bridge` does not exist.

- [ ] **Step 3: Write the implementation**

`frontend/src/main/scala/lmbot/frontend/bridge/Bridge.scala`:

```scala
package lmbot.frontend.bridge

import gears.async.ScalaConverters.asGears
import gears.async.{Async, Future}

import scala.concurrent.{ExecutionContext, Future as StdFuture}

/** The single adapter between foreign async APIs and Gears (spec §5.7.1).
  *
  * Nothing outside this package may mention `scala.concurrent.Future`: sttp's
  * Scala.js backend returns one, and this is where that fact stops.
  */
object Bridge:

  private given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  /** Awaits a foreign Future as a value. Failures come back as `Left` rather
    * than as thrown exceptions, because a failed network call is an expected
    * outcome, not a bug (spec §7).
    */
  def await[T](f: => StdFuture[T])(using Async): Either[Throwable, T] =
    f.asGears.awaitResult.toEither
```

`frontend/src/main/scala/lmbot/frontend/api/ApiClient.scala`:

```scala
package lmbot.frontend.api

import gears.async.Async
import lmbot.frontend.bridge.Bridge
import lmbot.shared.api.{ApiError, AuthEndpoints, LoginRequest}
import lmbot.shared.domain.UserView
import sttp.client3.FetchBackend
import sttp.model.Uri
import sttp.tapir.client.sttp.SttpClientInterpreter

/** Derived from the shared endpoint descriptions, so the client cannot drift
  * from the server (spec §5.1).
  */
class ApiClient(baseUri: Uri):

  // Lazy so that merely constructing an ApiClient touches no browser API —
  // the pure `update` tests build one and never make a call.
  private lazy val backend     = FetchBackend()
  private lazy val interpreter = SttpClientInterpreter()

  private lazy val loginFn =
    interpreter.toClientThrowDecodeFailures(AuthEndpoints.login, Some(baseUri), backend)
  private lazy val meFn =
    interpreter.toSecureClientThrowDecodeFailures(AuthEndpoints.me, Some(baseUri), backend)
  private lazy val logoutFn =
    interpreter.toSecureClientThrowDecodeFailures(AuthEndpoints.logout, Some(baseUri), backend)

  def login(req: LoginRequest)(using Async): Either[ApiError, UserView] =
    // The response also carries the Set-Cookie value; the browser stores it,
    // so the value itself is of no use to us here.
    call(loginFn(req)).map((view, _) => view)

  /** The session cookie is `HttpOnly`, so page scripts cannot read it. We pass
    * `None` as the security input and let the browser attach the real cookie
    * to the request — which it does, because the API is same-origin.
    */
  def me()(using Async): Either[ApiError, UserView] = call(meFn(None)(()))

  def logout()(using Async): Either[ApiError, Unit] = call(logoutFn(None)(())).map(_ => ())

  /** A transport failure is reported as an error value, in the same channel as
    * a server-side error, so callers have exactly one thing to handle.
    */
  private def call[E, T](f: => scala.concurrent.Future[Either[ApiError, T]])(using
    Async
  ): Either[ApiError, T] =
    Bridge.await(f) match
      case Right(result) => result
      case Left(err) =>
        Left(ApiError.Unexpected(Option(err.getMessage).getOrElse("network request failed")))
```

Decode failures throw rather than becoming values, deliberately: both sides of this contract are generated from the same `shared` module, so a decode failure is a build-level bug, and spec §7 reserves exceptions for exactly that. (The Luxmed client in Plan 3 is the opposite case — there, decode failures are expected and become `ApiChanged` values.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt frontend/testFull`
Expected: PASS — 2 tests.

- [ ] **Step 5: Verify the frontend still links to Wasm**

Run: `sbt frontend/fastLinkJS`
Expected: success.

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "feat(frontend): Gears bridge and tapir-derived API client"
```

---

### Task 10: The Elm-on-Gears runtime

**Files:**
- Create: `frontend/src/main/scala/lmbot/frontend/elm/Effect.scala`
- Create: `frontend/src/main/scala/lmbot/frontend/elm/Runtime.scala`
- Test: `frontend/src/test/scala/lmbot/frontend/RuntimeTest.scala`

**Interfaces:**
- Consumes: nothing (generic in state and message types).
- Produces:
  - `trait Effect[+M] { def run(using Async): Option[M] }`
  - `case class Transition[S, M](state: S, effects: List[Effect[M]])`
  - `class Runtime[S, M](initial: S, update: (S, M) => Transition[S, M])` with `store: Var[S]`, `dispatch(msg: M): Unit`, and `run(using Async.Spawn): Unit`.

`dispatch` is non-suspending so DOM handlers can call it directly — they do exactly one thing, send a message (spec §5.6). `Var` is the only Airstream value in the app.

- [ ] **Step 1: Write the failing test**

`frontend/src/test/scala/lmbot/frontend/RuntimeTest.scala`:

```scala
package lmbot.frontend

import gears.async.*
import gears.async.default.given
import lmbot.frontend.elm.{Effect, Runtime, Transition}

class RuntimeTest extends munit.FunSuite:

  enum Msg:
    case Inc, Dec, Stop
    case Add(n: Int)

  private def counterUpdate(state: Int, msg: Msg): Transition[Int, Msg] = msg match
    case Msg.Inc    => Transition(state + 1, Nil)
    case Msg.Dec    => Transition(state - 1, Nil)
    case Msg.Add(n) => Transition(state + n, Nil)
    case Msg.Stop   => Transition(state, Nil)

  test("update is pure and needs no runtime at all"):
    assertEquals(counterUpdate(0, Msg.Inc).state, 1)
    assertEquals(counterUpdate(5, Msg.Add(3)).state, 8)
    assertEquals(counterUpdate(0, Msg.Inc).effects, Nil)

  // `Async.fromSync` on Scala.js returns a `scala.concurrent.Future`, which
  // MUnit accepts as a test result directly.
  test("dispatched messages are folded into the store in order"):
    Async.fromSync:
      Async.group:
        val rt   = Runtime[Int, Msg](0, counterUpdate)
        val loop = Future(rt.run)

        rt.dispatch(Msg.Inc)
        rt.dispatch(Msg.Inc)
        rt.dispatch(Msg.Add(10))
        rt.dispatch(Msg.Dec)

        rt.awaitQuiescence()
        assertEquals(rt.store.now(), 11)
        rt.stop()
        loop.awaitResult
        ()

  test("an effect's resulting message is fed back into the loop"):
    Async.fromSync:
      Async.group:
        def update(state: Int, msg: Msg): Transition[Int, Msg] = msg match
          // Inc bumps the counter and schedules an effect that adds 100.
          case Msg.Inc =>
            val eff = new Effect[Msg]:
              def run(using Async): Option[Msg] = Some(Msg.Add(100))
            Transition(state + 1, List(eff))
          case other => counterUpdate(state, other)

        val rt   = Runtime[Int, Msg](0, update)
        val loop = Future(rt.run)

        rt.dispatch(Msg.Inc)
        rt.awaitQuiescence()

        assertEquals(rt.store.now(), 101)
        rt.stop()
        loop.awaitResult
        ()

  test("an effect that yields no message still leaves state consistent"):
    Async.fromSync:
      Async.group:
        def update(state: Int, msg: Msg): Transition[Int, Msg] = msg match
          case Msg.Inc =>
            val silent = new Effect[Msg]:
              def run(using Async): Option[Msg] = None
            Transition(state + 1, List(silent))
          case other => counterUpdate(state, other)

        val rt   = Runtime[Int, Msg](0, update)
        val loop = Future(rt.run)
        rt.dispatch(Msg.Inc)
        rt.awaitQuiescence()

        assertEquals(rt.store.now(), 1)
        rt.stop()
        loop.awaitResult
        ()

  test("an effect that throws kills only its own fiber, not the loop"):
    Async.fromSync:
      Async.group:
        def update(state: Int, msg: Msg): Transition[Int, Msg] = msg match
          case Msg.Inc =>
            val bad = new Effect[Msg]:
              def run(using Async): Option[Msg] = throw new RuntimeException("boom")
            Transition(state + 1, List(bad))
          case other => counterUpdate(state, other)

        val rt   = Runtime[Int, Msg](0, update)
        val loop = Future(rt.run)

        rt.dispatch(Msg.Inc)
        rt.awaitQuiescence()
        // The loop survived, so this later message is still processed.
        rt.dispatch(Msg.Add(5))
        rt.awaitQuiescence()

        assertEquals(rt.store.now(), 6)
        rt.stop()
        loop.awaitResult
        ()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt frontend/testFull`
Expected: FAIL — `lmbot.frontend.elm.Runtime` does not exist.

- [ ] **Step 3: Write the implementation**

`frontend/src/main/scala/lmbot/frontend/elm/Effect.scala`:

```scala
package lmbot.frontend.elm

import gears.async.Async

/** A side effect to run outside `update`: an API call, a timer, storage access.
  *
  * Written as ordinary sequential Gears code. Returning `None` means the effect
  * produced nothing the application needs to react to.
  */
trait Effect[+M]:
  def run(using Async): Option[M]

/** The result of `update`: the next state, plus effects to run. */
case class Transition[S, M](state: S, effects: List[Effect[M]])
```

`frontend/src/main/scala/lmbot/frontend/elm/Runtime.scala`:

```scala
package lmbot.frontend.elm

import com.raquo.laminar.api.L.Var
import gears.async.*

import scala.util.{Failure, Success, Try}

/** The Elm architecture on Gears (spec §5.6).
  *
  *   - one store: `store`, the only Airstream `Var` in the app;
  *   - one message channel;
  *   - one event loop, which applies the pure `update` and then runs each
  *     effect in its own fiber.
  */
class Runtime[S, M](initial: S, update: (S, M) => Transition[S, M]):

  /** Unbounded so that `dispatch` never has to suspend — DOM event handlers
    * cannot.
    */
  private val inbox = UnboundedChannel[M]()

  /** Outstanding work, tracked so tests can wait for the loop to settle rather
    * than sleeping a guessed interval. `queued` is decremented only *after* a
    * message's effects have been counted into `inFlight`, and an effect's
    * follow-up message is dispatched before that effect's `inFlight` is
    * released — so the pair is never both zero while work remains.
    *
    * Plain `var`s are sound here: the browser runs this single-threaded, and
    * this runtime is JS-only.
    */
  private var queued   = 0
  private var inFlight = 0

  val store: Var[S] = Var(initial)

  /** The one thing a DOM handler is allowed to do. Non-suspending. */
  def dispatch(msg: M): Unit =
    queued += 1
    inbox.sendImmediately(msg)

  def stop(): Unit = inbox.close()

  def run(using Async.Spawn): Unit =
    var state   = initial
    var running = true
    while running do
      inbox.read() match
        case Left(_) => running = false
        case Right(msg) =>
          val Transition(next, effects) = update(state, msg)
          state = next
          store.set(next)
          inFlight += effects.size
          effects.foreach: effect =>
            // Each effect gets its own fiber, so a crashing effect takes down
            // only itself and never the loop (spec §5.7.2).
            Future:
              try
                Try(effect.run) match
                  case Success(Some(resultMsg)) => dispatch(resultMsg)
                  case Success(None)            => ()
                  case Failure(_)               => ()
              finally inFlight -= 1
          queued -= 1

  /** Waits until every dispatched message — including those produced by
    * effects — has been handled. Test support; the browser never calls it.
    */
  def awaitQuiescence()(using Async, AsyncOperations): Unit =
    while queued > 0 || inFlight > 0 do AsyncOperations.sleep(1)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt frontend/testFull`
Expected: PASS — 5 runtime tests plus the 2 bridge tests.

These tests must pass repeatedly, not once. Run `sbt frontend/testFull` three times; if any run differs, the `queued`/`inFlight` bookkeeping is wrong and needs fixing at the source. Do not paper over a flaky result with a longer sleep.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): Elm-on-Gears runtime with store, channel and event loop"
```

---

### Task 11: Login page — state, update, and views

**Files:**
- Create: `frontend/src/main/scala/lmbot/frontend/AppState.scala`
- Create: `frontend/src/main/scala/lmbot/frontend/Msg.scala`
- Create: `frontend/src/main/scala/lmbot/frontend/Update.scala`
- Create: `frontend/src/main/scala/lmbot/frontend/view/AppView.scala`
- Create: `frontend/src/main/scala/lmbot/frontend/Main.scala`
- Create: `frontend/index.html`
- Test: `frontend/src/test/scala/lmbot/frontend/UpdateTest.scala`

**Interfaces:**
- Consumes: `Runtime`, `Transition`, `Effect` (Task 10); `ApiClient` (Task 9); `UserView`, `ApiError`, `LoginRequest` (Tasks 2–3).
- Produces:
  - `enum Screen { case Login, Dashboard }`
  - `case class LoginForm(username: String, password: String, submitting: Boolean, error: Option[String])`
  - `case class AppState(screen: Screen, login: LoginForm, user: Option[UserView], booting: Boolean)` with `AppState.initial`.
  - `enum Msg` with `UsernameChanged(String)`, `PasswordChanged(String)`, `LoginSubmitted`, `LoginSucceeded(UserView)`, `LoginFailed(ApiError)`, `SessionRestored(UserView)`, `SessionAbsent`, `LogoutRequested`, `LoggedOut`.
  - `class Update(api: ApiClient)` with `apply(state: AppState, msg: Msg): Transition[AppState, Msg]`. Pass `Update(api).apply` where `Runtime` wants a function — the instance is not a `Function2`.
  - `object AppView` with `apply(rt: Runtime[AppState, Msg]): HtmlElement`.

- [ ] **Step 1: Write the failing test**

`frontend/src/test/scala/lmbot/frontend/UpdateTest.scala`:

```scala
package lmbot.frontend

import lmbot.shared.api.ApiError
import lmbot.shared.domain.{Role, UserView}

/** `update` is pure, so this whole suite runs with no DOM and no runtime. */
class UpdateTest extends munit.FunSuite:

  // ApiClient's backend is lazy, so this never touches fetch: these tests
  // exercise `update` alone and no effect is ever run.
  private val api    = lmbot.frontend.api.ApiClient(sttp.model.Uri.unsafeParse("http://localhost"))
  private val update = Update(api)

  private val alice = UserView(1L, "alice", "Alice", Role.User, telegramLinked = false)

  test("the app starts on the login screen, booting, with an empty form"):
    val s = AppState.initial
    assertEquals(s.screen, Screen.Login)
    assertEquals(s.booting, true)
    assertEquals(s.user, None)
    assertEquals(s.login.username, "")
    assertEquals(s.login.password, "")
    assertEquals(s.login.error, None)

  test("typing updates only the field typed into"):
    val afterUser = update(AppState.initial, Msg.UsernameChanged("bob")).state
    assertEquals(afterUser.login.username, "bob")
    assertEquals(afterUser.login.password, "")

    val afterPass = update(afterUser, Msg.PasswordChanged("pw")).state
    assertEquals(afterPass.login.username, "bob")
    assertEquals(afterPass.login.password, "pw")

  test("submitting marks the form busy, clears any old error, and emits one effect"):
    val filled = update(update(AppState.initial, Msg.UsernameChanged("bob")).state, Msg.PasswordChanged("pw")).state
    val withError = filled.copy(login = filled.login.copy(error = Some("previously wrong")))

    val t = update(withError, Msg.LoginSubmitted)

    assertEquals(t.state.login.submitting, true)
    assertEquals(t.state.login.error, None)
    assertEquals(t.effects.size, 1)

  test("submitting an incomplete form is rejected without a request"):
    val t = update(AppState.initial, Msg.LoginSubmitted)

    assertEquals(t.effects, Nil)
    assertEquals(t.state.login.submitting, false)
    assert(t.state.login.error.isDefined)

  test("a double submit does not fire a second request"):
    val filled = update(update(AppState.initial, Msg.UsernameChanged("bob")).state, Msg.PasswordChanged("pw")).state
    val busy   = update(filled, Msg.LoginSubmitted).state

    val t = update(busy, Msg.LoginSubmitted)
    assertEquals(t.effects, Nil)

  test("a successful login moves to the dashboard and forgets the password"):
    val filled = update(update(AppState.initial, Msg.UsernameChanged("bob")).state, Msg.PasswordChanged("pw")).state
    val busy   = update(filled, Msg.LoginSubmitted).state

    val s = update(busy, Msg.LoginSucceeded(alice)).state

    assertEquals(s.screen, Screen.Dashboard)
    assertEquals(s.user, Some(alice))
    assertEquals(s.login.submitting, false)
    assertEquals(s.login.password, "", "the password must not linger in memory after login")

  test("a failed login shows the message and stays put, keeping the username"):
    val filled = update(update(AppState.initial, Msg.UsernameChanged("bob")).state, Msg.PasswordChanged("pw")).state
    val busy   = update(filled, Msg.LoginSubmitted).state

    val s = update(busy, Msg.LoginFailed(ApiError.Unauthorized)).state

    assertEquals(s.screen, Screen.Login)
    assertEquals(s.user, None)
    assertEquals(s.login.submitting, false)
    assertEquals(s.login.username, "bob", "retyping the username after a typo in the password is rude")
    assertEquals(s.login.password, "")
    assert(s.login.error.isDefined)

  test("a restored session skips the login screen"):
    val s = update(AppState.initial, Msg.SessionRestored(alice)).state
    assertEquals(s.screen, Screen.Dashboard)
    assertEquals(s.user, Some(alice))
    assertEquals(s.booting, false)

  test("no session leaves the user on the login screen, no longer booting"):
    val s = update(AppState.initial, Msg.SessionAbsent).state
    assertEquals(s.screen, Screen.Login)
    assertEquals(s.booting, false)
    assertEquals(s.login.error, None, "arriving unauthenticated is not an error to show")

  test("logging out returns to a clean login screen"):
    val dashboard = update(AppState.initial, Msg.SessionRestored(alice)).state

    val requested = update(dashboard, Msg.LogoutRequested)
    assertEquals(requested.effects.size, 1)

    val s = update(requested.state, Msg.LoggedOut).state
    assertEquals(s.screen, Screen.Login)
    assertEquals(s.user, None)
    assertEquals(s.login.username, "")
    assertEquals(s.login.password, "")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt frontend/testFull`
Expected: FAIL — `AppState`, `Msg`, `Update` do not exist.

- [ ] **Step 3: Write state, messages and update**

`frontend/src/main/scala/lmbot/frontend/AppState.scala`:

```scala
package lmbot.frontend

import lmbot.shared.domain.UserView

enum Screen:
  case Login, Dashboard

case class LoginForm(
  username: String = "",
  password: String = "",
  submitting: Boolean = false,
  error: Option[String] = None
)

/** `booting` is true until the app has asked the server whether the browser
  * already holds a valid session, so the login form is not flashed at a user
  * who is in fact already signed in.
  */
case class AppState(
  screen: Screen,
  login: LoginForm,
  user: Option[UserView],
  booting: Boolean
)

object AppState:
  val initial: AppState = AppState(Screen.Login, LoginForm(), None, booting = true)
```

`frontend/src/main/scala/lmbot/frontend/Msg.scala`:

```scala
package lmbot.frontend

import lmbot.shared.api.ApiError
import lmbot.shared.domain.UserView

enum Msg:
  case UsernameChanged(value: String)
  case PasswordChanged(value: String)
  case LoginSubmitted
  case LoginSucceeded(user: UserView)
  case LoginFailed(error: ApiError)
  case SessionRestored(user: UserView)
  case SessionAbsent
  case LogoutRequested
  case LoggedOut
```

`frontend/src/main/scala/lmbot/frontend/Update.scala`:

```scala
package lmbot.frontend

import gears.async.Async
import lmbot.frontend.api.ApiClient
import lmbot.frontend.elm.{Effect, Transition}
import lmbot.shared.api.{ApiError, LoginRequest}

/** Every decision the frontend makes lives here, and this function is pure:
  * it returns the next state plus a description of what to do, never doing it.
  */
class Update(api: ApiClient):

  def apply(state: AppState, msg: Msg): Transition[AppState, Msg] = msg match

    case Msg.UsernameChanged(v) =>
      Transition(state.copy(login = state.login.copy(username = v)), Nil)

    case Msg.PasswordChanged(v) =>
      Transition(state.copy(login = state.login.copy(password = v)), Nil)

    case Msg.LoginSubmitted =>
      val form = state.login
      if form.submitting then Transition(state, Nil)
      else if form.username.isEmpty || form.password.isEmpty then
        Transition(
          state.copy(login = form.copy(error = Some("Enter both a username and a password."))),
          Nil
        )
      else
        val request = LoginRequest(form.username, form.password)
        val effect = new Effect[Msg]:
          def run(using Async): Option[Msg] = Some:
            api.login(request) match
              case Right(user) => Msg.LoginSucceeded(user)
              case Left(err)   => Msg.LoginFailed(err)
        Transition(state.copy(login = form.copy(submitting = true, error = None)), List(effect))

    case Msg.LoginSucceeded(user) =>
      // Drop the password as soon as it has served its purpose.
      Transition(
        state.copy(screen = Screen.Dashboard, user = Some(user), login = LoginForm(), booting = false),
        Nil
      )

    case Msg.LoginFailed(err) =>
      Transition(
        state.copy(
          login = state.login.copy(submitting = false, password = "", error = Some(explain(err))),
          booting = false
        ),
        Nil
      )

    case Msg.SessionRestored(user) =>
      Transition(state.copy(screen = Screen.Dashboard, user = Some(user), booting = false), Nil)

    case Msg.SessionAbsent =>
      Transition(state.copy(screen = Screen.Login, user = None, booting = false), Nil)

    case Msg.LogoutRequested =>
      val effect = new Effect[Msg]:
        def run(using Async): Option[Msg] =
          api.logout()
          // Whether or not the server agreed, this browser is now signed out.
          Some(Msg.LoggedOut)
      Transition(state, List(effect))

    case Msg.LoggedOut =>
      Transition(AppState(Screen.Login, LoginForm(), None, booting = false), Nil)

  private def explain(err: ApiError): String = err match
    case ApiError.Unauthorized  => "Wrong username or password."
    case ApiError.Forbidden     => "That account is disabled. Ask the administrator."
    case ApiError.Unexpected(d) => s"Something went wrong: $d"
    case other                  => other.message
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `sbt frontend/testFull`
Expected: PASS — 10 update tests, plus the earlier runtime and bridge tests.

- [ ] **Step 5: Write the views and entry point**

`frontend/src/main/scala/lmbot/frontend/view/AppView.scala`:

```scala
package lmbot.frontend.view

import com.raquo.laminar.api.L.*
import lmbot.frontend.elm.Runtime
import lmbot.frontend.{AppState, Msg, Screen}

/** Rendering only. Handlers do exactly one thing: send a message (spec §5.6).
  * Projections use `Signal.map(...).distinct` so the DOM updates narrowly.
  */
object AppView:

  def apply(rt: Runtime[AppState, Msg]): HtmlElement =
    val state = rt.store.signal
    div(
      cls := "app",
      child <-- state.map(s => (s.booting, s.screen)).distinct.map {
        case (true, _)              => booting
        case (false, Screen.Login)  => loginPage(rt)
        case (false, Screen.Dashboard) => dashboard(rt)
      }
    )

  private def booting: HtmlElement =
    div(cls := "booting", p("Loading…"))

  private def loginPage(rt: Runtime[AppState, Msg]): HtmlElement =
    val form = rt.store.signal.map(_.login).distinct
    div(
      cls := "login",
      h1("lm-bot"),
      formTag(
        onSubmit.preventDefault.mapTo(Msg.LoginSubmitted) --> (m => rt.dispatch(m)),
        label(
          "Username",
          input(
            tpe := "text",
            autoComplete := "username",
            value <-- form.map(_.username).distinct,
            onInput.mapToValue --> (v => rt.dispatch(Msg.UsernameChanged(v)))
          )
        ),
        label(
          "Password",
          input(
            tpe := "password",
            autoComplete := "current-password",
            value <-- form.map(_.password).distinct,
            onInput.mapToValue --> (v => rt.dispatch(Msg.PasswordChanged(v)))
          )
        ),
        button(
          tpe := "submit",
          disabled <-- form.map(_.submitting).distinct,
          child.text <-- form.map(f => if f.submitting then "Signing in…" else "Sign in").distinct
        ),
        child.maybe <-- form.map(_.error).distinct.map(_.map(msg => p(cls := "error", role := "alert", msg)))
      )
    )

  private def dashboard(rt: Runtime[AppState, Msg]): HtmlElement =
    val user = rt.store.signal.map(_.user).distinct
    div(
      cls := "dashboard",
      h1("lm-bot"),
      child.maybe <-- user.map(_.map(u => p(s"Signed in as ${u.displayName}"))),
      button("Sign out", onClick.mapTo(Msg.LogoutRequested) --> (m => rt.dispatch(m))),
      p(cls := "placeholder", "Monitors will appear here.")
    )
```

Note that `-->` appears only inside event handlers here. An `-->` or stream combinator anywhere else in a view is the review flag spec §5.7.3 describes.

`frontend/src/main/scala/lmbot/frontend/Main.scala`:

```scala
package lmbot.frontend

import com.raquo.laminar.api.L.{render, *}
import gears.async.*
import gears.async.js.{JsAsyncFromSync, JsAsyncOperations}
import lmbot.frontend.api.ApiClient
import lmbot.frontend.elm.{Effect, Runtime}
import lmbot.frontend.view.AppView
import org.scalajs.dom
import sttp.model.Uri

@main def main(): Unit =
  // Same origin as the page: the backend serves this app, which is also what
  // lets the browser attach the HttpOnly session cookie to API calls.
  val baseUri = Uri.unsafeParse(dom.window.location.origin)
  val api = ApiClient(baseUri)
  // `.apply` eta-expands the method into the function `Runtime` expects — an
  // `Update` instance is not itself a Function2.
  val runtime = Runtime[AppState, Msg](AppState.initial, Update(api).apply)

  val container = dom.document.getElementById("app")
  render(container, AppView(runtime))

  // `Async.blocking` is JVM-only — it needs `FromSync.Blocking`. On Scala.js the
  // loop starts through `fromSync`, suspending via JSPI instead of blocking a
  // thread.
  //
  // `JsAsyncFromSync` — NOT `UnsafeJsAsyncFromSync`. The unsafe variant's
  // `Output[T]` is `T`, which is tempting because it keeps `Future` out of this
  // file (spec §5.7.1), but it skips the `js.async` wrapper and so provides no
  // `WebAssembly.promising` context. The app then renders and hangs on its first
  // suspension with `SuspendError`. Both variants link, so the compiler and the
  // test suite will not catch this — only Task 12 Step 7 will. The returned
  // Future is discarded; that is the price of a legal async boundary.
  given Async.FromSync = JsAsyncFromSync
  given AsyncOperations = JsAsyncOperations

  Async.fromSync:
    Async.group:
      // `booting` stays true until this answers, so the login form is not
      // flashed at someone who already holds a valid session.
      val restore = new Effect[Msg]:
        def run(using Async): Option[Msg] = Some:
          api.me() match
            case Right(user) => Msg.SessionRestored(user)
            case Left(_)     => Msg.SessionAbsent
      Future(restore.run.foreach(runtime.dispatch))
      runtime.run
```

`frontend/index.html`:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>lm-bot</title>
  </head>
  <body>
    <div id="app"></div>
    <!-- Served by StaticRoutes.assets, which is mounted under /assets. -->
    <script type="module" src="/assets/main.js"></script>
  </body>
</html>
```

- [ ] **Step 6: Run the full frontend suite and link**

Run: `sbt frontend/testFull frontend/fastLinkJS`
Expected: all tests pass; linking succeeds.

- [ ] **Step 7: Commit**

```bash
git add frontend
git commit -m "feat(frontend): login and dashboard screens on the Elm runtime"
```

---

### Task 12: Static asset serving, packaging, and an end-to-end login

**Files:**
- Create: `backend/src/main/scala/lmbot/backend/http/StaticRoutes.scala`
- Create: `backend/src/main/scala/lmbot/backend/Main.scala`
- Create: `Dockerfile`, `docker-compose.yml`, `.dockerignore`
- Create: `README.md`
- Modify: `build.sbt` (frontend assets into the backend's resources)
- Test: `backend/src/test/scala/lmbot/backend/StaticRoutesTest.scala`

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `object StaticRoutes` with `endpoints: List[ServerEndpoint[Any, Identity]]` serving the linked frontend from the `web/` classpath prefix, with an SPA fallback to `index.html`.
  - `object Main` — the composition root: config → database → migrate → bootstrap admin → routes → server.

- [ ] **Step 1: Write the failing test**

`backend/src/test/scala/lmbot/backend/StaticRoutesTest.scala`:

```scala
package lmbot.backend

import lmbot.backend.http.{Server, StaticRoutes}
import sttp.client3.*
import sttp.model.StatusCode

class StaticRoutesTest extends munit.FunSuite:

  private var server: com.sun.net.httpserver.HttpServer = scala.compiletime.uninitialized
  private var baseUri: Uri                              = scala.compiletime.uninitialized
  private val http                                      = HttpClientSyncBackend()

  override def beforeAll(): Unit =
    server  = Server.start("127.0.0.1", 0, StaticRoutes.endpoints)
    baseUri = uri"http://127.0.0.1:${server.getAddress.getPort}"

  override def afterAll(): Unit =
    if server != null then server.stop(0)

  test("the index page is served at the root"):
    val r = basicRequest.get(uri"$baseUri/").send(http)
    assertEquals(r.code, StatusCode.Ok)
    assert(r.body.exists(_.contains("""<div id="app">""")), s"unexpected body: ${r.body}")

  test("a client-side route falls back to the index page so deep links work"):
    val r = basicRequest.get(uri"$baseUri/monitors/42").send(http)
    assertEquals(r.code, StatusCode.Ok)
    assert(r.body.exists(_.contains("""<div id="app">""")))

  test("a path that looks like a file 404s instead of returning the index page"):
    val r = basicRequest.get(uri"$baseUri/nope.js").send(http)
    assertEquals(r.code, StatusCode.NotFound)

  test("a missing asset under the assets prefix is a 404"):
    val r = basicRequest.get(uri"$baseUri/assets/missing.js").send(http)
    assertEquals(r.code, StatusCode.NotFound)
```

For this to pass, `backend/src/main/resources/web/index.html` must exist. Copy `frontend/index.html` there as part of Step 3's build wiring.

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "backend/testOnly lmbot.backend.StaticRoutesTest"`
Expected: FAIL — `StaticRoutes` does not exist.

- [ ] **Step 3: Write static serving and the composition root**

`backend/src/main/scala/lmbot/backend/http/StaticRoutes.scala` — endpoint order matters: `assets` is tried first, so a real file always wins over the fallback.

```scala
package lmbot.backend.http

import sttp.model.StatusCode
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.files.*
import sttp.tapir.server.ServerEndpoint

object StaticRoutes:

  private val loader = getClass.getClassLoader

  /** Built assets are mounted under a prefix on purpose. If they were served
    * from the root, this endpoint would match *every* path and answer 404 for
    * unknown ones, and the SPA fallback below would never be reached.
    */
  private val assets: ServerEndpoint[Any, Identity] =
    staticResourcesGetServerEndpoint[Identity]("assets")(loader, "web")

  /** Everything else is a client-side route, so hand back the index page and
    * let the app deal with it. A path whose last segment contains a dot is
    * treated as a missing file and 404s, rather than quietly returning HTML to
    * something that asked for a script.
    */
  private val spaFallback: ServerEndpoint[Any, Identity] =
    endpoint.get
      .in(paths)
      .out(htmlBodyUtf8)
      .errorOut(statusCode)
      .serverLogicPure[Identity] { segments =>
        if segments.lastOption.exists(_.contains('.')) then Left(StatusCode.NotFound)
        else
          Option(loader.getResourceAsStream("web/index.html")) match
            case Some(stream) => Right(new String(stream.readAllBytes(), "UTF-8"))
            case None         => Left(StatusCode.NotFound)
      }

  val endpoints: List[ServerEndpoint[Any, Identity]] = List(assets, spaFallback)
```

`backend/src/main/scala/lmbot/backend/Main.scala`:

```scala
package lmbot.backend

import lmbot.backend.auth.{AdminBootstrap, AuthService}
import lmbot.backend.config.Config
import lmbot.backend.db.{Database, SessionRepo, UserRepo}
import lmbot.backend.http.{AuthRoutes, HealthRoutes, Server, StaticRoutes}
import org.slf4j.LoggerFactory

import java.time.OffsetDateTime
import scala.jdk.CollectionConverters.*

/** Composition root: everything is wired by hand, in one readable place
  * (spec §5.7.5 — no DI framework, no reflection).
  */
object Main:

  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    Config.fromEnv(System.getenv().asScala.toMap) match
      case Left(errors) =>
        errors.foreach(e => log.error(s"Configuration error: $e"))
        sys.exit(1)

      case Right(config) =>
        val ds = Database.dataSource(config.dbUrl, config.dbUser, config.dbPassword.value)
        Database.migrate(ds)
        val xa = Database.transactor(ds)

        val users    = UserRepo(xa)
        val sessions = SessionRepo(xa)

        AdminBootstrap(users).run(config.adminUsername, config.adminPassword.map(_.value)) match
          case AdminBootstrap.Outcome.Created(username) =>
            log.info(s"Created initial admin account '$username'")
          case AdminBootstrap.Outcome.SkippedUsersExist =>
            log.info("Users already exist; skipping admin bootstrap")
          case AdminBootstrap.Outcome.MissingCredentials =>
            log.warn(
              "No users exist and ADMIN_USERNAME/ADMIN_PASSWORD are not both set — " +
                "nobody can log in. Set them and restart."
            )

        val auth   = AuthService(users, sessions, config.sessionTtl, () => OffsetDateTime.now())
        val routes = AuthRoutes(auth, config.cookieSecure, config.sessionTtl)

        val server = Server.start(
          config.httpHost,
          config.httpPort,
          HealthRoutes.endpoints ++ routes.endpoints ++ StaticRoutes.endpoints
        )

        log.info(s"lm-bot listening on ${config.httpHost}:${server.getAddress.getPort}")

        Runtime.getRuntime.addShutdownHook(
          Thread: () =>
            log.info("Shutting down")
            server.stop(3)
            ds.close()
        )
```

- [ ] **Step 4: Wire the linked frontend into the backend's resources**

Add to `build.sbt`, replacing the `backend` project's settings block with one that also copies the frontend output and `index.html` into the backend's managed resources:

```scala
lazy val backend = project
  .in(file("backend"))
  .dependsOn(sharedJVM)
  .settings(commonSettings)
  .settings(
    name := "lm-bot-backend",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-jdkhttp-server" % v.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-files"          % v.tapir,
      "com.augustnagro"             %% "magnum"               % v.magnum,
      "org.flywaydb"                 % "flyway-core"          % v.flyway,
      "org.flywaydb"                 % "flyway-database-postgresql" % v.flyway,
      "org.postgresql"               % "postgresql"           % v.postgres,
      "com.zaxxer"                   % "HikariCP"             % v.hikari,
      "de.mkammerer"                 % "argon2-jvm"           % v.argon2,
      "ch.qos.logback"               % "logback-classic"      % v.logback,
      "org.scalameta"                %% "munit"                % v.munit          % Test,
      "org.testcontainers"           % "postgresql"           % v.testcontainers % Test,
      "com.softwaremill.sttp.client3" %% "core"               % v.sttp           % Test
    ),
    javacOptions ++= Seq("-source", "25", "-target", "25"),
    Compile / mainClass := Some("lmbot.backend.Main"),

    // Package the linked frontend as classpath resources under `web/`, which
    // is where StaticRoutes looks for it.
    Compile / resourceGenerators += Def.task {
      val linked  = (frontend / Compile / fullLinkJS).value
      val outDir  = (frontend / Compile / fullLinkJSOutput).value
      val target  = (Compile / resourceManaged).value / "web"
      IO.copyDirectory(outDir, target, overwrite = true)
      IO.copyFile(baseDirectory.value / ".." / "frontend" / "index.html", target / "index.html")
      val _ = linked
      (target ** "*").get().filter(_.isFile)   // sbt 2: PathFinder.get needs ()
    }.taskValue
  )
```

For the test in Step 1 to run without linking Wasm every time, also commit a static copy at `backend/src/main/resources/web/index.html` (identical to `frontend/index.html`). The generated resources overwrite it in a real build.

- [ ] **Step 5: Run the static test and the full suite**

Run: `sbt "backend/testOnly lmbot.backend.StaticRoutesTest"`
Expected: PASS — 4 tests.

Run: `sbt testFull`
Expected: every module's tests pass.

- [ ] **Step 6: Write the container and compose files**

First add the assembly settings to the `backend` project in `build.sbt` (a single fat jar keeps the runtime image to one `COPY` and avoids shipping a coursier cache):

```scala
    assembly / mainClass := Some("lmbot.backend.Main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*)      => MergeStrategy.discard
      case PathList("module-info.class") => MergeStrategy.discard
      case x                             => (assembly / assemblyMergeStrategy).value(x)
    }
```

`Dockerfile`:

```dockerfile
# Build stage: link the frontend to Wasm, then assemble the backend fat jar.
FROM sbtscala/scala-sbt:eclipse-temurin-25.0.3_9_1.12.14_3.8.4 AS build
WORKDIR /build

# Node 26+: Node 24/25 carry a V8 bug that breaks Gears' nested async contexts.
RUN curl -fsSL https://deb.nodesource.com/setup_26.x | bash - && \
    apt-get install -y nodejs && \
    rm -rf /var/lib/apt/lists/*

# Dependencies first, so a source-only change does not re-resolve them.
COPY project/build.properties project/plugins.sbt project/
COPY build.sbt ./
RUN sbt update

COPY shared shared
COPY backend backend
COPY frontend frontend
# backend/assembly triggers frontend/fullLinkJS through the resourceGenerators
# wiring added in Step 4, so the app is bundled into the jar.
#
# sbt 2 centralises output under target/out/jvm/scala-3.8.4/<project>/, not
# <project>/target/. Rather than hardcode a layout that sbt may reorganise
# again, find the artifact and normalise its name here.
RUN sbt backend/assembly \
 && find target -name 'lm-bot-backend-assembly-*.jar' -print -quit \
      | xargs -I{} cp {} /build/lm-bot.jar \
 && test -s /build/lm-bot.jar

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=build /build/lm-bot.jar /app/lm-bot.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/lm-bot.jar"]
```

The `test -s` matters: without it a layout change would silently produce an image containing no jar, and you would only find out at `docker run`.

`docker-compose.yml`:

```yaml
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_USER: lmbot
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD}
      POSTGRES_DB: lmbot
    volumes:
      - lmbot-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U lmbot"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  backend:
    build: .
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/lmbot
      DATABASE_USER: lmbot
      DATABASE_PASSWORD: ${POSTGRES_PASSWORD:?set POSTGRES_PASSWORD}
      HTTP_PORT: 8080
      # TLS is terminated by the operator's reverse proxy in front of this
      # container (spec §6), but the cookie must still be marked Secure.
      COOKIE_SECURE: "true"
      ADMIN_USERNAME: ${ADMIN_USERNAME:-}
      ADMIN_PASSWORD: ${ADMIN_PASSWORD:-}
    ports:
      - "127.0.0.1:8080:8080"
    restart: unless-stopped

volumes:
  lmbot-data:
```

`.dockerignore`:

```
target/
*/target/
project/target/
project/project/
.git/
.metals/
.bloop/
.bsp/
node_modules/
```

- [ ] **Step 7: Verify the container builds and login works end to end**

```bash
POSTGRES_PASSWORD=devpassword ADMIN_USERNAME=admin ADMIN_PASSWORD=devadminpw \
  docker compose up --build -d
```

On this dev machine `docker` is Podman, so that is `podman compose` under the hood; if the subcommand is unavailable, use `podman compose` explicitly or install a compose provider. The compose file itself is runtime-agnostic.

Then check each of these:

```bash
# Health.
curl -fsS localhost:8080/health
# Expected: ok

# The app shell is served.
curl -fsS localhost:8080/ | grep -q 'id="app"' && echo "index ok"

# Login as the bootstrapped admin, keeping the cookie jar.
curl -fsS -c /tmp/lmbot-cookies -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"devadminpw"}'
# Expected: {"id":1,"username":"admin",...,"role":"Admin",...}

# The session works.
curl -fsS -b /tmp/lmbot-cookies localhost:8080/api/auth/me
# Expected: the same user

# Logout invalidates it.
curl -fsS -b /tmp/lmbot-cookies -c /tmp/lmbot-cookies -X POST localhost:8080/api/auth/logout
curl -s -o /dev/null -w '%{http_code}\n' -b /tmp/lmbot-cookies localhost:8080/api/auth/me
# Expected: 401
```

Finally — **this step is the one that decides whether the frontend works at all, so do not skip or infer it.** Open `http://localhost:8080` in a JSPI-capable browser (recent Chrome or Firefox), sign in as `admin`, confirm the dashboard renders, and confirm "Sign out" returns you to the login form. Keep the console open and treat any `SuspendError` as a failure even if the page looks fine.

Verify each of these separately, because they fail independently:

1. `/assets/main.js` and `/assets/main.wasm` return **200**. If they 404, the `resourceGenerators` wiring in Step 4 is missing — the page will render an empty `<div id="app">` and nothing else. Linking the frontend does not ship it.
2. The login form appears (not a stuck "Loading…"). A permanent "Loading…" means the session-restore effect threw; check for `SuspendError` and confirm `Main` uses `JsAsyncFromSync`.
3. Typing updates the field — proves `dispatch` and the event loop work.
4. **Submitting reaches the dashboard.** This is the critical assertion. A `POST /api/auth/login` returning 200 is *not* sufficient: the server can authenticate successfully while `Msg.LoginSucceeded` is never dispatched, leaving the UI on the login form. Watch the rendered state, not the network tab.
5. Sign out returns to the login form.

**Verified working end to end** on Gears 0.3.1 + Scala.js 1.22.0 + Wasm/JSPI, in headless Chromium 150: login reaches the dashboard, the button shows `Signing in…` while the request is in flight, sign-out returns to the login form, and no exceptions are raised. The Elm-on-Gears architecture in spec §5.6 works in a browser — DOM handlers, the channel, the event loop, and effect-spawned fibers all behave.

Two traps produced a login that *silently never completed*, and both are already fixed in this plan — if you hit either symptom, check these first rather than suspecting Gears or JSPI:

- **A permanent "Loading…" screen** → `Main` is using `UnsafeJsAsyncFromSync`. Use `JsAsyncFromSync` (Task 11).
- **Login returns 200 but the UI stays on the form** → a cookie output is declared with `setCookie` instead of `setCookieOpt` (Task 3). The client cannot read `Set-Cookie` and fails with "Cannot decode: Missing".

Neither is an upstream limitation. Earlier drafts of this plan blamed [scala-js#5393](https://github.com/scala-js/scala-js/issues/5393) — that issue is about permitted top-level export *shapes* and is unrelated. JSPI is a finished, browser-tested feature ([#5064](https://github.com/scala-js/scala-js/issues/5064)/[#5130](https://github.com/scala-js/scala-js/pull/5130) implemented it, [#5366](https://github.com/scala-js/scala-js/pull/5366) tests `SuspendError` against Chrome and Firefox, [#5361](https://github.com/scala-js/scala-js/pull/5361) made the Wasm backend non-experimental).

**The §5.1 fallback is therefore not required and should not be applied.** If a genuinely new frontend blocker appears, stop and report rather than improvising — that decision belongs to the spec's owner — but do not reach for the fallback on the strength of a `SuspendError` alone.

Then `docker compose down -v`.

- [ ] **Step 8: Write the README**

`README.md`:

````markdown
# lm-bot

Self-hosted monitoring and booking for Luxmed appointments.

See [the design document](docs/superpowers/specs/2026-07-27-lm-bot-prd-design.md)
and [the implementation roadmap](docs/superpowers/plans/2026-07-27-lm-bot-roadmap.md).

## Status

Plan 1 of 6 complete: foundation and authentication. No Luxmed integration yet.

## Requirements

- **Nix with flakes**, and ideally **direnv** (plus `nix-direnv` for caching).
  The flake pins everything else: Temurin 25, the sbt launcher, Node 26,
  Metals, scalafmt, `psql`.
- **A container runtime on the host** — rootless Podman or Docker. A devShell
  cannot provide one. Testcontainers needs it for the backend tests; the
  devShell wires it up for Podman automatically.
- **A JSPI-capable browser** (recent Chrome or Firefox) — the frontend compiles
  to WebAssembly.

Node's version is not a preference: Node 24 and 25 contain a V8 bug that
stack-overflows in the nested async contexts Gears relies on. The flake pins
Node 26 so this cannot drift.

## Development

```bash
direnv allow              # once; or `nix develop`

sbt testFull              # everything; needs a container runtime
sbt backend/testFull      # backend only
sbt frontend/testFull     # frontend, incl. Gears runtime suites
sbt frontend/fastLinkJS   # link the frontend to Wasm
```

The build runs on **sbt 2** (declared in `project/build.properties`); the sbt
binary from the flake is only a launcher. Build output is centralised under
`target/out/`.

If Testcontainers reports "Could not find a valid Docker environment", you are
outside the devShell — that is where `DOCKER_HOST` gets pointed at Podman.

## Configuration

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `DATABASE_URL` | yes | — | JDBC URL |
| `DATABASE_USER` | yes | — | database user |
| `DATABASE_PASSWORD` | yes | — | database password |
| `HTTP_HOST` | no | `0.0.0.0` | bind address |
| `HTTP_PORT` | no | `8080` | bind port |
| `COOKIE_SECURE` | no | `true` | set `false` only for plain-HTTP local dev |
| `SESSION_TTL_DAYS` | no | `7` | session lifetime |
| `ADMIN_USERNAME` | no | — | read **only** when the `users` table is empty |
| `ADMIN_PASSWORD` | no | — | as above |

## Deployment

Run behind your own HTTPS reverse proxy; lm-bot does not terminate TLS.

```bash
POSTGRES_PASSWORD=... ADMIN_USERNAME=... ADMIN_PASSWORD=... docker compose up -d
```
````

- [ ] **Step 9: Commit**

```bash
git add .
git commit -m "feat: serve the frontend, containerise, and document the skeleton"
```

---

## Spec coverage

What this plan implements, and what it deliberately leaves to later plans, so the gap is auditable rather than accidental:

| Spec section | Plan 1 |
|---|---|
| §2 users & deployment | Admin bootstrap, no self-registration, docker-compose behind a proxy — **done**. Admin create/disable/reset-password UI → Plan 7. |
| §3.1 user management | Login, cookie sessions — **done**. Admin actions and password change → Plan 7. |
| §3.2–§3.5 Luxmed, monitors, auto-book, Telegram | **None.** Plans 3–6. |
| §4 future versions | Out of scope by definition. |
| §5.1 stack | **Done and proven** — every layer exercised, including Wasm linking. |
| §5.2 modules | `shared` / `backend` / `frontend` with the layering described — **done**. |
| §5.3 domain & persistence | `users` and `sessions` tables, ownership-in-service-layer pattern — **done**. Remaining tables → Plans 4–6. `Europe/Warsaw` handling → Plan 3 (nothing needs it yet). |
| §5.4 Luxmed client | **None.** Plans 2 (spike) and 3 (client). |
| §5.5 monitor engine | **None.** Plan 5. |
| §5.6 Elm-on-Gears | **Done** — store, channel, loop, pure `update`, render-only views. |
| §5.7 style conventions | **Done and enforced** by the Definition of Done greps below. |
| §6 security | Argon2id, hashed opaque tokens, cookie flags, service-layer authorization, no in-app TLS, secret masking — **done**. Credential encryption at rest, plus session and device-identity encryption → Plan 4. |
| §7 error handling | `ApiError` as values, exceptions only for bugs — **done** for the internal API. `LuxmedError` → Plan 3. |
| §8 testing | Pure shared tests, Testcontainers backend integration tests, DOM-free frontend tests, CI on every push — **done**. Luxmed mock server → Plan 3. |
| §9 observability & ops | Structured logging, `/health`, env config, docker-compose — **done**. |
| §10 risks | The Gears/Wasm risk is retired or escalated by Task 1 Step 5 and Task 12 Step 7. |

## Definition of done for Plan 1

- [ ] `nix develop --command true` succeeds from a clean clone, and the banner reports Node 26.x and a reachable container runtime.
- [ ] `sbt testFull` is green **and reports a non-zero total for every project**, including Testcontainers-backed Postgres tests and the frontend `RuntimeTest` and `BridgeTest` suites. Use `testFull`, not `test` — see the note below. A green run achieved by excluding or renaming test files does not count: if a suite cannot run, the plan is not done.
- [ ] `sbt frontend/fastLinkJS` emits `main.wasm`. A build that links only by turning off WebAssembly does not satisfy this.
- [ ] `sbt frontend/fullLinkJS` produces Wasm output.
- [ ] CI passes on a pushed branch.
- [ ] `docker compose up --build` yields a working app on `localhost:8080`.
- [ ] A human can sign in through the browser as the bootstrapped admin, see the dashboard, and sign out — with `/assets/main.wasm` returning 200 and no `SuspendError` in the console. **Watch the rendered state, not the network tab:** a 200 from `/api/auth/login` while the UI stays on the login form is a failure, and is the currently-known failure mode (Task 12 Step 7).
- [ ] No `scala.concurrent.Future` appears outside `frontend/.../bridge/`. Verify: `grep -rn "scala.concurrent" --include=*.scala shared backend frontend | grep -v /bridge/` returns nothing.
- [ ] No Airstream combinator outside the store and view projections. Verify by reading `frontend/src/main/scala/lmbot/frontend/view/AppView.scala` and confirming `-->` appears only in event handlers.
- [ ] Secrets do not appear in logs. Verify: `docker compose logs backend | grep -iE "devadminpw|devpassword"` returns nothing.

## Notes carried forward

- `Config` gains `LUXMED_APP_VERSION`, `CREDENTIAL_MASTER_KEY`, and `TELEGRAM_BOT_TOKEN`. The `Secret` wrapper introduced in Task 7 is the type to use for all three.
- The `Europe/Warsaw` normalisation helper has no home yet — it belongs with the Luxmed datetime decoder in Plan 3, not in `shared`, because only Luxmed data needs it.
- `Runtime.awaitQuiescence` is test-only scaffolding. If Plan 5 introduces long-lived effects (timers, polling), it will need revisiting.
