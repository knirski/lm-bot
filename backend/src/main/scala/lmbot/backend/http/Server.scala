package lmbot.backend.http

import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.jdkhttp.{HttpServer, JdkHttpServer}

import java.util.concurrent.Executors

object Server:

  /** jdkhttp defaults to a single calling thread, which would serialise every
    * request. A virtual-thread-per-task executor is what makes the blocking
    * style in the services safe: handlers block freely, and Gears is used
    * inside them (spec §5.1).
    */
  def start(host: String, port: Int, endpoints: List[ServerEndpoint[Any, Identity]]): HttpServer =
    JdkHttpServer()
      .host(host)
      .port(port)
      .executor(Executors.newVirtualThreadPerTaskExecutor())
      .addEndpoints(endpoints)
      .start()
