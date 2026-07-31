package lmbot.shared.domain

final case class NamedId(id: Long, name: String)

final case class DictionaryCity(id: Long, name: String)
final case class DictionaryService(id: Long, name: String)

final case class DictionaryFacility(id: Long, name: String)
final case class DictionaryDoctor(id: Long, name: String)

final case class FacilitiesDoctorsResponse(
    facilities: List[DictionaryFacility],
    doctors: List[DictionaryDoctor]
)
