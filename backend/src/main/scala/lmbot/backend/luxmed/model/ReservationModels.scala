package lmbot.backend.luxmed.model

import java.time.LocalTime

/** Request body for POST /PatientPortal/NewPortal/reservation/lockterm.
  */
final case class LockTermRequest(
    date: String,
    doctorId: Long,
    facilityId: Long,
    impedimentText: Option[String],
    isAdditional: Boolean,
    isImpediment: Boolean,
    isPreparationRequired: Boolean,
    isTelemedicine: Boolean,
    roomId: Long,
    scheduleId: Long,
    serviceVariantId: Long,
    timeFrom: String,
    timeTo: String
)

/** Response from POST /PatientPortal/NewPortal/reservation/lockterm.
  */
final case class LockTermResponse(
    errors: List[String],
    warnings: List[String],
    hasErrors: Boolean,
    hasWarnings: Boolean,
    value: LockTermResponseValue
)

final case class LockTermResponseValue(
    changeTermAvailable: Boolean,
    conflictedVisit: Option[String],
    doctorDetails: Doctor,
    relatedVisits: List[RelatedVisit],
    temporaryReservationId: Long,
    valuations: List[Valuation]
)

final case class RelatedVisit(
    doctor: Doctor,
    facilityName: String,
    isTelemedicine: Boolean,
    reservationId: Long,
    timeFrom: LocalTime,
    timeTo: LocalTime
)

/** Pricing/referral information for a locked term.
  */
final case class Valuation(
    alternativePrice: Option[String],
    contractId: Option[Long],
    isExternalReferralAllowed: Boolean,
    isReferralRequired: Boolean,
    payerId: Option[Long],
    price: Option[Double],
    productElementId: Option[Long],
    productId: Option[Long],
    productInContractId: Option[Long],
    requireReferralForPP: Boolean,
    valuationType: Long
)

/** Request body for POST /PatientPortal/NewPortal/reservation/confirm.
  */
final case class ConfirmRequest(
    date: String,
    doctorId: Long,
    facilityId: Long,
    roomId: Long,
    scheduleId: Long,
    serviceVariantId: Long,
    temporaryReservationId: Long,
    timeFrom: LocalTime,
    valuation: Valuation
)

/** Response from POST /PatientPortal/NewPortal/reservation/confirm.
  */
final case class ConfirmResponse(
    errors: List[String],
    warnings: List[String],
    hasErrors: Boolean,
    hasWarnings: Boolean,
    value: ConfirmValue
)

final case class ConfirmValue(
    canSelfConfirm: Boolean,
    npsToken: String,
    reservationId: Long,
    serviceInstanceId: Long
)
