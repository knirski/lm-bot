import org.scalajs.linker.interface.{ESVersion, ModuleKind, StandardConfig}

val scala3 = "3.9.0"

ThisBuild / scalaVersion := scala3
ThisBuild / organization := "dev.knirski"
ThisBuild / version := "0.1.0-SNAPSHOT"

// --- Development mode ---
// When true (default), the backend resource generator triggers fastLinkJS and
// reads from its output — much faster for iterative work.  Set to false for
// production assembly (Dockerfile does this explicitly).
val useFastLinkForAssets =
  settingKey[Boolean](
    "Use fastLinkJS output for backend assets (default: true)"
  )
ThisBuild / useFastLinkForAssets := true

val Vgears = "0.3.1"
val Vtapir = "1.13.31"
val Vsttp = "3.11.0"
val Vjsoniter = "2.41.0"
val Vlaminar = "17.2.1"
val VscalajsDom = "2.8.1"
val Vmagnum = "1.3.1"
val Vflyway = "13.7.0"
val Vpostgres = "42.7.13"
val Vhikari = "7.1.0"
val Vargon2 = "2.12"
val Vlogback = "1.6.3"
val Vmunit = "1.3.6"
val VembeddedPg = "2.2.2"
// Zonky's embedded-postgres bundles PostgreSQL 14.22.0 binaries by default;
// override to the current stable major so tests and the compose deployment
// (postgres:18) exercise the same PostgreSQL major.
val VembeddedPgBinaries = "18.6.0"
val Vpureconfig = "0.17.10"
val VscalaJavaTimeTzdb = "2.7.0"

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
    // Scala 3.9's coverage instrumentation warns for value initializers above
    // 3000 tree nodes and skips them; Tapir's derived Schemas (MonitorDraft,
    // MonitorView) exceed that, and -Werror would fail the coverage build.
    // Silenced by message so the rest of the warning surface stays strict.
    // See scala/scala3#26953.
    "-Wconf:msg=Skipping coverage instrumentation:s",
    "-source:3.9"
  )
)

// The shared module's real sources live in one place; the two platform projects
// below both compile them. This is what sbt-crossproject would have generated,
// written out by hand because it has no sbt 2 build.
lazy val sharedSources = Def.setting(
  (ThisBuild / baseDirectory).value / "shared" / "src" / "main" / "scala"
)
lazy val sharedTestSources = Def.setting(
  (ThisBuild / baseDirectory).value / "shared" / "src" / "test" / "scala"
)

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
      "ch.epfl.lamp" %% "gears" % Vgears,
      "com.softwaremill.sttp.tapir" %% "tapir-core" % Vtapir,
      "com.softwaremill.sttp.tapir" %% "tapir-jsoniter-scala" % Vtapir,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % Vjsoniter,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % Vjsoniter,
      "org.scalameta" %% "munit" % Vmunit % Test
    )
  )

lazy val sharedJS = project
  .in(file("shared/.js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(sharedSettings)
  .settings(
    name := "lm-bot-shared-js",
    // sbt-scoverage instrumentation breaks Scala.js test bridge internals
    // (TrieMap MainNode).  JS projects run bare under coverage.
    coverageEnabled := false,
    libraryDependencies ++= Seq(
      jsDep("ch.epfl.lamp", "gears", Vgears),
      jsDep("com.softwaremill.sttp.tapir", "tapir-core", Vtapir),
      jsDep("com.softwaremill.sttp.tapir", "tapir-jsoniter-scala", Vtapir),
      jsDep(
        "com.github.plokhotnyuk.jsoniter-scala",
        "jsoniter-scala-core",
        Vjsoniter
      ),
      jsDep(
        "com.github.plokhotnyuk.jsoniter-scala",
        "jsoniter-scala-macros",
        Vjsoniter
      ),
      jsDep("org.scalameta", "munit", Vmunit) % Test
    ),
    scalaJSLinkerConfig ~= wasmConfig
  )

/** Gears on Scala.js needs the WebAssembly backend so JSPI can suspend (spec
  * §5.1). Wasm implies ES modules *and* at least ES2022. `withUseWebAssembly`
  * is the current spelling; `withExperimentalUseWebAssembly` is deprecated as
  * of Scala.js 1.22.0.
  *
  * `withUseJSPI(true)` is critical — it defaults to false and the linker
  * rejects all js.async/js.await usage without it. The error "Uses an async
  * block without JSPI support in WebAssembly" means exactly this flag.
  */
lazy val wasmConfig
    : org.scalajs.linker.interface.StandardConfig => org.scalajs.linker.interface.StandardConfig =
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
      "com.softwaremill.sttp.tapir" %% "tapir-files" % Vtapir,
      "com.augustnagro" %% "magnum" % Vmagnum,
      "org.flywaydb" % "flyway-core" % Vflyway,
      "org.flywaydb" % "flyway-database-postgresql" % Vflyway,
      "org.postgresql" % "postgresql" % Vpostgres,
      "com.zaxxer" % "HikariCP" % Vhikari,
      "de.mkammerer" % "argon2-jvm" % Vargon2,
      "ch.qos.logback" % "logback-classic" % Vlogback,
      "io.zonky.test" % "embedded-postgres" % VembeddedPg,
      "com.github.pureconfig" %% "pureconfig-core" % Vpureconfig,
      "org.scalameta" %% "munit" % Vmunit % Test,
      "com.softwaremill.sttp.client3" %% "core" % Vsttp
    ),
    dependencyOverrides ++= Seq(
      "io.zonky.test.postgres" % "embedded-postgres-binaries-linux-amd64" % VembeddedPgBinaries,
      "io.zonky.test.postgres" % "embedded-postgres-binaries-linux-amd64-alpine" % VembeddedPgBinaries,
      "io.zonky.test.postgres" % "embedded-postgres-binaries-darwin-amd64" % VembeddedPgBinaries,
      "io.zonky.test.postgres" % "embedded-postgres-binaries-windows-amd64" % VembeddedPgBinaries
    ),
    // Each database test case manages its own Zonky PostgreSQL instance on a
    // random port, so suites can run concurrently without shared test state.
    // Zonky shares a binary cache at /tmp/embedded-pg/; a warm cache avoids
    // concurrent extraction of the same PostgreSQL distribution.
    Test / parallelExecution := true,

    // Virtual threads want a real JVM 25+.
    javacOptions ++= Seq("-source", "25", "-target", "25"),
    Compile / mainClass := Some("lmbot.backend.Main"),

    // startDev runs this project in a fork and selects the complete local
    // resource. Tests and production packaging remain unaffected.
    Compile / fork := true,
    Compile / envVars := Map(
      "LMBOT_CONFIG_RESOURCE" -> "application-dev.conf"
    ),

    // Watch backend, frontend, and shared sources so `~backend/run` restarts
    // after changes anywhere in the application.
    watchSources ++= Def
      .uncached(Def.task {
        (Compile / unmanagedSources).value ++
          (frontend / Compile / unmanagedSources).value ++
          (sharedJVM / Compile / unmanagedSources).value
      })
      .value,

    // Package the linked frontend as classpath resources under `web/`, which is
    // where StaticRoutes looks (served at /assets). Without this the backend
    // serves index.html but 404s /assets/main.js, so the page loads blank —
    // linking the frontend is not the same as shipping it.
    Compile / resourceGenerators += Def.task {
      if ((ThisBuild / useFastLinkForAssets).value)
        (frontend / Compile / fastLinkJS).value // trigger dev link
      val linkedDir =
        if ((ThisBuild / useFastLinkForAssets).value)
          (frontend / Compile / fastLinkJSOutput).value
        else
          (frontend / Compile / fullLinkJSOutput).value
      val webDir = (Compile / resourceManaged).value / "web"
      IO.copyDirectory(linkedDir, webDir, overwrite = true)
      (webDir ** "*").get().filter(_.isFile)
    }.taskValue,

    // The deployable artifact the packaging Dockerfile copies. A stable name
    // keeps `COPY` free of version numbers and globs. The output path follows
    // the asset mode: `stageDockerJar` flips useFastLinkForAssets to false,
    // which bundles the production (full-link) frontend and writes the jar to
    // the repository root — the Docker build context. A plain
    // `backend/assembly` keeps the dev (fast) link and stays under target/.
    assembly / assemblyJarName := "lm-bot.jar",
    assembly / assemblyOutputPath := {
      val jarName = (assembly / assemblyJarName).value
      if ((ThisBuild / useFastLinkForAssets).value)
        (assembly / target).value / jarName
      else (ThisBuild / baseDirectory).value / jarName
    },

    assembly / mainClass := Some("lmbot.backend.Main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList(parts @ _*)
          if parts.lastOption.contains("module-info.class") =>
        MergeStrategy.discard
      case x => (assembly / assemblyMergeStrategy).value(x)
    }
  )

lazy val frontend = project
  .in(file("frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(sharedJS)
  .settings(commonSettings)
  .settings(
    name := "lm-bot-frontend",
    // sbt-scoverage instrumentation breaks Scala.js test bridge internals
    // (TrieMap MainNode).  JS projects run bare under coverage.
    coverageEnabled := false,
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= wasmConfig,
    Test / scalaJSLinkerConfig ~= wasmConfig,
    libraryDependencies ++= Seq(
      jsDep("ch.epfl.lamp", "gears", Vgears),
      jsDep("com.raquo", "laminar", Vlaminar),
      jsDep("org.scala-js", "scalajs-dom", VscalajsDom),
      jsDep("com.softwaremill.sttp.tapir", "tapir-sttp-client", Vtapir),
      jsDep("com.softwaremill.sttp.client3", "core", Vsttp),
      // scala-java-time itself is already pulled in transitively (via the
      // sttp/tapir client stack), but not this — without it, ZoneId.of of any
      // named zone (e.g. "Europe/Warsaw") throws ZoneRulesException at
      // runtime, since Scala.js has no IANA tzdb of its own.
      jsDep(
        "io.github.cquiroz",
        "scala-java-time-tzdb",
        VscalaJavaTimeTzdb
      ),
      jsDep("org.scalameta", "munit", Vmunit) % Test
    )
  )

lazy val root = project
  .in(file("."))
  .aggregate(sharedJVM, sharedJS, backend, frontend)
  .settings(
    name := "lm-bot",
    publish / skip := true,

    // Production artifact for the Docker image: flips the asset flag so the
    // resource generator bundles the full-link frontend, assembles
    // `./lm-bot.jar` in the Docker build context (see the backend assembly
    // settings above), then restores the dev default so the session cannot
    // leak the slow link into a later `run`/`startDev`.
    commands += Command.command("stageDockerJar") { state =>
      "set ThisBuild/useFastLinkForAssets := false" ::
        "backend/assembly" ::
        "set ThisBuild/useFastLinkForAssets := true" ::
        state
    },

    commands += Command.command("startDev") { state =>
      val log = state.log
      log.info("Starting development environment…")
      log.info("  → linking frontend (fastLinkJS)")
      log.info(
        "  → starting backend (forked JVM, sources watched — restart on change)"
      )
      log.info("")
      log.info(
        "Embedded PostgreSQL starts automatically on port 15432"
      )
      log.info("")
      // The resource generator triggers fastLinkJS on first compile, so we
      // don't run it explicitly here.
      "~backend/run" :: state
    }
  )
