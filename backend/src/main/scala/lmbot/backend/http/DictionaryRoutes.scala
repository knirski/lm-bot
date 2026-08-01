package lmbot.backend.http

import lmbot.backend.account.DictionaryService
import lmbot.backend.auth.AuthService
import lmbot.shared.api.DictionaryEndpoints
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint

/** Translates HTTP to service calls and back. No policy lives here. */
class DictionaryRoutes(auth: AuthService, dictionaries: DictionaryService):

  private val citiesRoute: ServerEndpoint[Any, Identity] =
    DictionaryEndpoints.cities
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure { user => accountId =>
        AsyncBoundary.run(dictionaries.cities(user.id, accountId))
      }

  private val servicesRoute: ServerEndpoint[Any, Identity] =
    DictionaryEndpoints.services
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure { user => accountId =>
        AsyncBoundary.run(dictionaries.services(user.id, accountId))
      }

  private val facilitiesDoctorsRoute: ServerEndpoint[Any, Identity] =
    DictionaryEndpoints.facilitiesDoctors
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure { user =>
        { case (accountId, cityId, serviceId) =>
          AsyncBoundary.run(
            dictionaries
              .facilitiesDoctors(user.id, accountId, cityId, serviceId)
          )
        }
      }

  val endpoints: List[ServerEndpoint[Any, Identity]] =
    List(citiesRoute, servicesRoute, facilitiesDoctorsRoute)
