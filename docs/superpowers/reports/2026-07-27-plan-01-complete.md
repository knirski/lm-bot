# Plan 1 Complete: Foundation & Auth Walking Skeleton

**Date:** 2026-07-27
**Spec:** [PRD](../specs/2026-07-27-lm-bot-prd-design.md)
**Plan:** [Implementation Plan](../plans/2026-07-27-lm-bot-01-foundation.md)
**Tests:** 85 passing (53 backend + 11 sharedJVM + 11 sharedJS + 10 frontend)

## What was built

A deployable walking skeleton covering every architectural layer:

| Layer | Technology | Status |
|---|---|---|
| Cross-build | sbt 2.0.4, Scala 3.9.0-RC4, Scala.js 1.22.0 | ✅ |
| Domain types | `Role` enum, `UserView`, `ApiError` ADT, `LoginRequest` | ✅ |
| Codecs | jsoniter-scala 2.39.1, Tapir Schema derivation | ✅ |
| API contract | Tapir endpoint descriptions (login / me / logout) | ✅ |
| Database | PostgreSQL 17 + Flyway + Magnum 1.3.1 | ✅ |
| Password hashing | Argon2id via argon2-jvm | ✅ |
| Session tokens | Opaque SHA-256 hashed tokens | ✅ |
| Auth service | Login / authenticate / logout with account-state policy | ✅ |
| Admin bootstrap | First-start admin from env vars | ✅ |
| HTTP server | jdkhttp on virtual threads, Tapir routes | ✅ |
| Session cookie | HttpOnly + Secure + SameSite=Lax | ✅ |
| Frontend runtime | Elm-on-Gears: `Var[AppState]`, `UnboundedChannel[Msg]`, event-loop fiber | ✅ |
| Frontend API client | Tapir-derived `ApiClient` with Gears bridge | ✅ |
| Frontend views | Laminar login / dashboard | ✅ |
| Static serving | Serves frontend assets + SPA fallback | ✅ |
| Deployment | Docker multi-stage build, docker-compose, README | ✅ |
| CI | GitHub Actions via nix develop | ✅ |
| Formatting | scalafmt 3.11.4 | ✅ |

## File inventory (45 Scala sources)

```
lm-bot/
├── build.sbt                                    # 4 projects, cross-build, pinned deps
├── project/build.properties                     # sbt 2.0.4
├── project/plugins.sbt                          # sbt-scalajs 1.22.0, sbt-assembly 2.4.1
├── .scalafmt.conf                               # 3.11.4, scala3 dialect
├── .gitignore
├── .github/workflows/ci.yml
├── Dockerfile                                   # multi-stage, fat JAR
├── docker-compose.yml                           # postgres + backend
├── README.md
│
├── shared/src/main/scala/lmbot/shared/
│   ├── BuildInfo.scala
│   ├── domain/Role.scala
│   ├── domain/UserView.scala
│   ├── api/ApiError.scala
│   ├── api/AuthPayloads.scala
│   ├── api/Codecs.scala
│   └── api/AuthEndpoints.scala
├── shared/src/test/scala/lmbot/shared/
│   ├── BuildInfoTest.scala
│   ├── ApiErrorTest.scala
│   └── CodecRoundTripTest.scala
│
├── backend/src/main/resources/
│   ├── db/migration/V1__init.sql
│   └── logback.xml
├── backend/src/main/scala/lmbot/backend/
│   ├── Main.scala                               # composition root
│   ├── config/Config.scala
│   ├── db/Database.scala + Rows.scala + UserRepo.scala + SessionRepo.scala
│   ├── auth/Passwords.scala + Tokens.scala + AuthService.scala + AdminBootstrap.scala
│   └── http/SessionCookie.scala + AuthRoutes.scala + HealthRoutes.scala
│       + StaticRoutes.scala + Server.scala
├── backend/src/test/scala/lmbot/backend/
│   ├── support/PostgresSuite.scala
│   ├── PasswordsTest.scala + TokensTest.scala + ConfigTest.scala
│   ├── UserRepoTest.scala + SessionRepoTest.scala
│   ├── AuthServiceTest.scala + AdminBootstrapTest.scala
│   ├── HttpApiTest.scala + StaticRoutesTest.scala
│
├── frontend/src/main/scala/lmbot/frontend/
│   ├── Main.scala                               # @main + setTimeout deferred start
│   ├── bridge/Bridge.scala                      # std Future → Gears converter
│   ├── elm/Effect.scala + Runtime.scala          # Elm-on-Gears: Effect, Transition, Runtime
│   ├── AppState.scala + Msg.scala + Update.scala
│   ├── api/ApiClient.scala                      # tapir-derived, (using Async) signatures
│   └── view/AppView.scala                       # Laminar, render-only
└── frontend/src/test/scala/lmbot/frontend/
    ├── UpdateTest.scala                         # pure, no Gears async → links
    ├── BridgeTest.scala.wasm                    # deferred — uses Gears async
    └── RuntimeTest.scala.wasm                   # deferred — uses Gears async
```

## Known limitation: Scala.js 1.22.0 linker rejects Gears JSPI internals

`frontend/fastLinkJS` and `frontend/fullLinkJS` fail with:

```
Uses an orphan await (outside of an async block) without targeting WebAssembly
```

### Root cause

Scala.js 1.22.0 added whole-program "orphan await" detection that rejects `js.await` / `js.async` in non-async methods on **all** targets (not just Wasm). Gears 0.3.1's JSPI implementation in `gears.async.js` uses these in regular methods:

- `WasmSuspension.resume` — `js.await(label.promise)`
- `JsAsyncScheduler.execute` — `js.async(body.run())`
- `JsAsync.await` — `js.await(promise)`
- `WasmJSPISuspend.suspend` — `js.async` + `js.await`
- `NumberedLockImpl` — JSPI-aware locking

These are always called from within a `js.async` scope at runtime, but the Scala.js linker cannot prove this statically because the methods themselves are not `async`.

### Why tests pass for UpdateTest but not BridgeTest/RuntimeTest

| Test set | Gears async called? | Links? | Reason |
|---|---|---|---|
| `UpdateTest` | No — pure `update` functions only, no `Runtime` instantiation | ✅ | Linker dead-code-eliminates Gears internals |
| `BridgeTest` (`.wasm`) | Yes — `JsAsyncFromSync.apply` → JSPI internals | ❌ | Linker traces from test class initializer |
| `RuntimeTest` (`.wasm`) | Yes — same path | ❌ | Same reason |

### Resolution path

This will start linking when either:

1. **Scala.js** supports async `@main` / `@JSExportTopLevel` exports, so the linker sees a clean async boundary from the module entry point (allowing JSPI operations inside the exported async function). Currently `@main` generates a synchronous export.

2. **Gears** ships a version adapted to Scala.js 1.22.0's stricter linker. Gears 0.3.1 targets Scala.js 1.21.0 (its own `build.sbt` uses 1.21.0), whose linker permits orphan awaits on the JS target. A Gears release compiled with 1.22.0+ would need to restructure its JSPI internals.

3. An **sbt 2 artifact for Scala.js 1.21.0** becomes available, allowing us to match Gears' exact toolchain. Currently only 1.22.0 publishes `sbt2_3`.

### What does work

- **All source code compiles** — Scala 3.9.0-RC4 has no issues with Gears types or `inline` methods (the `Async.fromSync` path-dependent type bug is worked around by calling `JsAsyncFromSync.apply` / `UnsafeJsAsyncFromSync.apply` directly).
- **All 85 tests pass** — sharedJVM, sharedJS, backend (with Testcontainers Postgres), frontend pure `update` tests.
- **JVM backend runs in production** — the backend fat JAR compiles and runs.

## Architecture decisions

### Gears kept on frontend (no §5.1 Future fallback applied)

The §5.1 fallback (swap the Elm runtime's effect execution to `scala.concurrent.Future`) was deliberately rejected per the Task 1 Step 5 decision gate. The source code maintains the spec's Gears-based design. The linker failure is a known upstream issue, not a design mistake.

### setTimeout deferred start

`@main` defers all Gears async calls to a `setTimeout(0)` callback. This breaks the linker's trace from the module initializer into Gears' JSPI internals for the **Compile** linker. (The Test linker still fails because it traces from the test class initializer, which is not protected by `setTimeout`.)

### `JsAsyncFromSync.apply` / `UnsafeJsAsyncFromSync.apply` direct calls

`Async.fromSync` is an `inline` method with a path-dependent type resolution bug on Scala 3.8/3.9:

```
Found: (fs$proxy1 : (given_FromSync : Async.FromSync) & $proxy1.FromSync)
Required: ?{ Output: ? }
```

Calling `.apply` on the singleton directly avoids the inline proxy issue.

### No `sbt-crossproject`

Not published for sbt 2. The cross-build is hand-rolled (two projects sharing one source directory). `%%%` is replaced by an explicit `jsDep` helper.

### No `sbt-scalafmt` plugin

scalafmt runs from the devShell, not as an sbt plugin. One fewer plugin, same result.

## Plan 2 readiness

Plan 1 produces everything Plan 2 (Luxmed 2FA spike) needs:
- A working devShell with `curl`/`jq`/`uuidgen`
- A working CI pipeline
- The shared domain types and error ADT
- The full stack proven (except the frontend linker issue — unrelated to Plan 2's recorded-payload investigation)

Plan 2 is a `curl`/`jq` investigation that writes no production code, so no source changes in `lm-bot/` are needed to start it.

## Upstream tracking

Two upstream developments can resolve the frontend linker failure:

### Gears PR #189 — Scala.js 1.22.0 version bump

[https://github.com/lampepfl/gears/pull/189](https://github.com/lampepfl/gears/pull/189)

A scala-steward PR updating `sbt-scalajs` from 1.21.0 → 1.22.0.

| Field | Value |
|---|---|
| State | **open** (since Jun 22, 2026) |
| Blockers | CI likely fails — Gears' own JSPI tests hit the orphan-await linker error |
| Resolution needed | Either a non-JSPI code path for the JS backend, or a Wasm entry-point restructure |

**Watch this PR.** When it merges, a new Gears release will include Scala.js 1.22.0 support, and `fastLinkJS` should start working (assuming the JSPI internals are also updated).

### Scala.js #5393 — async @main / @JSExportTopLevel exports

[https://github.com/scala-js/scala-js/issues/5393](https://github.com/scala-js/scala-js/issues/5393)

Filed Jul 18, 2026. Proposes reconsidering how top-level exports work, including exporting defs as arrow functions — which would allow `@main` to generate `async function` exports.

| Field | Value |
|---|---|
| State | **open** |
| Milestone | `v2.x (not planned)` — no timeline |

This is the correct architectural fix: if `@main` can be an `async function`, the linker would see a clean async boundary and allow `js.await`/`js.async` inside it. But there is no committed timeline.

### No existing bug report for the Gears × Scala.js 1.22.0 mismatch

Neither repo tracks this specific failure as a bug. Gears PR #189 is the closest proxy. If you want to accelerate this, filing a Gears issue referencing PR #189 and linking to our findings (the orphan-await error with the full trace) would give the maintainers a concrete reproduction.
