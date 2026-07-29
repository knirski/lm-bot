package lmbot.backend.support

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

/** Embedded database backed by zonky embedded-postgres — a real PostgreSQL
  * binary that is downloaded and started as a subprocess.
  *
  * Default credentials: `postgres` / `postgres` (superuser). Default database:
  * `postgres`.
  */
class ZonkyBackend private (pg: EmbeddedPostgres) extends EmbeddedDb:
  val jdbcUrl: String = pg.getJdbcUrl("postgres", "postgres")
  val username: String = "postgres"
  val password: String = "postgres"
  def close(): Unit = pg.close()

object ZonkyBackend:

  /** Start a new zonky embedded-postgres instance on the given port (0 =
    * OS-assigned).
    */
  def start(port: Int): ZonkyBackend =
    val builder = EmbeddedPostgres.builder().setPort(port)
    val pg = try builder.start()
    catch
      case e: IllegalStateException
          if e.getMessage != null && e.getMessage.contains("initdb") =>
        EmbeddedPg.patchAndRetry(builder)
    new ZonkyBackend(pg)
