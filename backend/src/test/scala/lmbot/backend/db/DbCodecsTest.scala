package lmbot.backend.db

import munit.FunSuite

class DbCodecsTest extends FunSuite:

  test("array text parsing preserves escaped backslashes"):
    assertEquals(
      parseArrayText("{\"C:\\\\Temp\"}"),
      List("C:\\Temp")
    )
