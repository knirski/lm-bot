package lmbot.backend.config

import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

/** A validated TCP port number (1–65535).
  *
  * Constructed only via the smart constructor, which validates at
  * config-parsing time. This is an opaque type so the validation is enforced at
  * the boundary and the integer value is used internally without overhead.
  */
opaque type Port = Int

object Port:

  given ConfigReader[Port] = ConfigReader.fromCursor: cur =>
    cur.asInt.flatMap: value =>
      fromInt(value).fold(
        error => cur.failed(CannotConvert(value.toString, "Port", error)),
        Right.apply
      )

  def fromInt(i: Int): Either[String, Port] =
    if i >= 1 && i <= 65535 then Right(i)
    else Left(s"Port $i is not in range 1–65535")

  extension (p: Port) def value: Int = p
