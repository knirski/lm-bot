package lmbot.frontend

import com.raquo.laminar.api.L.render
import gears.async.*
import gears.async.js.JsAsyncFromSync
import lmbot.frontend.api.ApiClient
import lmbot.frontend.elm.Runtime as ElmRuntime
import lmbot.frontend.elm.{Effect}
import lmbot.frontend.view.AppView
import org.scalajs.dom
import sttp.model.Uri

@main def main(): Unit =
  val baseUri = Uri.unsafeParse(dom.window.location.origin)
  val api = ApiClient(baseUri)
  val runtime = ElmRuntime[AppState, Msg](AppState.initial, Update(api).apply)

  val container = dom.document.getElementById("app")
  render(container, AppView(runtime))

  // UnsafeJsAsyncFromSync assumes we are already inside a js.async scope,
  // which on the Wasm backend is provided by the JSPI-enabled module export.
  // We call it directly (rather than Async.fromSync) to avoid the Scala 3
  // inline path-dependent type resolution bug with singleton FromSync givens.
  JsAsyncFromSync:
    Async.group:
      val restore = new Effect[Msg]:
        def run(using Async): Option[Msg] = Some:
          api.me() match
            case Right(user) => Msg.SessionRestored(user)
            case Left(_)     => Msg.SessionAbsent
      Future(restore.run.foreach(runtime.dispatch))
      runtime.run
