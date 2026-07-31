package lmbot.backend.db

import munit.FunSuite

class DbCodecsTest extends FunSuite:

  test("array text parsing preserves escaped backslashes"):
    assertEquals(
      parseArrayText("{\"C:\\\\Temp\"}"),
      List("C:\\Temp")
    )

  test("facility names containing commas and quotes round-trip"):
    assertEquals(
      parseArrayText(
        "{\"Warszawa, Puławska 455\",\"Kraków \\\"Centrum\\\"\"}"
      ),
      List("Warszawa, Puławska 455", "Kraków \"Centrum\"")
    )

  test("an empty-string element round-trips"):
    assertEquals(parseArrayText("{\"\"}"), List(""))

  test("an empty array decodes to an empty list"):
    assertEquals(parseArrayText("{}"), Nil)
