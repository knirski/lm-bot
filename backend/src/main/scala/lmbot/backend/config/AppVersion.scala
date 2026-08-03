package lmbot.backend.config

import scala.util.matching.Regex

import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

/** A validated Luxmed app version string (e.g. "4.44.0").
  *
  * Constructed only via the smart constructor, which enforces the minimum
  * supported version (4.44.0) and a valid semver-like format at config-parsing
  * time. This is an opaque type so the validation is enforced at the boundary
  * and the string value is used internally without overhead.
  */
opaque type AppVersion = String

object AppVersion:

  given ConfigReader[AppVersion] = ConfigReader.fromCursor: cur =>
    cur.asString.flatMap: value =>
      if value.isEmpty then
        cur.failed(
          CannotConvert(
            value,
            "AppVersion",
            "LUXMED_APP_VERSION must not be empty"
          )
        )
      else
        fromString(value).fold(
          error => cur.failed(CannotConvert(value, "AppVersion", error)),
          Right.apply
        )

  private val pattern: Regex = raw"^(\d+)\.(\d+)\.(\d+)".r

  private val minMajor = 4
  private val minMinor = 44
  private val minPatch = 0

  def fromString(s: String): Either[String, AppVersion] =
    s match
      case pattern(majorStr, minorStr, patchStr) =>
        val major = majorStr.toInt
        val minor = minorStr.toInt
        val patch = patchStr.toInt
        if isAtLeast(major, minor, patch) then Right(s)
        else
          Left(
            s"AppVersion $s is below minimum $minMajor.$minMinor.$minPatch"
          )
      case _ =>
        Left(s"AppVersion '$s' does not match semver-like format (X.Y.Z)")

  private def isAtLeast(major: Int, minor: Int, patch: Int): Boolean =
    if major > minMajor then true
    else if major < minMajor then false
    else if minor > minMinor then true
    else if minor < minMinor then false
    else patch >= minPatch

  def unsafeFromString(s: String): AppVersion =
    fromString(s).fold(
      err => throw IllegalArgumentException(err),
      identity
    )

  extension (v: AppVersion) def value: String = v
