# Zonky-only embedded database report

Date: 2026-08-02

The active project configuration now uses Zonky embedded-postgres exclusively.
The memgres dependency, backend adapter, selector environment variable, and
compatibility-only test skip were removed. The concurrent session replacement
test now runs normally. The memgres-specific array parser/tests and timestamp
workaround were also removed because they were unnecessary with real
PostgreSQL. A PostgreSQL-backed monitor test retains coverage for escaped,
quoted, comma-containing, and empty array values.

README and agent guidance were updated. Historical reports were left unchanged;
the PRD has an appendix explaining that memgres behavior diverged too far from
real PostgreSQL for persistence semantics.

Testcontainers remains a possible future backend if Apple-silicon compatibility
becomes a priority. It would require a deliberate Docker-based test lifecycle,
not a drop-in dependency swap. The aarch64 CI job was removed for now because
Zonky does not provide the required ARM binaries.

Verification:

- `sbt backend/testFull`: 274 passed, 0 failed, 0 errors.
- `sbt testFull`: exit 0, no failures or errors.
- `nix flake check`: all checks passed.
- Scala formatting checks: passed.
- Active-file sweep for memgres/`EMBEDDED_DB`: no matches.
