/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi

import akka.http.{ HttpExt, model }
import akka.actor.ActorSystem
import java.net.InetSocketAddress

object Http {
  private[http] def HttpRequest(): HttpRequest = model.HttpRequest()
  private[http] def HttpResponse(): HttpResponse = model.HttpResponse()

  private[http] def Uri(uri: model.Uri): Uri = JavaUri(uri)

  def get(system: ActorSystem): HttpExt = akka.http.Http.get(system)

  def Bind(host: String, port: Int): AnyRef = akka.http.Http.Bind(host, port)
}
