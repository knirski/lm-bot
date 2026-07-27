import org.scalajs.linker.interface.{ESVersion, ModuleKind, StandardConfig}

val scala3 = "3.8.4"

ThisBuild / scalaVersion := scala3
ThisBuild / organization := "dev.knirski"
ThisBuild / version      := "0.1.0-SNAPSHOT"

val Vgears          = "0.3.1"
val Vtapir          = "1.13.29"
val Vsttp           = "3.11.0"
val Vjsoniter       = "2.39.1"
val Vlaminar        = "17.2.1"
val VscalajsDom     = "2.8.1"
val Vmagnum         = "1.3.1"
val Vflyway         = "11.8.2"
val Vpostgres       = "42.7.7"
val Vhikari         = "7.1.0"
val Vargon2         = "2.12"
val Vlogback        = "1.6.0"
val Vmunit          = "1.3.4"
val Vtestcontainers = "1.21.3"

/** Names a Scala.js artifact explicitly, since sbt 2 has no `%%%`. The suffix
  * encodes Scala.js 1.x + Scala 3, both pinned by this build.
  */
def jsDep(org: String, artifact: String, version: String): ModuleID =
  org % s"${artifact}_sjs1_3" % version

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Wunused:all",
    "-Werror",
    "-source:3.8"
  )
)

// The shared module's real sources live in one place; the two platform projects
// below both compile them. This is what sbt-crossproject would have generated,
// written out by hand because it has no sbt 2 build.
lazy val sharedSources    = Def.setting((ThisBuild / baseDirectory).value / "shared" / "src" / "main" / "scala")
lazy val sharedTestSources = Def.setting((ThisBuild / baseDirectory).value / "shared" / "src" / "test" / "scala")

lazy val sharedSettings = commonSettings ++ Seq(
  Compile / unmanagedSourceDirectories += sharedSources.value,
  Test / unmanagedSourceDirectories += sharedTestSources.value
)

lazy val sharedJVM = project
  .in(file("shared/.jvm"))
  .settings(sharedSettings)
  .settings(
    name := "lm-bot-shared",
    libraryDependencies ++= Seq(
      "ch.epfl.lamp"                          %% "gears"                 % Vgears,
      "com.softwaremill.sttp.tapir"           %% "tapir-core"            % Vtapir,
      "com.softwaremill.sttp.tapir"           %% "tapir-jsoniter-scala"  % Vtapir,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core"   % Vjsoniter,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % Vjsoniter,
      "org.scalameta"                         %% "munit"                 % Vmunit % Test
    )
  )

lazy val sharedJS = project
  .in(file("shared/.js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(sharedSettings)
  .settings(
    name := "lm-bot-shared-js",
    libraryDependencies ++= Seq(
      jsDep("ch.epfl.lamp", "gears", Vgears),
      jsDep("com.softwaremill.sttp.tapir", "tapir-core", Vtapir),
      jsDep("com.softwaremill.sttp.tapir", "tapir-jsoniter-scala", Vtapir),
      jsDep("com.github.plokhotnyuk.jsoniter-scala", "jsoniter-scala-core", Vjsoniter),
      jsDep("com.github.plokhotnyuk.jsoniter-scala", "jsoniter-scala-macros", Vjsoniter),
      jsDep("org.scalameta", "munit", Vmunit) % Test
    ),
    scalaJSLinkerConfig ~= wasmConfig
  )

/** Gears on Scala.js needs the WebAssembly backend so JSPI can suspend (spec
  * §5.1). Wasm implies ES modules *and* at least ES2022.
  * `withUseWebAssembly` is the current spelling;
  * `withExperimentalUseWebAssembly` is deprecated as of Scala.js 1.22.0.
  *
  * `withUseJSPI(true)` is critical — it defaults to false and the linker
  * rejects all js.async/js.await usage without it.  The error "Uses an async
  * block without JSPI support in WebAssembly" means exactly this flag.
  */
lazy val wasmConfig: org.scalajs.linker.interface.StandardConfig => org.scalajs.linker.interface.StandardConfig =
  _.withModuleKind(ModuleKind.ESModule)
    .withESFeatures(_.withESVersion(ESVersion.ES2022).withUseWebAssembly(true))
    .withWasmFeatures(_.withUseJSPI(true))

lazy val backend = project
  .in(file("backend"))
  .dependsOn(sharedJVM)
  .settings(commonSettings)
  .settings(
    name := "lm-bot-backend",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-jdkhttp-server" % Vtapir,
      "com.softwaremill.sttp.tapir" %% "tapir-files"          % Vtapir,
      "com.augustnagro"             %% "magnum"               % Vmagnum,
      "org.flywaydb"                 % "flyway-core"          % Vflyway,
      "org.flywaydb"                 % "flyway-database-postgresql" % Vflyway,
      "org.postgresql"               % "postgresql"           % Vpostgres,
      "com.zaxxer"                   % "HikariCP"             % Vhikari,
      "de.mkammerer"                 % "argon2-jvm"           % Vargon2,
      "ch.qos.logback"               % "logback-classic"      % Vlogback,
      "org.scalameta"                %% "munit"                % Vmunit          % Test,
      "org.testcontainers"           % "postgresql"           % Vtestcontainers % Test,
      "com.softwaremill.sttp.client3" %% "core"               % Vsttp           % Test
    ),
    // Virtual threads and Testcontainers both want a real JVM 21+.
    javacOptions ++= Seq("-source", "21", "-target", "21"),
    Compile / mainClass := Some("lmbot.backend.Main"),

    // Package the linked frontend as classpath resources under `web/`, which is
    // where StaticRoutes looks (served at /assets). Without this the backend
    // serves index.html but 404s /assets/main.js, so the page loads blank —
    // linking the frontend is not the same as shipping it.
    Compile / resourceGenerators += Def.task {
      val linkedDir = (frontend / Compile / fullLinkJSOutput).value
      val webDir    = (Compile / resourceManaged).value / "web"
      IO.copyDirectory(linkedDir, webDir, overwrite = true)
      (webDir ** "*").get().filter(_.isFile)
    }.taskValue,


    assembly / mainClass := Some("lmbot.backend.Main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*)      => MergeStrategy.discard
      case PathList("module-info.class") => MergeStrategy.discard
      case x                             => (assembly / assemblyMergeStrategy).value(x)
    }
  )

lazy val frontend = project
  .in(file("frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(sharedJS)
  .settings(commonSettings)
  .settings(
    name := "lm-bot-frontend",
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= wasmConfig,
    Test / scalaJSLinkerConfig ~= wasmConfig,
    libraryDependencies ++= Seq(
      jsDep("ch.epfl.lamp", "gears", Vgears),
      jsDep("com.raquo", "laminar", Vlaminar),
      jsDep("org.scala-js", "scalajs-dom", VscalajsDom),
      jsDep("com.softwaremill.sttp.tapir", "tapir-sttp-client", Vtapir),
      jsDep("com.softwaremill.sttp.client3", "core", Vsttp),
      jsDep("org.scalameta", "munit", Vmunit) % Test
    )
  )

lazy val root = project
  .in(file("."))
  .aggregate(sharedJVM, sharedJS, backend, frontend)
  .settings(name := "lm-bot", publish / skip := true)
