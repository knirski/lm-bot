# lm-bot

Self-hosted monitoring and booking for Luxmed appointments.

See [the design document](docs/superpowers/specs/2026-07-27-lm-bot-prd-design.md)
and [the implementation roadmap](docs/superpowers/plans/2026-07-27-lm-bot-roadmap.md).

## Status

Plan 3 of 7 complete (foundation, auth, and a Luxmed API client). Plan 4
(Luxmed accounts & monitor CRUD) is **in progress**: encrypted account
linking, session persistence, and the monitor schema are implemented and
tested, but there are no HTTP routes or UI for any of it yet — the running
app cannot link an account or manage a monitor. See
[the roadmap](docs/superpowers/plans/2026-07-27-lm-bot-roadmap.md) for the
task-by-task breakdown.

## Requirements

- **Nix with flakes**, and ideally **direnv** (plus `nix-direnv` for caching).
  The flake pins everything else: Temurin 25, the sbt launcher, Node 26,
  Metals, scalafmt.
- **A JSPI-capable browser** (recent Chrome or Firefox) — the frontend compiles
  to WebAssembly via Scala.js + JSPI.
- **No external PostgreSQL needed.** The dev server and tests use an embedded
  PostgreSQL-compatible database — [memgres](https://github.com/lhgravendeel/memgres)
  by default (in-memory, no native binaries, millisecond startup), or zonky
  embedded-postgres when you set `EMBEDDED_DB=zonky`.

Node's version is not a preference: Node 24 and 25 contain a V8 bug that
stack-overflows in the nested async contexts Gears relies on. The flake pins
Node 26 so this cannot drift.

## Development

### Setup

```bash
direnv allow              # once; or `nix develop`

sbt testFull              # everything (embedded PG per test suite)
sbt backend/testFull      # backend only
sbt frontend/testFull     # frontend, incl. the Gears runtime suites
```

Use `testFull`, not `test`. In sbt 2, bare `test` is `testQuick`: it runs only
what changed, judged by content hashing against a global cache in `~/.cache/sbt/v2`
that survives `clean` and deleting `target/`. Re-running it on an unchanged tree
prints `Passed: Total 0` and `[success]` — correct, but easy to misread as "the
suite passed". `testFull` runs the full suite unconditionally.

### Local server (hot reload)

The embedded database starts automatically. `startDev` supplies safe local
defaults for every variable in [Configuration](#configuration) below — see
that table for exactly what each one is — so nothing needs to be set to get a
working local server:

```bash
direnv allow              # one-time (or `nix develop`)
sbt startDev              # starts embedded PG on 15432, links frontend, runs backend
```

Override any variable from the shell — the forked JVM inherits it:

```bash
ADMIN_PASSWORD=hunter2 sbt startDev
```

### Full hot reload (two terminals)

If you want the frontend to re-link independently without a backend restart, run
the watches separately:

```bash
# Terminal 1: frontend
sbt ~frontend/fastLinkJS

# Terminal 2: backend
sbt ~backend/run
```

Changes to any source file now trigger the relevant re-link or restart.

### Other commands

```bash
sbt frontend/fullLinkJS   # link the frontend to Wasm (production-style)
```

The build runs on **sbt 2** (declared in `project/build.properties`); the sbt
binary from the flake is only a launcher. Build output is centralised under
`target/out/`.

## Configuration

All variables are read from the environment only. `startDev` (above) supplies
the "Dev default" column automatically; production must set every variable
marked **required**, and may rely on the plain "Default" column for the rest.

| Variable | Required | Default | Dev default (`startDev`) | Meaning |
|---|---|---|---|---|
| `DATABASE_URL` | yes | — | `jdbc:postgresql://localhost:15432/lmbot` | JDBC connection string |
| `DATABASE_USER` | yes | — | `lmbot` | database user |
| `DATABASE_PASSWORD` | yes | — | `lmbot` | database password |
| `HTTP_HOST` | no | `0.0.0.0` | `127.0.0.1` | bind address |
| `HTTP_PORT` | no | `8080` | *(same)* | bind port |
| `COOKIE_SECURE` | no | `true` | `false` | set `false` only for plain-HTTP local dev |
| `SESSION_TTL_DAYS` | no | `7` | *(same)* | session lifetime in days; must be at least `1` |
| `LMBOT_MASTER_KEY` | yes | — | fixed dev-only key (never use in production) | standard Base64-encoded 32-byte AES key for encrypting Luxmed account credentials and sessions at rest; run `openssl rand -base64 32` to generate |
| `LUXMED_APP_VERSION` | no | `4.44.0` | *(same)* | Luxmed mobile app version reported to their API; must be at or above the measured refresh-compatible floor |
| `ADMIN_USERNAME` | no | — (bootstrap only) | `admin` | read **only** when the `users` table is empty |
| `ADMIN_PASSWORD` | no | — (bootstrap only) | `admin` | as above |

## Deployment

Run behind your own HTTPS reverse proxy; lm-bot does not terminate TLS.

```bash
POSTGRES_PASSWORD=... LMBOT_MASTER_KEY=$(openssl rand -base64 32) \
  ADMIN_USERNAME=... ADMIN_PASSWORD=... docker compose up -d
```
