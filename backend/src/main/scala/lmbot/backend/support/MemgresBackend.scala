package lmbot.backend.support

import com.memgres.core.Memgres

/** Embedded database backed by [[com.memgres.core.Memgres]] — an in-memory
  * PostgreSQL-compatible engine that speaks the real PG wire protocol v3. No
  * native binaries, no Docker, no network required.
  *
  * Default credentials: `memgres` / `memgres` (acts as superuser). Default
  * database: `postgres` (set in [[MemgresBackend.start]]).
  */
class MemgresBackend private (memgres: Memgres) extends EmbeddedDb:
  val jdbcUrl: String = memgres.getJdbcUrl
  val username: String = "memgres"
  val password: String = "memgres"
  def close(): Unit = memgres.close()

object MemgresBackend:

  /** Start a new memgres instance on the given port (0 = OS-assigned). The
    * default database is set to `postgres` for compatibility with the existing
    * bootstrap and test infrastructure.
    */
  def start(port: Int): MemgresBackend =
    val m = Memgres
      .builder()
      .port(port)
      .defaultDatabaseName("postgres")
      .build()
      .start()
    new MemgresBackend(m)
