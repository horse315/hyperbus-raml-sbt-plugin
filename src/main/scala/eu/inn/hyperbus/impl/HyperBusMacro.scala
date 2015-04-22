package eu.inn.hyperbus.impl

import eu.inn.hyperbus.protocol.{Body, Response}
import eu.inn.hyperbus.serialization.RequestDecoder

import scala.concurrent.Future
import scala.reflect.macros.blackbox.Context

private[hyperbus] object HyperBusMacro {

  def subscribe[IN: c.WeakTypeTag]
    (c: Context)
    (groupName: c.Expr[Option[String]])
    (handler: c.Expr[(IN) => Future[Response[Body]]]): c.Expr[String] = {

    import c.universe._

    val thiz = c.prefix.tree

    val in = weakTypeOf[IN]
    val url = "/resources"
    val method = "post"
    val contentType: Option[String] = None
    val decoder: RequestDecoder = null

    val obj = q"""{
      val thiz = $thiz
      val handler = eu.inn.hyperbus.impl.Helpers.wrapHandler($handler, null)
      val id = thiz.subscribe($url, $method, $contentType, $groupName, null)(handler)
      id
    }"""
    println(obj)
    c.Expr[String](obj)
  }
}