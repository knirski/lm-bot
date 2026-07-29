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
    httpPort: Int,
    cookieSecure: Boolean,
    sessionTtl: Duration,
    luxmedAppVersion: String,
    adminUsername: Option[String],
    adminPassword: Option[Secret]
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
    val port = int("HTTP_PORT", 8080)
    // Secure by default: the operator terminates TLS in front of us (spec §6).
    // Only a deliberate override turns it off, for plain-HTTP local dev.
    val secure = bool("COOKIE_SECURE", true)
    val ttlDays = int("SESSION_TTL_DAYS", 7)

    val luxmedAppVersion =
      env.get("LUXMED_APP_VERSION") match
        case None                          => "4.44.0"
        case Some(value) if value.nonEmpty => value
        case Some(_)                       =>
          errors += "LUXMED_APP_VERSION must not be empty"
          "4.44.0"

    val built = errors.result()
    if built.nonEmpty then Left(built)
    else
      Right(
        Config(
          dbUrl = dbUrl,
          dbUser = dbUser,
          dbPassword = Secret(dbPassword),
          httpHost = host,
          httpPort = port,
          cookieSecure = secure,
          sessionTtl = Duration.ofDays(ttlDays.toLong),
          luxmedAppVersion = luxmedAppVersion,
          adminUsername = env.get("ADMIN_USERNAME").filter(_.nonEmpty),
          adminPassword =
            env.get("ADMIN_PASSWORD").filter(_.nonEmpty).map(Secret.apply)
        )
      )
