package lmbot.backend.support

import java.sql.{Connection, DriverManager, SQLException}

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

/** Factory for embedded PostgreSQL-compatible databases.
  *
  * The default backend is [[MemgresBackend]] — an in-memory engine with no
  * native binary, no Docker, millisecond startup. Set `EMBEDDED_DB=zonky` to
  * fall back to the real PostgreSQL binary (zonky embedded-postgres).
  *
  * Both backends are bootstrapped identically when `startForDev` is used: a
  * `lmbot` role and database are created so the application can connect with
  * its default credentials.
  */
object EmbeddedPg:

  private enum Backend:
    case Memgres, Zonky

  private def resolveBackend: Backend =
    sys.env.get("EMBEDDED_DB") match
      case Some("zonky") => Backend.Zonky
      case _             => Backend.Memgres

  /** Start the embedded database (dev mode). Bootstraps the `lmbot` role and
    * `lmbot` database so callers can connect with the application defaults
    * (`lmbot` / `lmbot` on database `lmbot`).
    */
  def startForDev(port: Int): EmbeddedDb =
    val db = start(port)
    bootstrap(db)
    db

  /** Start the embedded database without bootstrapping. The returned database
    * has only the default superuser and default database.
    */
  def start(port: Int): EmbeddedDb =
    resolveBackend match
      case Backend.Memgres => MemgresBackend.start(port)
      case Backend.Zonky   => ZonkyBackend.start(port)

  // ---------------------------------------------------------------------------
  // Bootstrap – idempotent role/database creation
  // ---------------------------------------------------------------------------

  private def bootstrap(db: EmbeddedDb): Unit =
    bootstrap(getSuperConnection(db))

  private def bootstrap(conn: Connection): Unit =
    try
      try
        conn
          .createStatement()
          .execute(
            "CREATE ROLE lmbot WITH LOGIN PASSWORD 'lmbot'"
          )
      catch
        case e: SQLException if e.getSQLState == "42710" =>
          () // duplicate object
      try conn.createStatement().execute("CREATE DATABASE lmbot OWNER lmbot")
      catch
        case e: SQLException if e.getSQLState == "42P04" =>
          () // duplicate database
    finally conn.close()

  private def getSuperConnection(db: EmbeddedDb): Connection =
    DriverManager.getConnection(db.jdbcUrl, db.username, db.password)

  // ---------------------------------------------------------------------------
  // Zonky-specific: patchelf support for NixOS
  // ---------------------------------------------------------------------------

  /** Retry after fixing the dynamic linker on NixOS (zonky only). */
  private[support] def patchAndRetry(
      builder: EmbeddedPostgres.Builder
  ): EmbeddedPostgres =
    import scala.sys.process.*

    val patchelf = try
      Seq("/bin/sh", "-c", "command -v patchelf 2>/dev/null").!!.trim
    catch case _: Exception => ""

    if patchelf.nonEmpty then
      applyPatchelf(patchelf)
      builder.start()
    else
      throw new RuntimeException(
        "initdb failed and patchelf is not available — enable nix-ld or install patchelf"
      )

  private def applyPatchelf(patchelf: String): Unit =
    import scala.sys.process.*

    try
      val ld = Seq(
        "/bin/sh",
        "-c",
        "ls /nix/store/*-glibc-*/lib/ld-linux-x86-64.so.2 2>/dev/null | head -1"
      ).!!.trim
      val dir = Seq(
        "/bin/sh",
        "-c",
        "ls -d /tmp/embedded-pg/PG-*/bin 2>/dev/null | head -1"
      ).!!.trim
      if ld.isEmpty || dir.isEmpty then return
      Seq(
        "/bin/sh",
        "-c",
        s"""for f in $dir/*; do [ -x "$$f" ] && "$patchelf" --set-interpreter "$ld" "$$f" 2>/dev/null; done"""
      ).!!
    catch case _: Exception => ()
