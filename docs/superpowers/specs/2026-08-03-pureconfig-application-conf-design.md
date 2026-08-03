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
- Keep the supported environment variable names and meanings documented in the
  README: `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`,
  `LIVE_LUXMED_API`, `EMBEDDED_PG`, `LMBOT_MASTER_KEY`, `ADMIN_USERNAME`, and
  `ADMIN_PASSWORD`.
- Keep `httpHost`, `httpPort`, `cookieSecure`, `sessionTtl`, and
  `luxmedAppVersion` in the selected HOCON resource only; they are not
  environment-variable overrides.
- Environment values override values from the selected HOCON resource.
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
sessionTtl = 7 days
liveLuxmedApi = false
embeddedPg = false
luxmedAppVersion = "4.44.0"
```

The selected resource contains optional HOCON substitutions such as
`dbUrl = ${?DATABASE_URL}`. The loader supplies a filtered environment config
as the higher-precedence layer, resolves those substitutions, and then lets
PureConfig derive the typed model. Consequently the precedence is:

```text
environment override > selected HOCON resource default > missing required value
```

The single `backend` launcher selects a resource explicitly: production defaults
to `application.conf`, while `startDev` injects
`LMBOT_CONFIG_RESOURCE=application-dev.conf`. The loader accepts the resource
name as an explicit parameter, so selection is testable and never depends on
classpath ordering. The development resource, not a second hand-written
defaults table, owns local defaults.

The environment source is filtered to the documented operator variables rather
than importing every process variable. This keeps the public variable names
stable, avoids accidental configuration from unrelated process variables, and
lets tests inject exactly the variables they intend to exercise. Fields that do
not need deployment-time overrides remain ordinary resource values with no
substitution.

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

Internally, the HOCON `sessionTtl` field is loaded as a `FiniteDuration` and
defaults to `7 days`. The resource value is validated with the existing
minimum-one-day rule.

`FiniteDuration` is an internal session/auth implementation detail. Resource
authors configure `sessionTtl` with HOCON duration syntax; Java APIs that need
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

- Secret values are supplied as literal substitution values and are never
  reparsed as HOCON, so substitution-looking secret text remains literal.
- `Secret` and the existing `Config` rendering tests continue to prevent
  database, admin, and master-key values from appearing in `toString` output.
- The environment source is an allow-list. New configuration fields need a
  deliberate `${?ENV_VAR}` substitution before they become operator overrides.
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
2. Each supported environment variable overrides its corresponding resource
   default, while resource-only fields ignore unrelated environment values.
3. Missing required values produce field-specific failures.
4. Invalid port, boolean, TTL, app version, and master-key values fail through
   PureConfig with useful paths.
5. Empty `LIVE_LUXMED_API` retains its current rejection behavior.
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
