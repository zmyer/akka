/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi

import akka.http.model

object Http {
  def HttpRequest(): HttpRequest = model.HttpRequest()
  def HttpResponse(): HttpResponse = model.HttpResponse()

  def Uri(uri: model.Uri): Uri = JavaUri(uri)
}
