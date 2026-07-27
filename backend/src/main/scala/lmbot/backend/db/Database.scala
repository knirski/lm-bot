package lmbot.backend.db

import com.augustnagro.magnum.Transactor
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.flywaydb.core.Flyway

object Database:

  def dataSource(url: String, user: String, password: String): HikariDataSource =
    val config = new HikariConfig()
    config.setJdbcUrl(url)
    config.setUsername(user)
    config.setPassword(password)
    // Family scale: a small pool is plenty. Blocking JDBC runs on virtual
    // threads, so the pool — not the thread count — is the real limit.
    config.setMaximumPoolSize(10)
    config.setPoolName("lmbot-pool")
    new HikariDataSource(config)

  def migrate(ds: HikariDataSource): Unit =
    Flyway.configure().dataSource(ds).load().migrate()
    ()

  def transactor(ds: HikariDataSource): Transactor = Transactor(ds)
