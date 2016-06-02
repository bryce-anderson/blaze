package org.http4s.blaze.examples

import java.util.concurrent.atomic.AtomicReference

import org.http4s.blaze.channel.ServerChannel
import org.http4s.blaze.http._
import org.http4s.blaze.pipeline.stages.monitors.IntervalConnectionMonitor
import org.http4s.blaze.util.Execution

import scala.concurrent.Future

object ExampleService {

  private implicit val ec = Execution.trampoline

  def http1Stage(status: Option[IntervalConnectionMonitor], maxRequestLength: Int, channel: Option[AtomicReference[ServerChannel]] = None): HttpServerStage =
    new HttpServerStage(1024*1024, maxRequestLength)(service(status, channel))

  def service(status: Option[IntervalConnectionMonitor], channel: Option[AtomicReference[ServerChannel]] = None)
             (request: Request): Response = HttpResponse(responder => {

    request.uri match {
      case "/bigstring" =>
        ResponseBuilder.Ok(bigstring, ("content-type", "application/binary")::Nil)(responder)

      case "/status" =>
        ResponseBuilder.Ok(status.map(_.getStats().toString).getOrElse("Missing Status."))(responder)

      case "/kill" =>
        channel.flatMap(a => Option(a.get())).foreach(_.close())
        ResponseBuilder.Ok("Killing connection.")(responder)

      case "/echo" =>
        val hs = request.headers.collect {
          case h@(k, _) if k.equalsIgnoreCase("Content-type") => h
        }

        val writer = responder(HttpResponsePrelude(200, "OK", hs))
        val body = request.body

        def go(): Future[Completed] = body().flatMap { chunk =>
          if (chunk.hasRemaining) writer.write(chunk).flatMap(_ => go())
          else writer.close()
        }

        go()

      case uri =>
        val sb = new StringBuilder
        sb.append("Hello world!\n")
          .append("Path: ").append(uri)
          .append("\nHeaders\n")
        request.headers.map { case (k, v) => "[\"" + k + "\", \"" + v + "\"]\n" }
          .addString(sb)

        val body = sb.result()
        ResponseBuilder.Ok(body)(responder)
    }
  })

  private val bigstring = (0 to 1024*1024*2).mkString("\n", "\n", "").getBytes()
}
