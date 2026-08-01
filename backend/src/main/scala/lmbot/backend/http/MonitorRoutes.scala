package lmbot.backend.http

import lmbot.backend.auth.AuthService
import lmbot.backend.monitor.MonitorService
import lmbot.shared.api.MonitorEndpoints
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint

/** Translates HTTP to service calls and back. No policy lives here — pure DB
  * work, so unlike `AccountRoutes`/`DictionaryRoutes` no `AsyncBoundary` is
  * needed.
  */
class MonitorRoutes(auth: AuthService, monitors: MonitorService):

  private val createRoute: ServerEndpoint[Any, Identity] =
    MonitorEndpoints.create
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure(user => draft => monitors.create(user.id, draft))

  private val listRoute: ServerEndpoint[Any, Identity] =
    MonitorEndpoints.list
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure(user => (_: Unit) => monitors.list(user.id))

  private val getRoute: ServerEndpoint[Any, Identity] =
    MonitorEndpoints.get
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure(user => monitorId => monitors.get(user.id, monitorId))

  private val updateRoute: ServerEndpoint[Any, Identity] =
    MonitorEndpoints.update
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure(user =>
        (monitorId, draft) => monitors.update(user.id, monitorId, draft)
      )

  private val pauseRoute: ServerEndpoint[Any, Identity] =
    MonitorEndpoints.pause
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure(user =>
        monitorId => monitors.pause(user.id, monitorId).map(_ => ())
      )

  private val resumeRoute: ServerEndpoint[Any, Identity] =
    MonitorEndpoints.resume
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure(user =>
        monitorId => monitors.resume(user.id, monitorId).map(_ => ())
      )

  private val deleteRoute: ServerEndpoint[Any, Identity] =
    MonitorEndpoints.delete
      .serverSecurityLogicPure(auth.authenticate)
      .serverLogicPure(user => monitorId => monitors.delete(user.id, monitorId))

  val endpoints: List[ServerEndpoint[Any, Identity]] =
    List(
      createRoute,
      listRoute,
      getRoute,
      updateRoute,
      pauseRoute,
      resumeRoute,
      deleteRoute
    )
