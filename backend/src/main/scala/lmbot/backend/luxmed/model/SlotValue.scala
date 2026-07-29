package lmbot.backend.luxmed.model

import java.time.{LocalDate, LocalTime, ZoneId}
import scala.util.Try

/** A Warsaw-normalised appointment slot (parked, for Tasks 6-7).
  *
  * This domain value owns date, start time, end time, and zone validation. It
  * is introduced here alongside the reservation codecs so the slot model is
  * available when lock/confirm/release are implemented, but it is not yet wired
  * into production paths.
  *
  * The only valid zone is Europe/Warsaw — Luxmed's service area.
  */
final case class SlotValue(
    date: LocalDate,
    timeFrom: LocalTime,
    timeTo: LocalTime
):
  require(
    !timeTo.isBefore(timeFrom),
    s"timeTo $timeTo is before timeFrom $timeFrom"
  )

  def zone: ZoneId = SlotValue.warsawZone

object SlotValue:
  private val warsawZone = ZoneId.of("Europe/Warsaw")

  def apply(
      date: LocalDate,
      timeFrom: LocalTime,
      timeTo: LocalTime
  ): SlotValue =
    val s = new SlotValue(date, timeFrom, timeTo)
    s

  def fromLuxmedDateTime(
      dateTimeFrom: LuxmedDateTime,
      dateTimeTo: LuxmedDateTime
  ): Either[String, SlotValue] =
    Try:
      val fromWarsaw = dateTimeFrom.value.withZoneSameInstant(warsawZone)
      val toWarsaw = dateTimeTo.value.withZoneSameInstant(warsawZone)
      SlotValue(
        fromWarsaw.toLocalDate,
        fromWarsaw.toLocalTime,
        toWarsaw.toLocalTime
      )
    .toEither.left.map(_.getMessage)
