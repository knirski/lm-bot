package lmbot.backend.luxmed

import lmbot.backend.config.Secret

/** An immutable, case-insensitive-by-name cookie jar. Cookies are merged by
  * name: a newer response cookie replaces an older one with the same name.
  * Cookies without a value are treated as deletion signals and remove the
  * corresponding stored cookie.
  */
final case class CookieJar private (entries: Map[String, Secret]):

  /** Return the stored cookie by its lowercased name. */
  def get(name: String): Option[Secret] = entries.get(name.toLowerCase)

  /** All stored cookie names (original casing is lost on merge). */
  def names: Set[String] = entries.keySet

  /** Merge the given response cookies into this jar. Each cookie replaces an
    * existing cookie of the same lowercased name. A cookie whose value is empty
    * or whose `Max-Age=0` removes the existing cookie.
    */
  def merge(responseCookies: List[(String, Secret)]): CookieJar =
    val merged = responseCookies.foldLeft(entries) {
      case (acc, (name, value)) =>
        val key = name.toLowerCase
        if value.value.isEmpty then acc - key
        else acc + (key -> value)
    }
    CookieJar(merged)

  /** Produce request header entries from the jar. */
  def requestCookies: List[(String, String)] =
    entries.map { (_, value) => ("Cookie", value.value) }.toList

object CookieJar:
  def empty: CookieJar = CookieJar(Map.empty)

  def apply(pairs: (String, Secret)*): CookieJar =
    CookieJar(pairs.map { (k, v) => k.toLowerCase -> v }.toMap)
