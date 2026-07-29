package lmbot.backend.luxmed

import lmbot.backend.config.Secret

/** An immutable cookie jar. Cookie names are retained exactly as received; a
  * newer response cookie replaces an older one with the same exact name.
  * Cookies with an empty value are treated as deletion signals and remove the
  * corresponding stored cookie.
  */
final case class CookieJar private (entries: Map[String, Secret]):

  /** Return the stored cookie by its exact name. */
  def get(name: String): Option[Secret] = entries.get(name)

  /** All stored cookie names, preserving server-provided casing. */
  def names: Set[String] = entries.keySet

  /** Merge the given response cookies into this jar. Each cookie replaces an
    * existing cookie of the same exact name. A cookie whose value is empty
    * removes the existing cookie.
    */
  def merge(responseCookies: List[(String, Secret)]): CookieJar =
    val merged = responseCookies.foldLeft(entries) {
      case (acc, (name, value)) =>
        if value.value.isEmpty then acc - name
        else acc + (name -> value)
    }
    CookieJar(merged)

  /** Produce request header entries from the jar. */
  def requestCookies: List[(String, String)] =
    if entries.isEmpty then Nil
    else
      List(
        "Cookie" -> entries
          .map((name, value) => s"$name=${value.value}")
          .mkString("; ")
      )

  /** Produce (name, value) tuples for sttp's cookies method. */
  def toSeq: Seq[(String, String)] =
    entries.map((k, v) => (k, v.value)).toSeq

  /** Produce (name, Secret) pairs for merging. */
  def toList: List[(String, Secret)] =
    entries.toList

object CookieJar:
  def empty: CookieJar = CookieJar(Map.empty)

  def apply(pairs: (String, Secret)*): CookieJar =
    CookieJar(pairs.toMap)
