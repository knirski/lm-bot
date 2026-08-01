package lmbot.backend.support

/** Abstraction over the embedded PostgreSQL database.
  *
  * The implementation is [[ZonkyBackend]], which downloads and starts a real
  * PostgreSQL binary via zonky embedded-postgres.
  */
trait EmbeddedDb extends AutoCloseable:

  /** JDBC URL for the default database (superuser access). */
  def jdbcUrl: String

  /** Username with superuser-like privileges on the embedded database. */
  def username: String

  /** Password for the superuser account. */
  def password: String
