# Stack Modernization — September 2026

**Date:** 2026-09-20
**Trigger:** project-owner request to move to current LTS versions
**Branch:** `chore/modernize-stack`

## What changed

| Dependency | From | To | Notes |
|---|---|---|---|
| Scala | 3.8.4 | **3.9.0** | 3.9.0 is the new LTS line (released 2026-09-03, succeeding 3.3 LTS); the compile flag moves to `-source:3.9` |
| sbt | 2.0.4 | **2.0.9** | `project/build.properties`; the nixpkgs launcher starts it |
| sbt-assembly | 2.4.1 | **2.5.0** | |
| Tapir | 1.13.29 | **1.13.31** | |
| jsoniter-scala | 2.39.1 | **2.41.0** | |
| Flyway | 11.8.2 | **13.7.0** | migration tests pass unchanged |
| PostgreSQL JDBC | 42.7.7 | **42.7.13** | |
| logback-classic | 1.6.0 | **1.6.3** | |
| MUnit | 1.3.4 | **1.3.6** | |
| Zonky embedded PostgreSQL binaries | 14.22.0 (Zonky default) | **18.6.0** | explicit `dependencyOverrides`; tests now exercise the same major as the deployment |
| Compose PostgreSQL image | `postgres:17` | **`postgres:18`** | |
| Docker build image | `sbtscala/scala-sbt:..._3.8.4` (sbt 1 launcher) | **`..._7_2.x`** (JDK 25.0.4 + sbt 2.0.9) | the sbt version that builds the project still comes from `project/build.properties` |
| Node / JDK (devShell) | Node 26, Temurin 25 | unchanged | already at the requested LTS lines |

## Deliberately not bumped

- **Gears 0.3.1** — already the newest published release (`gears_3`, `gears_sjs1_3`).
- **Scala.js 1.22.0** — newest release; since Scala 3 ships the JS backend in the
  compiler, the pairing is verified by `scalajs-scalalib_2.13:3.9.0` being
  published, and the Wasm/JSPI link succeeds.
- **Laminar 17.2.1** — 18.x is milestone-only.
- **Magnum 1.3.1** — newest stable; 2.0.0-M3 remains a milestone. The spec table
  previously claimed 2.0.0-M3 while the code used 1.3.1; the table now states
  the stable version that the code actually uses.
- **Zonky embedded-postgres 2.2.2** — newest release (only its bundled PG
  binaries needed overriding).
- **`flake.lock`** — not refreshed; the devShell inputs are not version-gated by
  this change, and refreshing unstable nixpkgs would invalidate the Cachix
  devShell closure for no requested gain.

## Build adjustments required by the bump

- **Scala 3.9 coverage warning vs `-Werror`.** Scala 3.9's coverage
  instrumentation warns and skips value initializers above 3000 tree nodes
  ([scala/scala3#26953](https://github.com/scala/scala3/issues/26953)). Tapir's
  derived `Schema`s for `MonitorDraft` (4749 nodes) and `MonitorView` (5550
  nodes) exceed it, which failed CI's `coverage; testFull`. A message-scoped
  silence was added — `-Wconf:msg=Skipping coverage instrumentation:s` — so the
  rest of the warning surface stays strict under `-Werror`.

## Verification

All run in the flake devShell on this branch:

| Check | Result |
|---|---|
| `sbt compile` (JVM + JS + Wasm link) | clean, no warnings |
| `sbt testFull` | **469 passed, 0 failed, 0 ignored** (48 suites, JVM + JS) |
| `sbt frontend/fastLinkJS`, `frontend/fullLinkJS` | Wasm output produced (`main.wasm`) |
| `nix develop --command sbt "coverage; testFull; coverageReport"` | passed; scoverage reports generated |
| `nix flake check` | all checks passed |
| `sbt backend/assembly` (via Docker build) | fat jar built |
| Docker build stage | assembly completed on the new base image; the local run stopped at the runtime `FROM` only because rootless Podman cannot resolve short image names (CI's Docker resolves them) |
| Browser smoke test | app loaded, admin login succeeded, accounts and monitors rendered, **no console or page errors** |
| Embedded PG version used by tests | `postgres (PostgreSQL) 18.6` confirmed from the extracted binary |

## Notes and follow-ups

- **Existing deployments with a `postgres:17` data volume** need a dump/restore
  (or `pg_upgrade`) before running the `postgres:18` image; PostgreSQL does not
  accept in-place major version reuse of the data directory.
- The Docker base tag `..._7_2.x` is rolling (as is the runtime
  `eclipse-temurin:25-jre`); the project build version remains pinned by
  `project/build.properties`.
- The browser smoke test used Chromium 153 from nixpkgs (the agent-browser
  bundle lacks system libraries on this Nix host).
