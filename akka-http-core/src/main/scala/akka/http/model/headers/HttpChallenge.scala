/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package headers

import akka.http.util._
import java.util

import akka.http.model.japi.JavaMapping.Implicits._

final case class HttpChallenge(scheme: String, realm: String,
                               parameters: Map[String, String] = Map.empty) extends japi.headers.HttpChallenge with ValueRenderable {

  def render[R <: Rendering](r: R): r.type = {
    r ~~ scheme ~~ " realm=" ~~# realm
    if (parameters.nonEmpty) parameters.foreach { case (k, v) ⇒ r ~~ ',' ~~ k ~~ '=' ~~# v }
    r
  }

  /** Java API */
  def getParameters: util.Map[String, String] = parameters.asJava
}
