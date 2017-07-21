package com.hypertino.hyperbus.raml

import java.io.{File, FileNotFoundException}

import org.raml.v2.api.RamlModelBuilder
import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin

import scala.collection.JavaConversions

object Raml2Hyperbus extends AutoPlugin {
  override def requires = JvmPlugin
  override def trigger = allRequirements
  object autoImport {
    val ramlHyperbusSources = settingKey[Seq[RamlSource]]("ramlHyperbusSources")
    def ramlSource(
                    path: String, packageName: String, isResource: Boolean = false, contentTypePrefix: Option[String] = None
                  ): RamlSource = RamlSource(path, packageName, isResource, contentTypePrefix)
  }

  import autoImport._

  override val projectSettings =
    ramlHyperbusScopedSettings(Compile) //++ ramlHyperbusScopedSettings(Test) ++*/ //r2hDefaultSettings

  protected def ramlHyperbusScopedSettings(conf: Configuration): Seq[Def.Setting[_]] = inConfig(conf)(Seq(
    sourceGenerators in conf +=  Def.task {
      ramlHyperbusSources.value.map { source ⇒
        generateFromRaml(resourceDirectory.value, new File(source.path), source.isResource, sourceManaged.value,
          source.packageName, source.contentTypePrefix)
      }
    }.taskValue
  ))

  protected def generateFromRaml(resourceDirectory: File,
                                 source: File,
                                 sourceIsResource: Boolean,
                                 base: File, packageName: String,
                                 contentPrefix: Option[String]): File = {

    val outputFile = base / "r2h" / (packageName.split('.').mkString("/") + "/" + source.getName + ".scala")
    val apiFile = if (sourceIsResource) {
      resourceDirectory / source.getPath
    } else {
      source
    }
    if (!outputFile.canRead || outputFile.lastModified() < apiFile.lastModified()) {
      if (!apiFile.exists()) {
        throw new FileNotFoundException(s"File ${apiFile.getAbsolutePath} doesn't exists")
      }

      val api = new RamlModelBuilder().buildApi(apiFile)
      val ramlApi = api.getApiV10
      if (ramlApi == null) {
        import JavaConversions._
        val validationErrors = api.getValidationResults.mkString(System.lineSeparator())
        throw new RuntimeException(s"RAML parser errors for '${apiFile.getAbsolutePath}':${System.lineSeparator()} $validationErrors")
      }
      val generator = new InterfaceGenerator(ramlApi, GeneratorOptions(packageName, contentPrefix))
      IO.write(outputFile, generator.generate())
    }
    outputFile
  }
}
