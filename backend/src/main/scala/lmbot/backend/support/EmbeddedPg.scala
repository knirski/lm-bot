package lmbot.backend.support

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import scala.sys.process.*

/** Embedded PostgreSQL starter with support for NixOS via `patchelf`.
  *
  * On most platforms the downloaded PG binaries work out of the box. On NixOS
  * without `nix-ld` they fail with a dynamic-linker error. If `patchelf` is
  * available we fix the interpreter and retry once.
  *
  * The proper NixOS fix is `programs.nix-ld.enable = true` in your
  * configuration.nix — no patching needed after that.
  */
object EmbeddedPg:

  def start(builder: EmbeddedPostgres.Builder): EmbeddedPostgres =
    try builder.start()
    catch
      case e: IllegalStateException
          if e.getMessage != null && e.getMessage.contains("initdb") =>
        patchAndRetry(builder)

  private def patchAndRetry(
      builder: EmbeddedPostgres.Builder
  ): EmbeddedPostgres =
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
