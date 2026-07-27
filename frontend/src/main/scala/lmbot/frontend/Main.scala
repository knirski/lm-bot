package lmbot.frontend

import com.raquo.laminar.api.L.render
import gears.async.*
import gears.async.default.given
import lmbot.frontend.api.ApiClient
import lmbot.frontend.elm.{Effect, Runtime}
import lmbot.frontend.view.AppView
import org.scalajs.dom
import sttp.model.Uri

@main def main(): Unit =
  // Same origin as the page: the backend serves this app, which is also what
  // lets the browser attach the HttpOnly session cookie to API calls.
  val baseUri = Uri.unsafeParse(dom.window.location.origin)
  val api = ApiClient(baseUri)
  // `.apply` eta-expands the method into the function `Runtime` expects — an
  // `Update` instance is not itself a Function2.
  val runtime = new Runtime[AppState, Msg](AppState.initial, Update(api).apply)

  val container = dom.document.getElementById("app")
  render(container, AppView(runtime))

  Async.fromSync:
    Async.group:
      // `booting` stays true until this answers, so the login form is not
      // flashed at someone who already holds a valid session.
      val restore = new Effect[Msg]:
        def run(using Async): Option[Msg] = Some:
          api.me() match
            case Right(user) => Msg.SessionRestored(user)
            case Left(_)     => Msg.SessionAbsent
      Future(restore.run.foreach(runtime.dispatch))
      runtime.run
