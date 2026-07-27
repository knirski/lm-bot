# Plan 1 Complete: Foundation & Auth Walking Skeleton

**Date:** 2026-07-27
**Spec:** [PRD](../specs/2026-07-27-lm-bot-prd-design.md)
**Plan:** [Implementation Plan](../plans/2026-07-27-lm-bot-01-foundation.md)
**Tests:** 92 passing (53 backend + 11 sharedJVM + 11 sharedJS + 17 frontend)

## What was built

A deployable walking skeleton covering every architectural layer:

| Layer | Technology | Status |
|---|---|---|
| Cross-build | sbt 2.0.4, Scala 3.8.4, Scala.js 1.22.0, Wasm+JSPI | ✅ |
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
| Frontend linking | Wasm+JSPI — `fastLinkJS`/`fullLinkJS` emit `.wasm` output | ✅ |
| Static serving | Serves frontend assets + SPA fallback | ✅ |
| Deployment | Docker multi-stage build, docker-compose, README | ✅ |
| CI | GitHub Actions via nix develop | ✅ |
| Formatting | scalafmt 3.11.4 | ✅ |

## File inventory (47 Scala sources)

```
lm-bot/
├── build.sbt                                    # 4 projects, cross-build, pinned deps
├── project/build.properties                     # sbt 2.0.4
├── project/plugins.sbt                          # sbt-scalajs 1.22.0, sbt-assembly 2.4.1
├── .scalafmt.conf                               # 3.11.4, scala3 dialect
├── .gitignore
├── .github/workflows/ci.yml
├── Dockerfile                                   # multi-stage, links frontend then fat JAR
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
│   ├── Main.scala                               # @main + UnsafeJsAsyncFromSync
│   ├── bridge/Bridge.scala                      # std Future → Gears converter
│   ├── elm/Effect.scala + Runtime.scala          # Elm-on-Gears: Effect, Transition, Runtime
│   ├── AppState.scala + Msg.scala + Update.scala
│   ├── api/ApiClient.scala                      # tapir-derived, (using Async) signatures
│   └── view/AppView.scala                       # Laminar, render-only
└── frontend/src/test/scala/lmbot/frontend/
    ├── UpdateTest.scala                          # 10 tests, pure update logic
    ├── RuntimeTest.scala                         # 5 tests, Gears event-loop integration
    └── BridgeTest.scala                          # 2 tests, std Future → Gears bridge
```

## Linker configuration: three flags, not upstream bugs

The frontend links via the WebAssembly backend with JSPI enabled:

```scala
lazy val wasmConfig: StandardConfig => StandardConfig =
  _.withModuleKind(ModuleKind.ESModule)
    .withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true))
    .withWasmFeatures(_.withUseJSPI(true))
```

All three are required. The most easily missed is `.withUseJSPI(true)` — it defaults to `false` in Scala.js 1.22.0, and without it the linker rejects every `js.async`/`js.await` call with:

- **Wasm backend:** *"Uses an async block without JSPI support in WebAssembly"*
- **JS backend:** *"Uses an orphan await (outside of an async block) without targeting WebAssembly"*

Both errors disappear when JSPI is enabled. The `@main` export is synchronous, but with JSPI the Scala.js linker generates the appropriate Wasm module structure for Promise integration, allowing Gears' internals (`WasmSuspension.resume`, `JsAsyncScheduler.execute`, `JsAsync.await`, etc.) to suspend correctly.

### What this means for the spec's §5.1 fallback

The original plan's §5.1 fallback (swap to `scala.concurrent.Future` on the frontend) was **never needed**. It was documented as a risk mitigation in case the Wasm+JSPI path failed. The path does not fail — the three-flag linker config makes it work. The fallback exists in the spec as a design-time hedge and does not need to be exercised.

## Key decisions and workarounds

### `JsAsyncFromSync.apply` / `UnsafeJsAsyncFromSync.apply` called directly

`Async.fromSync` is an `inline` method with a path-dependent type resolution bug on Scala 3.8.4 (and 3.9.0-RC4):

```
Found: (fs$proxy1 : (given_FromSync : Async.FromSync) & $proxy1.FromSync)
Required: ?{ Output: ? }
```

Calling `.apply` on the singleton directly avoids the inline proxy issue. The workaround is verified on 3.8.4 and is the reason this build pins 3.8.4 rather than upgrading to a release candidate.

### No `sbt-crossproject`

Not published for sbt 2. The cross-build is hand-rolled (two projects sharing one source directory). `%%%` is replaced by an explicit `jsDep` helper.

### No `sbt-scalafmt` plugin

scalafmt runs from the devShell, not as an sbt plugin. One fewer plugin, same result.

## Plan 2 readiness

Plan 1 produces everything Plan 2 (Luxmed 2FA spike) needs:
- A working devShell with `curl`/`jq`/`uuidgen`
- A working CI pipeline
- The shared domain types and error ADT
- A fully linked frontend — the walking skeleton is walkable

Plan 2 is a `curl`/`jq` investigation that writes no production code, so no source changes in `lm-bot/` are needed to start it.
