/*
 * Copyright (c) 2017 Magomed Abdurakhmanov, Hypertino
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 */

package com.hypertino.hyperbus.raml

import java.util.Date

import com.hypertino.hyperbus.raml.utils.StyleConverter
import com.hypertino.inflector.English
import com.hypertino.inflector.naming._
import com.hypertino.hyperbus.utils._
import com.hypertino.hyperbus.utils.uri.{TextToken, UriPathParser}
import org.raml.v2.api.model.v10.api.Api
import org.raml.v2.api.model.v10.bodies.Response
import org.raml.v2.api.model.v10.datamodel._
import org.raml.v2.api.model.v10.methods.Method
import org.raml.v2.api.model.v10.resources.Resource
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._

class InterfaceGenerator(api: Api, options: GeneratorOptions) {
  protected val log = LoggerFactory.getLogger(getClass)
  protected val enumsConverter = new StyleConverter(options.ramlEnumNameStyle, options.enumFieldsStyle)
  protected val classNameConverter = new StyleConverter(options.ramlTypeNameStyle, options.classNameStyle)
  protected val fieldNameConverter = new StyleConverter(options.ramlFieldsNameStyle, options.classFieldsStype)
  protected val contentTypeConverter = CamelCaseToDashCaseConverter

  protected val messageReservedWords = Set(
    "headers", "body", "query"
  )

  protected val bodyReservedWords = Set(
    "contentType"
  )

  def generate(): String = {
    val builder = new StringBuilder
    if (options.defaultImports) {
      generateImports(builder)
      builder.append("\n// --------------------\n\n")
    }

    options.customImports.foreach { customImports ⇒
      builder.append(customImports)
      builder.append("\n// --------------------\n\n")
    }

    if (options.generatorInformation) {
      generateInformation(builder)
      builder.append("\n// --------------------\n\n")
    }
    generateTypes(builder)
    builder.append("\n// --------------------\n\n")
    generateRequests(builder)
    builder.toString
  }

  protected def generateImports(builder: StringBuilder) = {
    builder.append(
      s"""
         |package ${options.packageName}
         |
         |import com.hypertino.binders.annotations.fieldName
         |import com.hypertino.hyperbus.model._
         |import com.hypertino.hyperbus.model.annotations._
         |import com.hypertino.binders.value._
      """.stripMargin
    )
  }

  protected def generateInformation(builder: StringBuilder) = {
    builder.append(
      s"""
        |/*
        | DO NOT EDIT
        | Autogenerated on ${new Date}
        | options: $options
        |*/
        |
      """.stripMargin)
  }

  protected def generateTypes(builder: StringBuilder) = {
    api.types().foreach {
      case obj: ObjectTypeDeclaration ⇒
        generateObjectType(builder, obj)

      case strEl: StringTypeDeclaration ⇒
        generateEnumStrElement(builder, strEl)

      case other ⇒
        log.warn(s"Currently $other is not supported in types")
    }
  }

  protected def generateObjectType(builder: StringBuilder, obj: ObjectTypeDeclaration) = {
    val isBody = api.resources.exists { resource ⇒
      resource.methods.exists { method ⇒
        method.responses.exists { response ⇒
          response.body.exists { body ⇒
            body.`type` == obj.name
          }
        } ||
        method.body.exists { body ⇒
          body.`type` == obj.name
        }
      }
    }

    if (isBody) {
      val getBodyResource = api.resources.find { resource ⇒
        resource.methods.exists { method ⇒
          method.method.toLowerCase == "get" &&
            method.responses.exists { response ⇒
              response.code.value == "200" &&
                response.body.exists { body ⇒
                  body.`type` == obj.name
                }
            }
        }
      }
      val isCreatedBody = api.resources.exists { resource ⇒
        resource.methods.exists { method ⇒
          method.responses.exists { response ⇒
            response.code.value == "201" &&
              response.body.exists { body ⇒
                body.`type` == obj.name
              }
          }
        }
      }

      if (isBody) {
        obj.properties.foreach { prop ⇒
          if (bodyReservedWords.contains(prop.name)) {
            throw new RamlSyntaxException(s"Can't generate class '${obj.name}': '${prop.name}' is a reserved word for a Body")
          }
        }
      }

      builder.append(s"""@body("${options.contentTypePrefix.getOrElse("")}${contentTypeConverter.convert(obj.name)}")\n""")
      builder.append(s"case class ${obj.name}(\n")
      generateCaseClassProperties(builder, obj.properties, true)
      /*if (isCreatedBody || getBodyResource.isDefined) {
        builder.append(s""",\n    @fieldName("_links") links: Links.LinksMap""")
        if (getBodyResource.isDefined) {
          builder.append(s""" = ${obj.name}.defaultLinks""")
        }
      }*/
      builder.append("\n  ) extends Body")
      /*if (isCreatedBody || getBodyResource.isDefined) {
        builder.append(" with Links")
      }
      if (isCreatedBody) {
        builder.append(" with CreatedBody")
      }*/
      builder.append("\n\n")

      getBodyResource.map { r ⇒
        builder.append(s"object ${obj.name} extends BodyObjectApi[${obj.name}] {\n")
        //builder.append(s"""  val selfPattern = "${r.relativeUri.value}"\n""")
        //builder.append(s"""  val defaultLinks = Links(selfPattern, templated = true)\n""")
        builder.append("}\n\n")
      } getOrElse {
        builder.append(s"object ${obj.name} extends BodyObjectApi[${obj.name}]\n\n")
      }
    } else {
      builder.append(s"case class ${obj.name}(\n")
      generateCaseClassProperties(builder, obj.properties, true)
      builder.append("\n  ) extends scala.Serializable\n\n")
    }
  }

  protected def generateRequests(builder: StringBuilder) = {
    api.resources.foreach { resource ⇒
      resource.methods.foreach { method ⇒
        generateRequest(builder, method)
      }
    }
  }

  // method.method.toUpperCase -> for feed:put -> FEED_PUT
  protected def generateRequest(builder: StringBuilder, method: Method) = {
    val resource = method.resource()

    builder.append(s"""@request(Method.${method.method.replace(':','_').toUpperCase}, "${api.baseUri().value}${resource.relativeUri.value}")\n""")
    val name = requestClassName(resource.relativeUri.value, method.method)
    builder.append(s"case class $name(\n")
    val classParameters = resource.uriParameters().toSeq ++ method.queryParameters().toSeq

    classParameters.foreach { prop ⇒
      if (messageReservedWords.contains(prop.name)) {
        throw new RamlSyntaxException(s"Can't generate class '$name' for ${resource.relativeUri.value}: '${prop.name}' is a reserved word for a Request/Response")
      }
    }

    generateCaseClassProperties(builder, classParameters, false)
    val (bodyType, defBodyValue) = method.method match {
      case "get" | "delete" ⇒ ("EmptyBody", Some("EmptyBody"))
      case _ ⇒
        (method.body.headOption.filterNot(_.`type`()=="any").map(_.`type`).getOrElse("DynamicBody"), None)
    }
    if (classParameters.nonEmpty) {
      builder.append(",\n")
    }
    builder.append(s"    body: $bodyType\n")
    builder.append(s") extends Request[$bodyType]\n")
    val successResponses = method.responses.filter{r ⇒ val i = r.code.value.toInt; i >= 200 && i < 400}

    if (successResponses.nonEmpty) {
      if (successResponses.size > 1)
        builder.append("  with DefinedResponse[(\n")
      else
        builder.append("  with DefinedResponse[\n")
      var isFirst = true
      successResponses.foreach { r ⇒
        if (isFirst) {
          builder.append("    ")
        }
        else {
          builder.append(",\n    ")
        }
        isFirst = false

        builder.append(getFullResponseType(r))
      }
      if (successResponses.size > 1)
        builder.append("\n  )]\n\n")
      else
        builder.append("\n  ]\n\n")
    } else {
      builder.append("\n")
    }

    val responseType = if (successResponses.size == 1) {
      getFullResponseType(successResponses.head)
    }
    else if (successResponses.size > 1) {
      val head = successResponses.head
      val headBodyType = getResponseBodyType(head)
      if (successResponses.tail.forall(r ⇒ headBodyType == getResponseBodyType(r))) {
        "Response[" + headBodyType + "]"
      }
      else {
        "ResponseBase"
      }
    }
    else {
      "ResponseBase"
    }

    if (!method.method().startsWith("feed:")) {
      builder.append(s"trait ${name}MetaCompanion {\n")
      builder.append(s"  def apply(\n")
      generateCaseClassProperties(builder, classParameters, true)
      if (classParameters.nonEmpty) {
        builder.append(",\n")
      }
      defBodyValue match {
        case Some(s) ⇒
          builder.append(s"    body: $bodyType = $s,\n")
        case None ⇒
          builder.append(s"    body: $bodyType,\n")
      }
      builder.append(s"    headers: com.hypertino.hyperbus.model.Headers = com.hypertino.hyperbus.model.Headers.empty,\n")
      builder.append(s"    query: com.hypertino.binders.value.Value = com.hypertino.binders.value.Null\n")
      builder.append(s"  )(implicit mcx: MessagingContext): $name\n")
      builder.append(s"}\n\n")

      builder.append(s"object $name extends com.hypertino.hyperbus.model.RequestMetaCompanion[$name] with ${name}MetaCompanion {\n")
      builder.append("  implicit val meta = this\n")
      builder.append(s"  type ResponseType = ")
      builder.append(responseType)
      builder.append(s"\n}\n\n")
    }

    successResponses.foreach { r ⇒
      r.body().foreach { t ⇒
        if (t.`type`() != "object[]" && t.`type`().endsWith("[]")) {
          val typ = collectionResponseType(t.`type`())
          val originalTyp = t.`type`().substring(0, t.`type`().length-2)
          builder.append(s"""@body("${options.contentTypePrefix.getOrElse("")}${contentTypeConverter.convert(typ)}")\n""")
          builder.append(s"case class $typ(items: Seq[$originalTyp]) extends CollectionBody[$originalTyp]\n\n")
          builder.append(s"object  $typ extends BodyObjectApi[$typ] {\n")
          builder.append(s"}\n\n")
        }
      }
    }
  }

//  protected def generateFeedRequest(builder: StringBuilder, method: Method, resource: Resource) = {
//    builder.append(s"""@request(Method.${"FEED_" + method.method.toUpperCase}, "${resource.relativeUri.value}")\n""")
//    val name = requestClassName(resource.relativeUri.value, "feed-" + method.method)
//    builder.append(s"case class $name(\n")
//    val uriParameters = resource.uriParameters().toSeq
//
//    uriParameters.foreach { prop ⇒
//      if (messageReservedWords.contains(prop.name)) {
//        throw new RamlSyntaxException(s"Can't generate class '$name' for ${resource.relativeUri.value}: '${prop.name}' is a reserved word for a Request/Response")
//      }
//    }
//
//    generateCaseClassProperties(builder, uriParameters)
//    val bodyType = method.method match {
//      case "get" ⇒ "QueryBody"
//      case "delete" ⇒ "EmptyBody"
//      case _ ⇒
//        method.body.headOption.flatMap(_.`type`.headOption).getOrElse("DynamicBody")
//    }
//    if (uriParameters.nonEmpty) {
//      builder.append(",\n")
//    }
//    builder.append(s"    body: $bodyType\n  ) extends Request[$bodyType]\n\n")
//  }

  protected def getFullResponseType(r: Response): String = {
    getResponseType(r.code.value) + '[' + getResponseBodyType(r) + ']'
  }

  protected def getResponseBodyType(r: Response): String = {
    r.body.headOption.filterNot { t ⇒
      t.`type`() == "any" || t.`type`() == "object"
    }.map(s ⇒ collectionResponseType(s.`type`)).getOrElse("DynamicBody")
  }

  protected def collectionResponseType(s: String) : String = {
    if(s.endsWith("[]")) {
      s.substring(0, s.length-2) + "Collection"
    }
    else {
      s
    }
  }

  protected def requestClassName(uriPattern: String, method: String): String = {
    val tokens = UriPathParser.tokens(uriPattern).toSeq.zipWithIndex
    val last = tokens.reverse.head

    val dashed = tokens.collect {
      case (TextToken(s), index) ⇒
        // this is last token and it's text token, don't depluralize
        // it should be a collection
        if (index == last._2)
          s
        else
          English.singular(s)
    } :+ method.replace(':', '-') mkString "-"
    classNameConverter.convert(dashed)
  }

  protected def generateCaseClassProperties(builder: StringBuilder, properties: Seq[TypeDeclaration], allowOptionals: Boolean) = {
    var isFirst = true
    properties.foreach { property ⇒
      if (isFirst) {
        builder.append("    ")
      }
      else {
        builder.append(",\n    ")
      }
      isFirst = false
      val (propertyName, isOptional) = {
        val pname = if (property.name.indexOf(':') >= 0) {
          // hyperbus syntax x:@, y:*, etc
          property.name.substring(0, property.name.indexOf(':'))
        } else {
          property.name
        }
        (
          if (pname.endsWith("?")) pname.substring(0, pname.indexOf("?")) else pname,
          pname.endsWith("?") || (property.required != null && !property.required)
          )
      }
      builder.append(fieldNameConverter.convert(propertyName))
      builder.append(": ")
      val (typeName, emptyValue) = mapType(property, isOptional)
      builder.append(typeName)
      // if (property.defaultValue() != null && property.defaultValue() != "") {
      //  todo: support def values
      // }
      if (isOptional && allowOptionals) {
        builder.append(" = ")
        builder.append(emptyValue)
      }
    }
  }

  protected def generateEnumStrElement(builder: StringBuilder, el: StringTypeDeclaration) = {
    builder.append(s"object ${el.name} {\n  type StringEnum = String\n")
    el.enumValues().foreach { e ⇒
      builder.append(s"""  final val ${enumsConverter.convert(e)} = "$e"\n""")
    }
    builder.append(s"  final val values = Seq(${el.enumValues().map(enumsConverter.convert).mkString(",")})\n")
    builder.append("  final val valuesSet = values.toSet\n")
    builder.append("}\n\n")
  }

  // todo: numeric enums?
  protected def mapType(property: TypeDeclaration, isOptional: Boolean): (String, String) = {
    val r = property match {
      case se : StringTypeDeclaration if se.enumValues().nonEmpty ⇒ (se.`type`() + ".StringEnum", isOptional, "None")
      case _ : StringTypeDeclaration ⇒ ("String", isOptional, "None")
      case _ : IntegerTypeDeclaration ⇒ ("Int", isOptional, "None") // todo: also can have format!!
      case n : NumberTypeDeclaration ⇒ (n.format match {
        case "int32" | "int" ⇒ "Int"
        case "int64" | "long" ⇒ "Long"
        case "float" ⇒ "Float"
        case "double" ⇒ "Double"
        case "int16" ⇒ "Short"
        case "int8" ⇒ "Byte"
        case _ ⇒ "Double"
      }, isOptional, "None")
      case _ : BooleanTypeDeclaration ⇒ ("Boolean", isOptional, "None")
      case _ : DateTypeDeclaration | _: DateTimeTypeDeclaration |
           _: DateTimeOnlyTypeDeclaration | _: TimeOnlyTypeDeclaration ⇒ (options.dateType, isOptional, "None")
      case a : ArrayTypeDeclaration ⇒
        ("Seq[" + mapType(stripArrayEnding(a.`type`())) + "]", isOptional, "None")
      case d: ObjectTypeDeclaration ⇒ d.`type` match {
        case "object"
          if d.properties.size == 1 &&
          d.properties.headOption.exists(_.name == "[")
        ⇒ ("Map[String," + mapType(d.properties.get(0), !d.properties.get(0).required())._1 + "]", isOptional, "None")

        case "object" ⇒ ("com.hypertino.binders.value.Value", false, "com.hypertino.binders.value.Null")
        case t ⇒ (t, isOptional, "None")
      }
      case other ⇒
        log.warn(s"Can't map type $other")
        ("Any", isOptional, "None")
    }
    r match {
      case (s, true, emptyValue) ⇒ (s"Option[$s]", emptyValue)
      case (s, false, emptyValue) ⇒ (s, emptyValue)
    }
  }

  protected def mapType(`type`: String): String = `type` match {
    case "string" ⇒ "String"
    case "integer" ⇒ "Long" // todo: support Int with annotations?
    case "number" ⇒ "Double"
    case "boolean" ⇒ "Boolean"
    case "date" ⇒ options.dateType
    case "object" ⇒ "com.hypertino.binders.value.Value"
    case other ⇒
      api.types.find(_.name == `type`) match {
        case Some(str: StringTypeDeclaration) ⇒
          if (str.enumValues().nonEmpty) {
            other + ".StringEnum" // enum
          }
          else {
            other
          }
        case _ ⇒ other
      }
  }

  protected def stripArrayEnding(s:String): String = {
    if (s.endsWith("[]"))
      s.substring(0,s.length-2)
    else
      s
  }

  protected def getResponseType(code: String) = code match {
    case "200" ⇒ "Ok"
    case "201" => "Created"
    case "202" => "Accepted"
    case "203" => "NonAuthoritativeInformation"
    case "204" => "NoContent"
    case "205" => "ResetContent"
    case "206" => "PartialContent"
    case "207" => "MultiStatus"

    case "300" => "MultipleChoices"
    case "301" => "MovedPermanently"
    case "302" => "Found"
    case "303" => "SeeOther"
    case "304" => "NotModified"
    case "305" => "UseProxy"
    case "307" => "TemporaryRedirect"

    case _ ⇒ "???"
  }
}

