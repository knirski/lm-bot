package lmbot.backend.config

/** A validated TCP port number (1–65535).
  *
  * Constructed only via the smart constructor, which validates at
  * config-parsing time. This is an opaque type so the validation is enforced at
  * the boundary and the integer value is used internally without overhead.
  */
opaque type Port = Int

object Port:

  def fromInt(i: Int): Either[String, Port] =
    if i >= 1 && i <= 65535 then Right(i)
    else Left(s"Port $i is not in range 1–65535")

  extension (p: Port) def value: Int = p
