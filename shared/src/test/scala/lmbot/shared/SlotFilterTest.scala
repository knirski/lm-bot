package lmbot.shared

import java.time.{DayOfWeek, LocalDate, LocalDateTime, LocalTime}

import lmbot.shared.domain.{FoundSlot, SlotCriteria, SlotFilter}

/** Pure Warsaw-local slot filtering (spec §8: slot filtering lives in `shared`
  * and is tested without a database or a clock).
  */
class SlotFilterTest extends munit.FunSuite:

  private val criteria = SlotCriteria(
    facilityIds = List(9L),
    doctorIds = List(101L),
    dateFrom = LocalDate.parse("2026-08-10"),
    dateTo = LocalDate.parse("2026-08-14"),
    timeFrom = LocalTime.parse("08:00"),
    timeTo = LocalTime.parse("12:00"),
    daysOfWeek = List(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
  )

  private def slot(
      from: String = "2026-08-10T09:00",
      clinicId: Long = 9L,
      doctorId: Long = 101L
  ): FoundSlot =
    val start = LocalDateTime.parse(from)
    FoundSlot(
      key = s"400:$from",
      clinicId = clinicId,
      clinicName = Some("Puławska"),
      doctorId = doctorId,
      doctorName = "dr House",
      from = start,
      to = start.plusMinutes(15),
      telemedicine = false
    )

  test("a slot inside every criterion matches"):
    assert(SlotFilter.matches(slot(), criteria))

  test("date boundaries are inclusive"):
    assert(
      SlotFilter.matches(slot("2026-08-10T09:00"), criteria),
      "the first date must match"
    )
    // 2026-08-14 is the last date but a Friday, so extend the weekday
    // selection to isolate the date boundary from the weekday rule.
    val includingFriday =
      criteria.copy(daysOfWeek = criteria.daysOfWeek :+ DayOfWeek.FRIDAY)
    assert(
      SlotFilter.matches(slot("2026-08-14T09:00"), includingFriday),
      "the last date must match"
    )

  test("a slot outside the date range does not match"):
    assert(!SlotFilter.matches(slot("2026-08-07T09:00"), criteria))
    assert(!SlotFilter.matches(slot("2026-08-17T09:00"), criteria))

  test("a slot on an unselected weekday does not match"):
    // 2026-08-11 is a Tuesday, 2026-08-12 is a Wednesday.
    assert(!SlotFilter.matches(slot("2026-08-11T09:00"), criteria))
    assert(SlotFilter.matches(slot("2026-08-12T09:00"), criteria))

  test("the time window is start-inclusive and end-exclusive"):
    assert(SlotFilter.matches(slot("2026-08-10T08:00"), criteria))
    assert(!SlotFilter.matches(slot("2026-08-10T07:59"), criteria))
    assert(!SlotFilter.matches(slot("2026-08-10T12:00"), criteria))

  test("an unselected clinic or doctor does not match"):
    assert(!SlotFilter.matches(slot(clinicId = 10L), criteria))
    assert(!SlotFilter.matches(slot(doctorId = 999L), criteria))

  test("empty provider selections mean any clinic and any doctor"):
    val any = criteria.copy(facilityIds = List.empty, doctorIds = List.empty)
    assert(SlotFilter.matches(slot(clinicId = 10L, doctorId = 999L), any))

  test("a slot must satisfy every criterion at once"):
    // Monday, in range, in the window, but the wrong clinic: still rejected.
    assert(
      !SlotFilter.matches(slot("2026-08-10T09:00", clinicId = 10L), criteria)
    )
