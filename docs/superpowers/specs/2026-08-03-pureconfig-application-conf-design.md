# PureConfig application.conf configuration design

## Goal

Make PureConfig the single configuration boundary for the backend while adding
an `application.conf` defaults layer and preserving the current environment
variable contract. Configuration must be readable from a deterministic
`Map[String, String]` in tests and from the process environment in launchers.

## Scope and compatibility

- Keep one SBT backend module. Remove the `backend-dev` project.
- Add `backend/src/main/resources/application.conf` for production defaults.
- Add `backend/src/main/resources/application-dev.conf` for local defaults,
  including the embedded PostgreSQL URL, local credentials, insecure local
  cookies, the development master key, and mock-Luxmed mode.
- Move the development launcher, mock server, fixtures, and account seeder
  into the backend module's development/runtime package.
- Keep the existing environment variable names and meanings documented in the
  README, including `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`,
  `HTTP_HOST`, `HTTP_PORT`, `COOKIE_SECURE`, `SESSION_TTL_DAYS`,
  `LIVE_LUXMED_API`, `LMBOT_MASTER_KEY`, `LUXMED_APP_VERSION`,
  `ADMIN_USERNAME`, `ADMIN_PASSWORD`, and `EMBEDDED_PG`.
- Environment values override values from `application.conf`.
- Required values remain required. No production secret receives a default.
- Existing development defaults supplied by `startDev` remain unchanged.
- `Config.fromEnv(Map[String, String])` remains available as the pure,
  deterministic boundary used by tests and launchers.

## Configuration model and precedence

The Scala `Config` case class remains the application-facing model. Its fields
use the existing names and domain types. Both resource files use the same
camel-case paths as that model. Production `application.conf` contains only
safe non-secret defaults:

```hocon
httpHost = "0.0.0.0"
httpPort = 8080
cookieSecure = true
sessionTtlDays = 7
liveLuxmedApi = false
embeddedPg = false
luxmedAppVersion = "4.44.0"
```

The loader converts the allow-listed environment variables to those paths,
creates an environment `ConfigSource`, and combines it with
the selected resource source using `withFallback`. Consequently the precedence
is:

```text
environment override > application.conf default > missing required value
```

The single launcher selects a resource explicitly: production defaults to
`application.conf`, while `startDev` supplies `application-dev.conf`. The
loader accepts the resource name as an explicit parameter, so selection is
testable and never depends on classpath ordering. The old `backendDev/run`
environment injection is removed so the development resource, not a second
hand-written defaults table, owns local defaults.

The conversion is explicit rather than a global environment import. This
keeps the public variable names stable, avoids accidental configuration from
unrelated process variables, and lets tests inject exactly the variables they
intend to exercise.

## PureConfig integration

Use `pureconfig-core` as the Scala 3 dependency and PureConfig's native Scala 3
derivation (`pureconfig.generic.derivation.default`) for the configuration
product. `Config` derives `ConfigReader`; do not hand-write a product reader or
read individual fields through separate cursors. The derivation is used only
for the product shape; domain validation remains explicit below.

Provide explicit `ConfigReader`s for domain values PureConfig cannot safely
derive or validate on its own:

- `Secret` reads a string and retains its redacted `toString`.
- `Port` reads an integer and applies the valid TCP-port range.
- `AppVersion` reads a string and applies the measured minimum version.
- `MasterKey` reads standard Base64 and requires exactly 32 decoded bytes.

The HOCON field for `sessionTtl: scala.concurrent.duration.FiniteDuration` is
`sessionTtl` and defaults to `7 days`. The environment adapter translates the
legacy `SESSION_TTL_DAYS` variable to a duration value (for example, `7 days`)
before PureConfig reads it. The loader then applies the existing minimum-one-day
validation and reports the failure against `SESSION_TTL_DAYS` for compatibility.

The session/auth boundary uses `FiniteDuration` end-to-end; Java APIs that need
`java.time.Duration` (for example, the JDK HTTP client timeout) remain explicit
foreign-library adapters and are outside this configuration migration.

Readers return PureConfig failures with the relevant field path. Cross-field
or startup-policy checks that are not value conversions remain at the
application boundary (for example, production's requirement that
`LIVE_LUXMED_API=true`).

PureConfig failures are converted to the existing `Either[List[String],
Config]` API so callers continue to report all configuration errors together.
No `loadOrThrow` call is introduced in the public launcher path.

## Security

- Secret values are parsed as literal environment values and never evaluated
  as HOCON substitutions.
- `Secret` and the existing `Config` rendering tests continue to prevent
  database, admin, and master-key values from appearing in `toString` output.
- The environment mapping is an allow-list. New configuration fields must be
  added deliberately to it.
- Production `application.conf` contains no credentials or encryption keys.
- Development-only credentials and the fixed development master key may appear
  only in `application-dev.conf`, which is never selected by production.
- The production artifact includes development support code, but production
  startup refuses a non-live configuration before opening the database or HTTP
  server.

## Tests

Extend the backend configuration suite to pin these behaviors:

1. `application.conf` defaults load successfully when only required values are
   supplied.
2. Each supported environment variable overrides its corresponding default.
3. Missing required values produce field-specific failures.
4. Invalid port, boolean, TTL, app version, and master-key values fail through
   PureConfig with useful paths.
5. Empty `LIVE_LUXMED_API` and `LUXMED_APP_VERSION` retain their current
   rejection behavior.
6. Secret values containing substitution-looking text remain literal.
7. Config rendering never exposes any secret.
8. Resource selection loads production and development defaults independently.
9. The single launcher selects the mock boundary and development seeder only
   for the development resource.

Run the focused configuration suite first, then `sbt backend/testFull` and
`nix flake check` before declaring the change complete.

## Non-goals

- No change to the HTTP, database, Luxmed, or frontend configuration APIs.
- No new command-line configuration source.
- No migration of unrelated HOCON/logback settings.
- No broad renaming of environment variables.
