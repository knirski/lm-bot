package lmbot.backend.http

import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

object HealthRoutes:

  private val health: ServerEndpoint[Any, Identity] =
    endpoint.get
      .in("health")
      .out(stringBody)
      .serverLogicPure[Identity](_ => Right("ok"))

  val endpoints: List[ServerEndpoint[Any, Identity]] = List(health)
