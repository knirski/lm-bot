# lm-bot

Self-hosted monitoring and booking for Luxmed appointments.

See [the design document](docs/superpowers/specs/2026-07-27-lm-bot-prd-design.md)
and [the implementation roadmap](docs/superpowers/plans/2026-07-27-lm-bot-roadmap.md).

## Status

Plan 1 of 7 complete: foundation and authentication. No Luxmed integration yet.

## Requirements

- **Nix with flakes**, and ideally **direnv** (plus `nix-direnv` for caching).
  The flake pins everything else: Temurin 21, the sbt launcher, Node 26,
  Metals, scalafmt, `psql`.
- **A container runtime on the host** — rootless Podman or Docker. A devShell
  cannot provide one. Testcontainers needs it for the backend tests; the
  devShell wires it up for Podman automatically.
- **A JSPI-capable browser** (recent Chrome or Firefox) — the frontend compiles
  to WebAssembly via Scala.js + JSPI.

Node's version is not a preference: Node 24 and 25 contain a V8 bug that
stack-overflows in the nested async contexts Gears relies on. The flake pins
Node 26 so this cannot drift.

## Development

```bash
direnv allow              # once; or `nix develop`

sbt testFull              # everything; needs a container runtime
sbt backend/testFull      # backend only
sbt frontend/testFull     # frontend, incl. the Gears runtime suites
```

### Local server (hot reload)

**One terminal, one command** — starts everything, watches all sources (frontend,
backend, shared), and auto-restarts the backend on any change.

```bash
docker compose up -d postgres   # start the database (one-time or keep running)
sbt startDev                    # link frontend → run backend → watch
```

`startDev` runs with sensible env defaults — no environment file needed:

| Variable | Dev default |
|---|---|
| `COOKIE_SECURE` | `false` (plain-HTTP safe) |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `admin` / `admin` |
| `DATABASE_URL` / `USER` / `PASSWORD` | `localhost:5432/lmbot` / `lmbot` / `lmbot` |
| `HTTP_HOST` / `HTTP_PORT` | `127.0.0.1` / `8080` |

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

sbt frontend/fullLinkJS   # link the frontend to Wasm (production-style)

The build runs on **sbt 2** (declared in `project/build.properties`); the sbt
binary from the flake is only a launcher. Build output is centralised under
`target/out/`.

Use `testFull`, not `test`. In sbt 2, bare `test` is `testQuick`: it runs only
what changed, judged by content hashing against a global cache in `~/.cache/sbt/v2`
that survives `clean` and deleting `target/`. Re-running it on an unchanged tree
prints `Passed: Total 0` and `[success]` — correct, but easy to misread as "the
suite passed". `testFull` runs the full suite unconditionally.

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
