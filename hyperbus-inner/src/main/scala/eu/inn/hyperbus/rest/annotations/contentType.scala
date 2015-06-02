package eu.inn.hyperbus.rest.annotations

import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.language.experimental.macros


@compileTimeOnly("enable macro paradise to expand macro annotations")
class contentType(v: String) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Unit = macro AnnotationsMacro.contentType
}