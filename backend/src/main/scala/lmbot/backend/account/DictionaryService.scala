package lmbot.backend.account

import gears.async.Async
import lmbot.backend.luxmed.model.{
  CityId,
  Doctor,
  ServiceVariant,
  ServiceVariantId
}
import lmbot.backend.luxmed.{LuxmedClient, LuxmedError}
import lmbot.shared.api.ApiError
import lmbot.shared.domain.{
  AccountId,
  DictionaryCity,
  DictionaryDoctor,
  DictionaryFacility,
  DictionaryService as DictionaryServiceItem,
  FacilitiesDoctorsResponse,
  UserId
}

/** Proxies Luxmed dictionary lookups for a caller-owned account, translating
  * backend-internal Luxmed wire models into shared DTOs. No Luxmed wire model
  * ever crosses this boundary (spec §5.7.4).
  *
  * The registry, not a fresh client per call, owns the account's gate: two
  * dictionary calls (or a dictionary call racing an engine check) queue behind
  * the same rate limiter. An auth failure found here is reported through the
  * same [[AccountHealthReporter]] the engine uses, so the account is marked and
  * its monitors paused no matter which call discovered it (issue #40).
  */
final class DictionaryService(
    clients: AccountClientRegistry,
    health: AccountHealthReporter
):

  private val unavailable = "Luxmed is temporarily unavailable."

  def cities(ownerId: UserId, accountId: AccountId)(using
      Async
  ): Either[ApiError, List[DictionaryCity]] =
    withClient(ownerId, accountId): client =>
      client.cities() match
        case Right(cities) =>
          Right(cities.map(city => DictionaryCity(city.id.value, city.name)))
        case Left(error) =>
          report(accountId, error)
          Left(luxmedErrorMapping(error, unavailable))

  def services(ownerId: UserId, accountId: AccountId)(using
      Async
  ): Either[ApiError, List[DictionaryServiceItem]] =
    withClient(ownerId, accountId): client =>
      client.serviceVariants() match
        case Right(variants) => Right(flatten(variants, ancestry = None))
        case Left(error)     =>
          report(accountId, error)
          Left(luxmedErrorMapping(error, unavailable))

  def facilitiesDoctors(
      ownerId: UserId,
      accountId: AccountId,
      cityId: Long,
      serviceId: Long
  )(using Async): Either[ApiError, FacilitiesDoctorsResponse] =
    withClient(ownerId, accountId): client =>
      client.facilitiesAndDoctors(
        CityId(cityId),
        ServiceVariantId(serviceId)
      ) match
        case Right(data) =>
          Right(
            FacilitiesDoctorsResponse(
              facilities = data.facilities.map(f =>
                DictionaryFacility(f.id.value, f.name)
              ),
              doctors = data.doctors.map(doctorEntry)
            )
          )
        case Left(error) =>
          report(accountId, error)
          Left(luxmedErrorMapping(error, unavailable))

  /** Only credential-shaped failures are recorded against the account; a
    * transient 5xx or a rate limit says nothing about its health.
    */
  private def report(accountId: AccountId, error: LuxmedError)(using
      Async
  ): Unit =
    error match
      case LuxmedError.AuthFailed =>
        health.reportAuthFailure(accountId, AccountStatusReason.AuthFailed)
      case _: LuxmedError.UnexpectedAuthResponse =>
        health.reportAuthFailure(accountId, AccountStatusReason.Challenge)
      case _ => ()

  private def withClient[A](ownerId: UserId, accountId: AccountId)(
      op: LuxmedClient => Either[ApiError, A]
  ): Either[ApiError, A] =
    clients.forOwnedAccount(ownerId, accountId).flatMap(op)

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
