package lmbot.backend.support

/** Abstraction over embedded PostgreSQL-compatible databases.
  *
  * Two built-in implementations exist:
  *   - [[MemgresBackend]] (default) — in-memory PG-compatible engine, no native
  *     binaries, no Docker, millisecond startup.
  *   - [[ZonkyBackend]] — real PostgreSQL downloaded and started as a native
  *     binary via zonky embedded-postgres. Useful when 100% PG fidelity is
  *     required during debugging.
  *
  * Switch by setting `EMBEDDED_DB=zonky` (default: `memgres`).
  */
trait EmbeddedDb extends AutoCloseable:

  /** JDBC URL for the default database (superuser access). */
  def jdbcUrl: String

  /** Username with superuser-like privileges on the embedded database. */
  def username: String

  /** Password for the superuser account. */
  def password: String
