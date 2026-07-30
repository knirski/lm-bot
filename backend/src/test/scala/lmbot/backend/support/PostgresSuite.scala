package lmbot.backend.support

import com.augustnagro.magnum.{Transactor, sql, transact}
import com.zaxxer.hikari.HikariDataSource
import lmbot.backend.db.Database

/** One embedded PostgreSQL-compatible database per suite — each gets its own
  * in-memory PG instance on a random port. No shared state, no env vars, no
  * devShell dependency. Flyway migrations run once per suite, tables truncated
  * between tests.
  *
  * The default backend is [[MemgresBackend]] (in-memory, zero deps). Set the
  * environment variable `EMBEDDED_DB=zonky` to use real PostgreSQL via zonky
  * embedded-postgres instead.
  */
abstract class PostgresSuite extends munit.FunSuite:

  private var pg: EmbeddedDb = scala.compiletime.uninitialized
  private var ds: HikariDataSource = scala.compiletime.uninitialized
  protected var xa: Transactor = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    pg = EmbeddedPg.start(port = 0) // OS-assigned port
    ds = Database.dataSource(pg.jdbcUrl, pg.username, pg.password)
    Database.migrate(ds)
    xa = Database.transactor(ds)

  override def afterAll(): Unit =
    if ds != null then ds.close()
    if pg != null then pg.close()

  override def beforeEach(context: BeforeEach): Unit =
    transact(xa):
      sql"truncate table monitors, luxmed_accounts, sessions, users restart identity cascade".update
        .run()
    ()
