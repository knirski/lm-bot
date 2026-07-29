package lmbot.backend.support

import java.sql.SQLException

class EmbeddedPgTest extends munit.FunSuite:

  test("role bootstrap ignores only duplicate-object SQLSTATE"):
    assert(
      BootstrapObject.Role.isAlreadyExists(
        SQLException("role exists", "42710")
      )
    )
    assert(
      !BootstrapObject.Role.isAlreadyExists(
        SQLException("permission denied", "42501")
      )
    )

  test("database bootstrap ignores only duplicate-database SQLSTATE"):
    assert(
      BootstrapObject.Database.isAlreadyExists(
        SQLException("database exists", "42P04")
      )
    )
    assert(
      !BootstrapObject.Database.isAlreadyExists(
        SQLException("connection failure", "08006")
      )
    )

  test("duplicate SQLSTATEs are not interchangeable"):
    assert(
      !BootstrapObject.Role.isAlreadyExists(
        SQLException("database exists", "42P04")
      )
    )
    assert(
      !BootstrapObject.Database.isAlreadyExists(
        SQLException("role exists", "42710")
      )
    )
