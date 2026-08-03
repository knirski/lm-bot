package lmbot.backend.config

import java.time.Duration

import scala.jdk.CollectionConverters.*

import com.typesafe.config.ConfigFactory
import pureconfig.ConfigReader
import pureconfig.ConfigSource
import pureconfig.error.UserValidationFailed

/** Secrets are wrapped so an accidental interpolation of the configuration into
  * a log line cannot leak them.
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
    liveLuxmedApi: Boolean = false,
    embeddedPg: Boolean = false
)

object Config:

  private val environmentPaths = Map(
    "DATABASE_URL" -> "dbUrl",
    "DATABASE_USER" -> "dbUser",
    "DATABASE_PASSWORD" -> "dbPassword",
    "HTTP_HOST" -> "httpHost",
    "HTTP_PORT" -> "httpPort",
    "COOKIE_SECURE" -> "cookieSecure",
    "SESSION_TTL_DAYS" -> "sessionTtl",
    "LUXMED_APP_VERSION" -> "luxmedAppVersion",
    "ADMIN_USERNAME" -> "adminUsername",
    "ADMIN_PASSWORD" -> "adminPassword",
    "LMBOT_MASTER_KEY" -> "masterKey",
    "LIVE_LUXMED_API" -> "liveLuxmedApi",
    "EMBEDDED_PG" -> "embeddedPg"
  )

  private val optionalEnvironmentKeys = Set("ADMIN_USERNAME", "ADMIN_PASSWORD")

  private def environmentConfig(env: Map[String, String]) =
    val values = environmentPaths.flatMap: (environmentKey, modelPath) =>
      env
        .get(environmentKey)
        .flatMap: value =>
          if optionalEnvironmentKeys(environmentKey) && value.isEmpty then None
          else
            val hoconValue =
              if environmentKey == "SESSION_TTL_DAYS" then s"P${value}D"
              else if environmentKey == "EMBEDDED_PG" && value == "1" then
                "true"
              else value
            Some(modelPath -> hoconValue)
    ConfigFactory.parseMap(values.asJava).resolve()

  private given ConfigReader[Secret] = ConfigReader[String].map(Secret.apply)

  private given ConfigReader[Port] =
    ConfigReader[Int].emap: value =>
      Port.fromInt(value).left.map(UserValidationFailed.apply)

  private given ConfigReader[AppVersion] =
    ConfigReader[String].emap: value =>
      AppVersion.fromString(value).left.map(UserValidationFailed.apply)

  private given ConfigReader[MasterKey] =
    ConfigReader[String].emap: value =>
      MasterKey.fromBase64(value).left.map(UserValidationFailed.apply)

  // Task 2 will replace this bridge reader with product derivation once the
  // validated domain readers live beside their domain types, and migrate the
  // Java duration to PureConfig's Scala finite-duration reader.
  private given ConfigReader[Config] = ConfigReader.forProduct13(
    "dbUrl",
    "dbUser",
    "dbPassword",
    "httpHost",
    "httpPort",
    "cookieSecure",
    "sessionTtl",
    "luxmedAppVersion",
    "adminUsername",
    "adminPassword",
    "masterKey",
    "liveLuxmedApi",
    "embeddedPg"
  )(Config.apply)

  def fromEnv(
      env: Map[String, String],
      resourceName: String = "application.conf"
  ): Either[List[String], Config] =
    val overrides = ConfigSource.fromConfig(environmentConfig(env))
    val defaults = ConfigSource.resources(resourceName)
    overrides
      .withFallback(defaults)
      .load[Config]
      .left
      .map(_.toList.map(_.description))
