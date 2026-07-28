// Essential: there is no other way to build Scala.js.
addSbtPlugin("org.scala-js" % "sbt-scalajs"  % "1.22.0")
// Earns its place: produces the single fat jar the runtime image copies,
// instead of shipping a classpath plus a coursier cache (Task 12).
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.4.1")

// Formatting enforcement as an sbt plugin so it works regardless of whether
// the nix devShell is active. Provides `scalafmtAll`, `scalafmtCheckAll`, etc.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")

// Code coverage instrumentation and reporting.
// Provides `coverage`, `coverageReport`, `coverageAggregate`.
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")
