package lmbot.backend.luxmed.model

/** A city as returned by /PatientPortal/NewPortal/Dictionary/cities.
  */
final case class City(id: Long, name: String)

/** A service variant (recursive tree node) as returned by
  * /PatientPortal/NewPortal/Dictionary/serviceVariantsGroups.
  */
final case class ServiceVariant(
    id: ServiceVariantId,
    name: String,
    expanded: Boolean,
    children: List[ServiceVariant],
    isTelemedicine: Boolean,
    paymentType: Long
):
  /** Flatten this tree node and all descendants into a single list. */
  def flatten: List[ServiceVariant] = this :: children.flatMap(_.flatten)

/** A doctor within a facility, from
  * /PatientPortal/NewPortal/Dictionary/facilitiesAndDoctors.
  */
final case class Doctor(
    academicTitle: Option[String],
    facilityGroupIds: Option[List[Long]],
    firstName: Option[String],
    isEnglishSpeaker: Option[Boolean],
    genderId: Option[Long],
    id: DoctorId,
    lastName: Option[String]
)

/** A named facility.
  */
final case class Facility(id: FacilityId, name: String)

/** The combined facilities and doctors response.
  */
final case class FacilitiesAndDoctors(
    doctors: List[Doctor],
    facilities: List[Facility]
)
