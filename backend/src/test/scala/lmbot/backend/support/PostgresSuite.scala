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

  /** Skip the current test unless running against real PostgreSQL
    * (`EMBEDDED_DB=zonky`). Memgres does not reliably provide every guarantee a
    * real PostgreSQL does — e.g. atomic row-level locking under concurrent
    * writers — so a test that pins one of those should call this first rather
    * than re-deriving its own `EMBEDDED_DB` check.
    */
  protected def assumeRealPostgres(): Unit =
    assume(
      EmbeddedPg.usingRealPostgres,
      "requires a real PostgreSQL backend: re-run with EMBEDDED_DB=zonky"
    )

  override def beforeEach(context: BeforeEach): Unit =
    val pg = EmbeddedPg.startForTest(port = 0) // OS-assigned port
    val ds =
      try Database.dataSource(pg.jdbcUrl, pg.username, pg.password)
      catch
        case t: Throwable =>
          pg.close()
          throw t
    try
      Database.migrate(ds)
      currentDb.set(TestDb(pg, ds, Database.transactor(ds)))
    catch
      case t: Throwable =>
        try ds.close()
        finally pg.close()
        throw t
    ()

  override def afterEach(context: AfterEach): Unit =
    Option(currentDb.get()).foreach { db =>
      db.ds.close()
      db.pg.close()
      currentDb.remove()
    }
