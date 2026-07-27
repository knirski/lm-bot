package lmbot.backend.http

import sttp.model.StatusCode
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.files.*
import sttp.tapir.server.ServerEndpoint

object StaticRoutes:

  private val loader = getClass.getClassLoader

  /** Built assets are mounted under a prefix on purpose. If they were served
    * from the root, this endpoint would match *every* path and answer 404 for
    * unknown ones, and the SPA fallback below would never be reached.
    */
  private val assets: ServerEndpoint[Any, Identity] =
    staticResourcesGetServerEndpoint[Identity]("assets")(loader, "web")

  /** Everything else is a client-side route, so hand back the index page and
    * let the app deal with it. A path whose last segment contains a dot is
    * treated as a missing file and 404s, rather than quietly returning HTML to
    * something that asked for a script.
    */
  private val spaFallback: ServerEndpoint[Any, Identity] =
    endpoint.get
      .in(paths)
      .out(htmlBodyUtf8)
      .errorOut(statusCode)
      .serverLogicPure[Identity] { segments =>
        if segments.lastOption.exists(_.contains('.')) then Left(StatusCode.NotFound)
        else
          Option(loader.getResourceAsStream("web/index.html")) match
            case Some(stream) => Right(new String(stream.readAllBytes(), "UTF-8"))
            case None         => Left(StatusCode.NotFound)
      }

  val endpoints: List[ServerEndpoint[Any, Identity]] = List(assets, spaFallback)
