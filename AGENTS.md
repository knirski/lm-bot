# Agent instructions for lm-bot

Read this before touching anything. It covers only what you would otherwise get
**wrong** — setup and configuration live in [README.md](README.md), the design
lives in the spec, and neither is repeated here.

## Where authority lives

| Question | Answer lives in |
|---|---|
| What are we building, and why this way? | `docs/superpowers/specs/2026-07-27-lm-bot-prd-design.md` |
| What order, and what is in scope now? | `docs/superpowers/plans/2026-07-27-lm-bot-roadmap.md` |
| Exactly how to build the current piece | the numbered plan in `docs/superpowers/plans/` |
| What Luxmed's API actually does | `docs/superpowers/reports/2026-07-27-luxmed-api-analysis.md` |
| How is the flake / CI / Cachix infra configured? | `flake.nix` + `.github/actions/setup-nix/` + `.github/workflows/ci.yml` |

**The spec is the source of truth.** When you find it wrong — and you will —
amend the spec, commit that, then build against the corrected version. Do not
work around a spec error in code or encode the correction only in a plan; that
leaves the spec actively misleading for the next reader.

## The environment is not optional

Work inside the flake devShell: `direnv allow`, or `nix develop`. It pins
Temurin 25, the sbt launcher, **Node 26**, Metals, scalafmt, and `psql`.

- **Node 26+ is a hard requirement.** V8 in Node 24/25 stack-overflows in the
  nested async contexts Gears uses throughout.
- **The container runtime here is rootless Podman, not Docker.** Testcontainers
  cannot find Podman's socket unaided, so the devShell exports `DOCKER_HOST`,
  `TESTCONTAINERS_RYUK_DISABLED=true`, and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`.
  Outside the shell, every Testcontainers test fails with "Could not find a
  valid Docker environment". Never add a `docker` CLI to the devShell — it would
  shadow the working Podman shim. Because Ryuk is disabled, a hard-killed JVM
  can leave containers behind: `podman ps` after a crash.
- **The Cachix binary cache `knirski-lm-bot` is wired into `nixConfig`**, so
  every `nix develop` and `nix build` automatically pulls pre-built closures
  from `https://knirski-lm-bot.cachix.org` before building locally. CI pushes
  the devShell closure there on every main-branch push.

## Workflow

- One change → one branch → one PR. Stack only for explicit dependencies.
- Branch: `feat/`, `fix/`, `docs/` prefix. Commit: `feat:`, `fix:`, `refactor:`, `chore:`, etc.
- Format before commit: `nix fmt` for Nix files, `sbt scalafmtAll` for Scala.
  The pre-commit hooks installed by `nix develop` (treefmt, deadnix, statix,
  typos, end-of-file-fixer, shellcheck, actionlint) catch the rest. Do not
  commit until `nix flake check` passes.
- PR title must be conventional commit. Squash+merge only. Merge when CI is green and no "changes requested" review is active (approval not required).
- Before merging: verify CI green, all review threads resolved, bot comments addressed, no stale CI.
- When resolving review comments: reply explaining fix, then resolve thread.
- Final response format:
  ```text
  changed-files:
  verification-run:
  skipped-checks:
  branch:
  pr:
  blocker:
  ```

## Commit Convention (semantic-release)

Every commit on `main` triggers a release via semantic-release. PR title must be conventional commit. Use squash+merge.

| Prefix | Bump | Example |
|---|---|---|
| `feat:` | minor | `feat: add search bar` |
| `fix:` | patch | `fix: crash on empty list` |
| `perf:` | patch | `perf: optimize image loading` |
| `feat!:` / `fix!:` | major | `feat!: drop API v1` |

`docs:`, `chore:`, `style:`, `refactor:`, `test:`, `ci:`, `build:` — no release unless a `BREAKING CHANGE:` footer exists.

## Run tests with `testFull`, never `test`

```bash
nix flake check           # pre-commit hooks + Nix formatting
sbt testFull              # everything (96 tests)
sbt backend/testFull      # one module
sbt frontend/fastLinkJS   # link frontend to Wasm
```

In sbt 2, bare `test` **is** `testQuick`. It runs only what changed, judged by
content hashing against a global cache in `~/.cache/sbt/v2` that survives
`clean` **and** deleting `target/`. Re-running it on an unchanged tree prints
`Passed: Total 0` and `[success]`. That is correct behaviour and a trap: never
report a `Total 0` run as evidence that anything works. `touch` also does
nothing, since only content is hashed.

## sbt 2 differences that will bite

- **No `sbt-crossproject`** — it has no sbt 2 build. The JVM/JS cross-build is
  hand-rolled as two projects over one source directory in `build.sbt`.
- **No `%%%`** — use the `jsDep(org, artifact, version)` helper.
- **Output is centralised** under `target/out/{jvm,sjs1}/scala-3.8.4/<project>/`,
  not `<project>/target/`. Never hardcode that layout; locate artifacts.
- `PathFinder.get` requires the empty argument list: `.get()`.

## Scala.js / Wasm / JSPI

`wasmConfig` in `build.sbt` needs **three** settings and all are load-bearing:
`withUseWebAssembly(true)`, `ESVersion.ES2022`, and **`withUseJSPI(true)`**.
The last defaults to `false`, and without it nothing links — with errors that
read like upstream Gears bugs ("Uses an async block without JSPI support",
"orphan await") and stack traces pointing deep into Gears internals. They are
not Gears bugs. Check the flag first.

- `Main` must use **`JsAsyncFromSync`**, not `UnsafeJsAsyncFromSync`. The unsafe
  variant establishes no `js.async` boundary, so the app renders and then hangs
  forever on "Loading…". Both link identically; only a browser catches it.
- **Linking is not evidence the frontend works.** Two of the three frontend bugs
  in this project were invisible to the compiler, the linker, and 92 green
  tests. Load the app in a real browser and drive it.

## API contract rules

The `shared` module is the single definition of the API; server and browser
client are both derived from it. Two rules protect that:

- **Use `setCookieOpt`, never `setCookie`, on browser-facing endpoints.**
  `setCookie` fails to decode when the header is absent, and `Set-Cookie` is a
  forbidden response header for browser JS. With `setCookie`, every *successful*
  login is undecodable on the client: 200 from the server, cookie stored, and
  the UI silently never advances.
- **Pin wire formats in tests; round-tripping is not enough.** A discriminated
  object round-trips perfectly while the Tapir `Schema` advertises a string.
  `Role` shipped exactly that mismatch through 92 passing tests. Assert the
  actual bytes (see `CodecRoundTripTest`).

## Style, from spec §5.7 — these are review gates

1. **Gears is the only async vocabulary.** `scala.concurrent.Future` and JS
   `Promise` are named nowhere outside `frontend/.../bridge/` — not even in a
   private signature. When a foreign Future must be awaited, add the adapter to
   `Bridge` rather than spelling the type at the call site. Verify (quote the
   glob — zsh expands it otherwise):

   ```bash
   grep -rn --include='*.scala' 'scala\.concurrent' \
     shared/src/main backend/src/main frontend/src/main | grep -v '/bridge/'
   ```

   Must print nothing. Test sources may name it where they are testing the
   bridge itself.
2. **Errors are values** (`Either`, union types). Exceptions mean bugs; they
   crash their own fiber, never the supervisor.
3. **Airstream lives in exactly two places:** the store `Var` and view
   projections. An `-->` outside an event handler is a review flag.
4. **Tapir endpoint descriptions and Laminar views carry no control flow.**
   Behaviour lives in services (backend) and `update`/effects (frontend).
5. **No DI framework, no reflection.** Plain classes wired by hand in `Main`.
6. All Luxmed-facing dates and times are **`Europe/Warsaw`**.

## Security invariants

Argon2id for internal passwords; opaque random session tokens stored **hashed**;
cookies `HttpOnly` + `Secure` + `SameSite=Lax`; authorization in the **service
layer**, not the UI; Luxmed credentials, sessions and device identities
AES-256-GCM at rest; TLS terminated by the operator's proxy, not in-app.

Secrets are never logged. Wrap them in `Secret` so an accidental interpolation
cannot leak them, and keep login paths constant-time with respect to whether a
username exists (`AuthService` verifies against a decoy hash for unknown users —
do not "simplify" that away).

## Working with Luxmed

- **Shapes come from [dyrkin/luxmed-bot](https://github.com/dyrkin/luxmed-bot)
  and the analysis report — never from lmassist.** lmassist's DTOs are
  snake_case against paths that contradict its own research doc, because it was
  written against a mock it authored, with hand-invented fixtures. Its *prose*
  research and mock-server *approach* are fine; its shapes are not.
- Reservation-mutating calls need the **XSRF token** flow plus merged cookies.
- Auto-booking is **lock → validate → confirm or release**. Price and referral
  data arrive only in the lock response, and a lock that is neither confirmed
  nor released keeps holding the slot.
- **LuxMed temporarily locks accounts for excessive querying** (reported ~1 day).
  Anything that logs in repeatedly needs an attempt cap and spacing. Treat a
  sudden auth failure on a previously working account as a possible lock, not
  as a wrong password.

## Reporting discipline

This project has been burned repeatedly by confident conclusions built on
unverified intermediate claims. Therefore:

- **Read an upstream issue's body before citing it.** Verifying that an issue
  exists, is open, and has a milestone tells you nothing about whether it
  describes your problem.
- **Do not take another agent's summary as fact**, including your own earlier
  turns. Re-derive from the code or the wire.
- **Stop at decision gates and report** rather than improvising. If a plan says
  a choice belongs to the spec's owner, it does.
- **Never declare work complete with tests excluded, renamed, or skipped**, and
  never report a green build achieved by disabling a required feature. If a
  suite cannot run, the work is not done — say so plainly instead.
