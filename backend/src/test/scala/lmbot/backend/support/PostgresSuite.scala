package lmbot.backend.support

import com.augustnagro.magnum.{Transactor, sql, transact}
import com.zaxxer.hikari.HikariDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import lmbot.backend.db.Database

/** One embedded PostgreSQL instance per suite — each gets its own real PG on a
  * random port. No shared state, no env vars, no devShell dependency. Flyway
  * migrations run once per suite, tables truncated between tests.
  */
abstract class PostgresSuite extends munit.FunSuite:

  private var pg: EmbeddedPostgres = scala.compiletime.uninitialized
  private var ds: HikariDataSource = scala.compiletime.uninitialized
  protected var xa: Transactor = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    pg = EmbeddedPg.start(
      EmbeddedPostgres.builder().setPort(0) // OS-assigned port
    )
    val url = pg.getJdbcUrl("postgres", "postgres")
    ds = Database.dataSource(url, "postgres", "postgres")
    Database.migrate(ds)
    xa = Database.transactor(ds)

  override def afterAll(): Unit =
    if ds != null then ds.close()
    if pg != null then pg.close()

  override def beforeEach(context: BeforeEach): Unit =
    transact(xa):
      sql"truncate table sessions, users restart identity cascade".update.run()
    ()
