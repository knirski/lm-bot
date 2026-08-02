package lmbot.backend.config

import java.time.Duration

import scala.jdk.CollectionConverters.*

import com.typesafe.config.ConfigFactory
import lmbot.backend.support.result
import lmbot.backend.support.result.?
import pureconfig.ConfigReader
import pureconfig.ConfigSource

/** Configuration is env-only (spec §9). Secrets are wrapped so that an
  * accidental interpolation of the config into a log line cannot leak them.
  */
final case class Secret(value: String):
  override def toString: String = "***"

case class Config(
    dbUrl: String,
    dbUser: String,
    dbPassword: Secret,
    httpHost: String,
    httpPort: Port,
    cookieSecure: Boolean,
    sessionTtl: Duration,
    luxmedAppVersion: AppVersion,
    adminUsername: Option[String],
    adminPassword: Option[Secret],
    masterKey: MasterKey,
    liveLuxmedApi: Boolean = false
)

object Config:

  /** Env vars that never reach PureConfig/Typesafe Config, because they are
    * free text an operator or attacker controls (a secret or a connection
    * string), not something to run through a general-purpose parsing pipeline.
    * Every other env var is "structural" and visible to `readStructural` below
    * — a deny-list, so a newly added structural field needs no matching entry
    * anywhere else to be read; forgetting to list a new *secret* here just
    * means it takes the (already-verified-safe, see `ConfigTest`) PureConfig
    * path rather than the direct one.
    */
  private val secretKeys = Set(
    "DATABASE_URL",
    "DATABASE_USER",
    "DATABASE_PASSWORD",
    "LMBOT_MASTER_KEY",
    "ADMIN_USERNAME",
    "ADMIN_PASSWORD"
  )

  /** A Typesafe `Config` built from only the non-secret env vars.
    * `LUXMED_APP_VERSION` is excluded from blanking-to-absent because, unlike
    * the others, a *present but blank* value is its own error rather than "use
    * the default" (see `luxmedAppVersionRaw` below).
    */
  private def structuralHocon(env: Map[String, String]) =
    val present = env.view
      .filterKeys(k => !secretKeys(k))
      .filter((k, v) => v.nonEmpty || k == "LUXMED_APP_VERSION")
      .toMap
    ConfigFactory.parseMap(present.asJava).resolve()

  /** Read one optional structural key. Missing is `Right(None)`; present but
    * malformed for `A` (e.g. `HTTP_PORT=eighty`) is a `Left` naming the key —
    * `ConfigReaderFailure.description` does not include the path, so the key is
    * prefixed on here.
    *
    * `ConfigSource.at` fails on a missing path regardless of the target type —
    * `Option[A]` only short-circuits missing keys inside case-class derivation,
    * not at a bare cursor — so absence is checked explicitly.
    */
  private def readOpt[A: ConfigReader](
      hocon: com.typesafe.config.Config,
      key: String
  ): Either[List[String], Option[A]] =
    if !hocon.hasPath(key) then Right(None)
    else
      ConfigSource
        .fromConfig(hocon)
        .at(key)
        .load[A]
        .map(Some(_))
        .left
        .map(_.toList.map(failure => s"$key: ${failure.description}"))

  def fromEnv(env: Map[String, String]): Either[List[String], Config] =
    val errors = List.newBuilder[String]

    def required(key: String): String =
      env.get(key).filter(_.nonEmpty) match
        case Some(v) => v
        case None    => errors += s"$key is required"; ""

    val dbUrl = required("DATABASE_URL")
    val dbUser = required("DATABASE_USER")
    val dbPassword = required("DATABASE_PASSWORD")

    val hocon = structuralHocon(env)

    def readStructural[A: ConfigReader](key: String): Option[A] =
      readOpt[A](hocon, key) match
        case Left(readErrors) => errors ++= readErrors; None
        case Right(value)     => value

    val host = readStructural[String]("HTTP_HOST").getOrElse("0.0.0.0")
    val portRaw = readStructural[Int]("HTTP_PORT").getOrElse(8080)
    val secure = readStructural[Boolean]("COOKIE_SECURE").getOrElse(true)
    val ttlDays = readStructural[Int]("SESSION_TTL_DAYS").getOrElse(7)
    val liveLuxmedApiRaw = env.get("LIVE_LUXMED_API")
    val parsedLiveLuxmedApi: Either[String, Boolean] =
      liveLuxmedApiRaw match
        case None          => Right(false)
        case Some("true")  => Right(true)
        case Some("false") => Right(false)
        case Some(_) => Left("LIVE_LUXMED_API must be exactly true or false")
    parsedLiveLuxmedApi.left.foreach(errors += _)

    val luxmedAppVersionRaw: Either[String, String] =
      readStructural[String]("LUXMED_APP_VERSION") match
        case None                          => Right("4.44.0")
        case Some(value) if value.nonEmpty => Right(value)
        case Some(_) => Left("LUXMED_APP_VERSION must not be empty")

    // Parse each fallible value exactly once, both to validate it and to build
    // the `Config` from — no `.get`/`.toOption.get` invariant to keep in sync.
    val parsedPort: Either[String, Port] = Port.fromInt(portRaw)
    parsedPort.left.foreach(errors += _)

    val parsedTtlDays: Either[String, Int] =
      if ttlDays >= 1 then Right(ttlDays)
      else Left("SESSION_TTL_DAYS must be at least 1")
    parsedTtlDays.left.foreach(errors += _)

    val parsedAppVersion: Either[String, AppVersion] =
      luxmedAppVersionRaw.flatMap(
        AppVersion.fromString(_).left.map(msg => s"LUXMED_APP_VERSION: $msg")
      )
    parsedAppVersion.left.foreach(errors += _)

    val masterKeyRaw = env.get("LMBOT_MASTER_KEY").filter(_.nonEmpty)
    val parsedMasterKey: Either[String, MasterKey] =
      masterKeyRaw match
        case None      => Left("LMBOT_MASTER_KEY is required")
        case Some(raw) => MasterKey.fromBase64(raw)
    parsedMasterKey.left.foreach(errors += _)

    val built = errors.result()
    if built.nonEmpty then Left(built)
    else
      val config = result: // the `.?`s cannot fail: `built` is empty
        Config(
          dbUrl = dbUrl,
          dbUser = dbUser,
          dbPassword = Secret(dbPassword),
          httpHost = host,
          httpPort = parsedPort.?,
          cookieSecure = secure,
          sessionTtl = Duration.ofDays(parsedTtlDays.?.toLong),
          luxmedAppVersion = parsedAppVersion.?,
          liveLuxmedApi = parsedLiveLuxmedApi.?,
          adminUsername = env.get("ADMIN_USERNAME").filter(_.nonEmpty),
          adminPassword =
            env.get("ADMIN_PASSWORD").filter(_.nonEmpty).map(Secret.apply),
          masterKey = parsedMasterKey.?
        )
      config.left.map(List(_))
