package lmbot.backend.account

import gears.async.Async
import lmbot.backend.luxmed.LuxmedClient
import lmbot.backend.luxmed.model.{
  CityId,
  Doctor,
  ServiceVariant,
  ServiceVariantId
}
import lmbot.shared.api.ApiError
import lmbot.shared.domain.{
  AccountId,
  DictionaryCity,
  DictionaryDoctor,
  DictionaryFacility,
  DictionaryService as DictionaryServiceItem,
  FacilitiesDoctorsResponse
}

/** Proxies Luxmed dictionary lookups for a caller-owned account, translating
  * backend-internal Luxmed wire models into shared DTOs. No Luxmed wire model
  * ever crosses this boundary (spec §5.7.4).
  */
final class DictionaryService(clients: AccountClientFactory):

  private val unavailable =
    ApiError.Unexpected("Luxmed is temporarily unavailable.")

  def cities(ownerId: Long, accountId: AccountId)(using
      Async
  ): Either[ApiError, List[DictionaryCity]] =
    withClient(ownerId, accountId): client =>
      client
        .cities()
        .left
        .map(_ => unavailable)
        .map(_.map(city => DictionaryCity(city.id.value, city.name)))

  def services(ownerId: Long, accountId: AccountId)(using
      Async
  ): Either[ApiError, List[DictionaryServiceItem]] =
    withClient(ownerId, accountId): client =>
      client
        .serviceVariants()
        .left
        .map(_ => unavailable)
        .map(flatten(_, ancestry = None))

  def facilitiesDoctors(
      ownerId: Long,
      accountId: AccountId,
      cityId: Long,
      serviceId: Long
  )(using Async): Either[ApiError, FacilitiesDoctorsResponse] =
    withClient(ownerId, accountId): client =>
      client
        .facilitiesAndDoctors(CityId(cityId), ServiceVariantId(serviceId))
        .left
        .map(_ => unavailable)
        .map { data =>
          FacilitiesDoctorsResponse(
            facilities =
              data.facilities.map(f => DictionaryFacility(f.id.value, f.name)),
            doctors = data.doctors.map(doctorEntry)
          )
        }

  private def withClient[A](ownerId: Long, accountId: AccountId)(
      op: LuxmedClient => Either[ApiError, A]
  ): Either[ApiError, A] =
    clients.forStored(ownerId, accountId).flatMap(op)

  /** Flattens the recursive `ServiceVariant` tree into a flat, selectable list.
    * Each node's own name is prefixed with its ancestors' names (`"Parent >
    * Child"`) so that two variants with the same leaf name under different
    * parents remain distinguishable once flattened — `ServiceVariant.flatten`
    * alone drops that ancestor context.
    */
  private def flatten(
      variants: List[ServiceVariant],
      ancestry: Option[String]
  ): List[DictionaryServiceItem] =
    variants.flatMap { variant =>
      val label =
        ancestry.fold(variant.name)(parent => s"$parent > ${variant.name}")
      DictionaryServiceItem(variant.id.value, label) ::
        flatten(variant.children, Some(label))
    }

  private def doctorEntry(doctor: Doctor): DictionaryDoctor =
    val name =
      List(doctor.academicTitle, doctor.firstName, doctor.lastName).flatten
        .mkString(" ")
        .trim
    DictionaryDoctor(
      doctor.id.value,
      if name.isEmpty then s"Doctor #${doctor.id.value}" else name
    )
