package lmbot.backend.support

import com.augustnagro.magnum.Transactor
import com.zaxxer.hikari.HikariDataSource
import lmbot.backend.db.Database

/** One embedded PostgreSQL-compatible database per test case — each gets its
  * own instance on a random port. No shared test state or devShell dependency.
  * Flyway migrations run for each isolated test database.
  *
  * Tests use Memgres by default for fast isolated databases. Set
  * `EMBEDDED_DB=zonky` to run compatibility checks against real PostgreSQL.
  */
abstract class PostgresSuite extends munit.FunSuite:

  final private case class TestDb(
      pg: EmbeddedDb,
      ds: HikariDataSource,
      transactor: Transactor
  )

  private val currentDb = new InheritableThreadLocal[TestDb]

  protected def xa: Transactor =
    Option(currentDb.get())
      .map(_.transactor)
      .getOrElse(throw IllegalStateException("database is not initialized"))

  override def beforeEach(context: BeforeEach): Unit =
    val pg = EmbeddedPg.startForTest(port = 0) // OS-assigned port
    val ds = Database.dataSource(pg.jdbcUrl, pg.username, pg.password)
    Database.migrate(ds)
    currentDb.set(TestDb(pg, ds, Database.transactor(ds)))
    ()

  override def afterEach(context: AfterEach): Unit =
    Option(currentDb.get()).foreach { db =>
      db.ds.close()
      db.pg.close()
      currentDb.remove()
    }
