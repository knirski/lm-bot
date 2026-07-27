package lmbot.backend.support

import com.augustnagro.magnum.{Transactor, transact, sql}
import com.zaxxer.hikari.HikariDataSource
import lmbot.backend.db.Database
import org.testcontainers.containers.PostgreSQLContainer

/** One container per suite, schema migrated once, tables truncated between
  * tests so each test starts from a known empty database.
  */
abstract class PostgresSuite extends munit.FunSuite:

  private var container: PostgreSQLContainer[?] = scala.compiletime.uninitialized
  private var ds: HikariDataSource              = scala.compiletime.uninitialized
  protected var xa: Transactor                  = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    container = new PostgreSQLContainer("postgres:17")
    container.start()
    ds = Database.dataSource(container.getJdbcUrl, container.getUsername, container.getPassword)
    Database.migrate(ds)
    xa = Database.transactor(ds)

  override def afterAll(): Unit =
    if ds != null then ds.close()
    if container != null then container.stop()

  override def beforeEach(context: BeforeEach): Unit =
    transact(xa):
      sql"truncate table sessions, users restart identity cascade".update.run()
    ()
