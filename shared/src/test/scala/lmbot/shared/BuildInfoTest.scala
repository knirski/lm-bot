package lmbot.shared

class BuildInfoTest extends munit.FunSuite:
  test("build info carries the project name"):
    assertEquals(BuildInfo.name, "lm-bot")
