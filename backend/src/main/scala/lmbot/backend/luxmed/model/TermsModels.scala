package lmbot.backend.luxmed.model

import java.time.{LocalDate, ZonedDateTime}
import java.util.UUID

/** A Luxmed datetime value that may be returned either with a zone offset
  * (ISO_OFFSET_DATE_TIME) or as a bare local datetime (ISO_LOCAL_DATE_TIME).
  * Both forms normalise to Europe/Warsaw.
  */
final case class LuxmedDateTime(value: ZonedDateTime)

/** Query parameters for the /PatientPortal/NewPortal/terms/index endpoint.
  */
final case class TermsQuery(
    cityId: Long,
    serviceVariantId: ServiceVariantId,
    searchDateFrom: LocalDate,
    searchDateTo: LocalDate,
    processId: UUID = UUID.randomUUID(),
    facilityIds: Option[FacilityId] = None,
    doctorIds: Option[DoctorId] = None,
    languageId: Long = 10,
    searchDatePreset: Int = 14
)

/** The top-level response from /PatientPortal/NewPortal/terms/index.
  */
final case class TermsResponse(
    correlationId: String,
    termsForService: TermsForService
)

final case class TermsForService(
    additionalData: AdditionalData,
    termsForDays: List[TermsForDay]
)

final case class AdditionalData(
    isPreparationRequired: Boolean,
    preparationItems: List[PreparationItem]
)

final case class PreparationItem(
    header: Option[String],
    text: Option[String]
)

final case class TermsForDay(
    day: LuxmedDateTime,
    terms: List[Term]
)

final case class Term(
    clinic: Option[String],
    clinicId: Long,
    clinicGroupId: Long,
    dateTimeFrom: LuxmedDateTime,
    dateTimeTo: LuxmedDateTime,
    doctor: Doctor,
    impedimentText: Option[String],
    isAdditional: Boolean,
    isImpediment: Boolean,
    isTelemedicine: Boolean,
    roomId: Long,
    scheduleId: ScheduleId,
    serviceId: ServiceVariantId
)
