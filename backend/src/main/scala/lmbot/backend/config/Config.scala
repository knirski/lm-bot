package lmbot.backend.config

import java.time.Duration

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
    masterKey: MasterKey
)

object Config:

  def fromEnv(env: Map[String, String]): Either[List[String], Config] =
    val errors = List.newBuilder[String]

    def required(key: String): String =
      env.get(key).filter(_.nonEmpty) match
        case Some(v) => v
        case None    => errors += s"$key is required"; ""

    def int(key: String, default: Int): Int =
      env.get(key).filter(_.nonEmpty) match
        case None    => default
        case Some(v) =>
          v.toIntOption match
            case Some(i) => i
            case None => errors += s"$key must be a number, got '$v'"; default

    def bool(key: String, default: Boolean): Boolean =
      env.get(key).filter(_.nonEmpty) match
        case None    => default
        case Some(v) =>
          v.toBooleanOption match
            case Some(b) => b
            case None    =>
              errors += s"$key must be true or false, got '$v'"; default

    val dbUrl = required("DATABASE_URL")
    val dbUser = required("DATABASE_USER")
    val dbPassword = required("DATABASE_PASSWORD")
    val host = env.get("HTTP_HOST").filter(_.nonEmpty).getOrElse("0.0.0.0")
    val portRaw = int("HTTP_PORT", 8080)
    val secure = bool("COOKIE_SECURE", true)
    val ttlDays = int("SESSION_TTL_DAYS", 7)

    val luxmedAppVersion =
      env.get("LUXMED_APP_VERSION") match
        case None                          => "4.44.0"
        case Some(value) if value.nonEmpty => value
        case Some(_)                       =>
          errors += "LUXMED_APP_VERSION must not be empty"
          "4.44.0"

    // Validate port at config boundary
    Port.fromInt(portRaw) match
      case Left(msg) => errors += msg
      case _         => ()

    // Validate app version at config boundary
    AppVersion.fromString(luxmedAppVersion) match
      case Left(msg) => errors += s"LUXMED_APP_VERSION: $msg"
      case _         => ()

    // Parse and validate master key
    val masterKeyRaw = required("LMBOT_MASTER_KEY")
    val parsedMasterKey =
      if masterKeyRaw.nonEmpty then MasterKey.fromBase64(masterKeyRaw)
      else Left("LMBOT_MASTER_KEY is required")
    parsedMasterKey match
      case Left(msg) => errors += msg
      case _         => ()

    val built = errors.result()
    if built.nonEmpty then Left(built)
    else
      Right(
        Config(
          dbUrl = dbUrl,
          dbUser = dbUser,
          dbPassword = Secret(dbPassword),
          httpHost = host,
          httpPort = Port.fromInt(portRaw).toOption.get,
          cookieSecure = secure,
          sessionTtl = Duration.ofDays(ttlDays.toLong),
          luxmedAppVersion =
            AppVersion.fromString(luxmedAppVersion).toOption.get,
          adminUsername = env.get("ADMIN_USERNAME").filter(_.nonEmpty),
          adminPassword =
            env.get("ADMIN_PASSWORD").filter(_.nonEmpty).map(Secret.apply),
          masterKey = parsedMasterKey.toOption.get
        )
      )
