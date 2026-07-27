package lmbot.frontend

import com.raquo.laminar.api.L.render
import lmbot.frontend.api.ApiClient
import lmbot.frontend.elm.{AsyncEffect, Runtime}
import lmbot.frontend.view.AppView
import org.scalajs.dom
import sttp.model.Uri

import scala.concurrent.ExecutionContext

@main def main(): Unit =
  val baseUri = Uri.unsafeParse(dom.window.location.origin)
  val api = ApiClient(baseUri)
  val runtime = new Runtime[AppState, Msg](AppState.initial, Update(api).apply)

  val container = dom.document.getElementById("app")
  render(container, AppView(runtime))

  // Session restore: check if the browser already holds a valid session.
  given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue
  val restore = new AsyncEffect[Msg]:
    def run(): scala.concurrent.Future[Option[Msg]] =
      api.me().map:
        case Right(user) => Some(Msg.SessionRestored(user))
        case Left(_)     => Some(Msg.SessionAbsent)
  restore.run().foreach(_.foreach(runtime.dispatch))
