package lmbot.backend.config

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

import com.typesafe.config.{Config as TypesafeConfig, ConfigFactory}
import pureconfig.error.ConvertFailure
import pureconfig.{
  CamelCase,
  ConfigFieldMapping,
  ConfigReader,
  ConfigSource,
  KebabCase
}

/** Secrets are wrapped so an accidental interpolation of the configuration into
  * a log line cannot leak them.
  */
final case class Secret(value: String):
  override def toString: String = "***"

object Secret:
  given ConfigReader[Secret] = ConfigReader.fromCursor: cur =>
    cur.asString.map(Secret.apply)

case class Config(
    dbUrl: String,
    dbUser: String,
    dbPassword: Secret,
    httpHost: String,
    httpPort: Port,
    cookieSecure: Boolean,
    sessionTtl: FiniteDuration,
    luxmedAppVersion: AppVersion,
    adminUsername: Option[String],
    adminPassword: Option[Secret],
    masterKey: MasterKey,
    liveLuxmedApi: Boolean = false,
    embeddedPg: Boolean = false
) derives ConfigReader

object Config:

  private val readerFieldMapping = ConfigFieldMapping(CamelCase, KebabCase)

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

  private val readerPathToEnvironmentKey = environmentPaths.map:
    case (environmentKey, modelPath) =>
      readerFieldMapping(modelPath) -> environmentKey

  private def environmentConfig(env: Map[String, String]) =
    val values = environmentPaths.flatMap: (environmentKey, modelPath) =>
      env
        .get(environmentKey)
        .flatMap: value =>
          if optionalEnvironmentKeys(environmentKey) && value.isEmpty then None
          else
            val hoconValue =
              if environmentKey == "SESSION_TTL_DAYS" then s"$value days"
              else if environmentKey == "EMBEDDED_PG" && value == "1" then
                "true"
              else value
            Some(modelPath -> hoconValue)
    ConfigFactory.parseMap(values.asJava).resolve()

  private def forNativeReader(config: TypesafeConfig): TypesafeConfig =
    config
      .root()
      .entrySet()
      .asScala
      .foldLeft(ConfigFactory.empty()):
        case (mapped, entry) =>
          mapped.withValue(readerFieldMapping(entry.getKey()), entry.getValue())

  private def operatorDescription(
      failure: pureconfig.error.ConfigReaderFailure
  ) =
    val description = readerPathToEnvironmentKey.foldLeft(failure.description):
      case (current, (readerPath, environmentKey)) =>
        current.replace(s"'$readerPath'", s"'$environmentKey'")
    if description.contains("LUXMED_APP_VERSION must not be empty") then
      "LUXMED_APP_VERSION must not be empty"
    else
      failure match
        case convert: ConvertFailure =>
          readerPathToEnvironmentKey
            .get(convert.path)
            .fold(description)(environmentKey => s"$environmentKey: $description")
        case _ => description

  private def validate(config: Config, env: Map[String, String]) =
    val errors = List(
      env
        .get("LIVE_LUXMED_API")
        .filter(value => value != "true" && value != "false")
        .map(_ => "LIVE_LUXMED_API must be exactly true or false"),
      Option.when(config.sessionTtl < 1.day)(
        "SESSION_TTL_DAYS must be at least one day"
      )
    ).flatten
    Either.cond(errors.isEmpty, config, errors)

  def fromEnv(
      env: Map[String, String],
      resourceName: String = "application.conf"
  ): Either[List[String], Config] =
    val overrides =
      ConfigSource.fromConfig(forNativeReader(environmentConfig(env)))
    val defaults = ConfigSource.fromConfig(
      forNativeReader(ConfigFactory.parseResources(resourceName).resolve())
    )
    overrides
      .withFallback(defaults)
      .load[Config]
      .left
      .map(_.toList.map(operatorDescription))
      .flatMap(validate(_, env))
