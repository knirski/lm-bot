# lm-bot

Self-hosted monitoring and booking for Luxmed appointments.

See [the design document](docs/superpowers/specs/2026-07-27-lm-bot-prd-design.md)
and [the implementation roadmap](docs/superpowers/plans/2026-07-27-lm-bot-roadmap.md).

## Status

Plan 5 of 7 complete. You can sign in, link a Luxmed account (its credentials,
device identity, and session are encrypted at rest), and create, edit, pause,
resume, and delete appointment monitors — through the browser or the HTTP API.
Deleting an account deletes its monitors with it, after an explicit
confirmation.

**Monitors now run.** The engine checks each active monitor on its own jittered
interval, queues behind the account's rate limiter, records what it finds in an
append-only event log, and notifies the owner over Telegram. A slot is notified
at most once per monitor; a user without a linked chat still gets events in the
UI, with a warning that no notifications will be delivered. Auth failures pause
the account's monitors with the reason, version rejections notify the admin, and
a failed monitor can be resumed from the UI. Nothing books yet: that is Plan 6
(auto-booking). See
[the roadmap](docs/superpowers/plans/2026-07-27-lm-bot-roadmap.md) for the
plan-by-plan breakdown.

## Requirements

- **Nix with flakes**, and ideally **direnv** (plus `nix-direnv` for caching).
  The flake pins everything else: Temurin 25, the sbt launcher, Node 26,
  Metals, scalafmt.
- **A JSPI-capable browser** (recent Chrome or Firefox) — the frontend compiles
  to WebAssembly via Scala.js + JSPI.
- **PostgreSQL on x86_64 Linux.** The dev server and tests use Zonky's
  embedded-postgres, which starts a real PostgreSQL instance automatically.
  Zonky's required binaries are not currently available for aarch64/Apple
  silicon, so backend development and tests are currently x86_64-only. The
  paused ARM CI job can be revisited with a Testcontainers backend.

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

The embedded database starts automatically. `startDev` runs the single backend
module and selects its safe local HOCON resource, `application-dev.conf`, so
nothing needs to be set to get a working local server:

```bash
direnv allow              # one-time (or `nix develop`)
sbt startDev              # starts embedded PG on 15432, links frontend, runs backend
```

Override any variable from the shell — the forked JVM inherits it:

```bash
ADMIN_PASSWORD=hunter2 sbt startDev
```

`startDev` sets `LMBOT_CONFIG_RESOURCE=application-dev.conf` for the forked
backend JVM. Its resource sets `LIVE_LUXMED_API=false` and uses deterministic
local Luxmed data. Environment variables override values from the selected
resource, so set `LIVE_LUXMED_API=true` to opt into the real Luxmed API:

```bash
LIVE_LUXMED_API=true sbt startDev
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

Defaults come from the selected HOCON resource, then recognized environment
variables override them. The backend uses `application.conf` by default;
`startDev` sets `LMBOT_CONFIG_RESOURCE=application-dev.conf` to select the
local defaults below. Production therefore uses `application.conf` and must
set every variable marked **required**, including `LIVE_LUXMED_API=true`.
The selected resource contains `${?VARIABLE}` substitutions for the supported
operator variables; settings without a substitution remain resource-only.

| Variable | Required | Default | Dev default (`startDev`) | Meaning |
|---|---|---|---|---|
| `DATABASE_URL` | yes | — | `jdbc:postgresql://localhost:15432/lmbot` | JDBC connection string |
| `DATABASE_USER` | yes | — | `lmbot` | database user |
| `DATABASE_PASSWORD` | yes | — | `lmbot` | database password |
| `LIVE_LUXMED_API` | yes | `false` | `false` | opt into the real Luxmed API; `application-dev.conf` defaults to `false`, while production requires `true` |
| `EMBEDDED_PG` | no | `false` | `true` | controls Zonky embedded PostgreSQL; it defaults to `false` in production, which normally uses the configured external database, while `true` starts Zonky (as `startDev` does on port `15432`) |
| `LMBOT_MASTER_KEY` | yes | — | fixed dev-only key (never use in production) | standard Base64-encoded 32-byte AES key for encrypting Luxmed account credentials and sessions at rest; run `openssl rand -base64 32` to generate |
| `ADMIN_USERNAME` | no | — (bootstrap only) | `admin` | read **only** when the `users` table is empty |
| `ADMIN_PASSWORD` | no | — (bootstrap only) | `admin` | as above |
| `TELEGRAM_BOT_TOKEN` | no | — | — | Bot API token; set together with `TELEGRAM_BOT_USERNAME` to enable notifications |
| `TELEGRAM_BOT_USERNAME` | no | — | — | bot username (without `@`) used to build the `t.me` deep link |
| `TELEGRAM_API_BASE` | no | `https://api.telegram.org` | same | override the Bot API base for development or tests |

Telegram is optional: without a token the engine still records events, and the
UI says notifications are unavailable. Set the token and username together.

`LIVE_LUXMED_API` accepts `true` or `false`. `EMBEDDED_PG` accepts `true`,
`false`, `1`, or `0`.

These settings are resource-only and are not overridden through environment
variables:

| Setting | Production | Dev default (`startDev`) | Meaning |
|---|---|---|---|
| `httpHost` | `0.0.0.0` | `127.0.0.1` | bind address |
| `httpPort` | `8080` | `8080` | bind port |
| `cookieSecure` | `true` | `false` | whether browser cookies require HTTPS |
| `sessionTtl` | `7 days` | `7 days` | session lifetime; must be at least one day |
| `luxmedAppVersion` | `5.8.0` | `5.8.0` | Luxmed mobile app version reported to their API |

## Deployment

Run behind your own HTTPS reverse proxy; lm-bot does not terminate TLS.

```bash
LIVE_LUXMED_API=true POSTGRES_PASSWORD=... LMBOT_MASTER_KEY=$(openssl rand -base64 32) \
  ADMIN_USERNAME=... ADMIN_PASSWORD=... docker compose up -d
```

### Building the image from source

The Dockerfile is a packaging step: it copies a fat jar that already contains
the production (full-link) Wasm frontend. Build the jar first, then the image:

```bash
sbt stageDockerJar    # writes ./lm-bot.jar in the repository root
docker build -t lm-bot .
```

`docker compose up -d --build` works the same way — run `sbt stageDockerJar`
first so `./lm-bot.jar` exists. The jar is ignored by git.
