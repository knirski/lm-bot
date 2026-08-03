package lmbot.backend.config

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

import com.typesafe.config.{Config as TypesafeConfig, ConfigFactory}
import pureconfig.error.{CannotConvert, ConvertFailure}
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
    cur.asString.fold(
      _ =>
        cur.failed(
          CannotConvert("redacted", "Secret", "secret values must be strings")
        ),
      value => Right(Secret(value))
    )

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

  private val environmentPathNames = Map(
    "dbUrl" -> "DATABASE_URL",
    "dbUser" -> "DATABASE_USER",
    "dbPassword" -> "DATABASE_PASSWORD",
    "adminUsername" -> "ADMIN_USERNAME",
    "adminPassword" -> "ADMIN_PASSWORD",
    "masterKey" -> "LMBOT_MASTER_KEY",
    "liveLuxmedApi" -> "LIVE_LUXMED_API",
    "embeddedPg" -> "EMBEDDED_PG"
  )

  private val readerPathToEnvironmentKey = environmentPathNames.map:
    case (modelPath, environmentKey) =>
      readerFieldMapping(modelPath) -> environmentKey

  private val substitutionEnvironmentKeys = environmentPathNames.values.toSet

  private val booleanEnvironmentValues = Set("true", "false")
  private val embeddedPgEnvironmentValues = booleanEnvironmentValues ++ Set(
    "0",
    "1"
  )

  private val productionRequiredEnvironmentKeys = List(
    "DATABASE_URL",
    "DATABASE_USER",
    "DATABASE_PASSWORD",
    "LMBOT_MASTER_KEY"
  )

  private val emptyMeansMissing = Set(
    "DATABASE_URL",
    "DATABASE_USER",
    "DATABASE_PASSWORD",
    "ADMIN_USERNAME",
    "ADMIN_PASSWORD",
    "LMBOT_MASTER_KEY"
  )

  private def environmentConfig(env: Map[String, String]) =
    val values = env
      .filter((key, _) => substitutionEnvironmentKeys(key))
      .filterNot((key, value) => emptyMeansMissing(key) && value.isEmpty)
      .map:
        case ("EMBEDDED_PG", "1") => "EMBEDDED_PG" -> "true"
        case ("EMBEDDED_PG", "0") => "EMBEDDED_PG" -> "false"
        case other                => other
    ConfigFactory.parseMap(values.asJava)

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
        current
          .replace(s"'$readerPath'", s"'$environmentKey'")
          .replace(readerPath, environmentKey)
    if description.contains("LUXMED_APP_VERSION must not be empty") then
      "LUXMED_APP_VERSION must not be empty"
    else
      failure match
        case convert: ConvertFailure =>
          readerPathToEnvironmentKey
            .get(convert.path)
            .fold(description)(environmentKey =>
              s"$environmentKey: $description"
            )
        case _ => description

  private def validate(config: Config, env: Map[String, String]) =
    val errors = List(
      env
        .get("LIVE_LUXMED_API")
        .filterNot(booleanEnvironmentValues)
        .map(_ => "LIVE_LUXMED_API must be exactly true or false"),
      env
        .get("EMBEDDED_PG")
        .filterNot(embeddedPgEnvironmentValues)
        .map(_ => "EMBEDDED_PG must be true, false, 1, or 0"),
      Option.when(config.sessionTtl < 1.day)(
        "sessionTtl must be at least one day"
      )
    ).flatten
    Either.cond(errors.isEmpty, config, errors)

  def fromEnv(
      env: Map[String, String],
      resourceName: String = "application.conf"
  ): Either[List[String], Config] =
    val missingRequired =
      if resourceName == "application.conf" then
        productionRequiredEnvironmentKeys.filterNot(key =>
          env.get(key).exists(_.nonEmpty)
        )
      else Nil
    if missingRequired.nonEmpty then
      Left(missingRequired.map(key => s"$key is required"))
    else
      val resolved = environmentConfig(env)
        .withFallback(ConfigFactory.parseResources(resourceName))
        .resolve()
      ConfigSource
        .fromConfig(forNativeReader(resolved))
        .load[Config]
        .left
        .map(_.toList.map(operatorDescription))
        .flatMap(validate(_, env))
