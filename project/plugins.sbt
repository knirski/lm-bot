// Essential: there is no other way to build Scala.js.
addSbtPlugin("org.scala-js" % "sbt-scalajs"  % "1.22.0")
// Earns its place: produces the single fat jar the runtime image copies,
// instead of shipping a classpath plus a coursier cache (Task 12).
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.4.1")
