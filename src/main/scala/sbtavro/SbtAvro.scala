package sbtavro

import java.io.File

import scala.collection.mutable
import scala.io.Source

import org.apache.avro.{Protocol, Schema}
import org.apache.avro.compiler.idl.Idl
import org.apache.avro.compiler.specific.SpecificCompiler
import org.apache.avro.compiler.specific.SpecificCompiler.FieldVisibility
import org.apache.avro.generic.GenericData.StringType

import sbt._
import sbt.ConfigKey.configurationToKey
import sbt.Keys.{classpathTypes, cleanFiles, ivyConfigurations, javaSource, libraryDependencies, managedClasspath, managedSourceDirectories, sourceDirectory, sourceGenerators, sourceManaged, streams, update, version}

/**
 * Simple plugin for generating the Java sources for Avro schemas and protocols.
 */
object SbtAvro extends AutoPlugin {

  object autoImport {

    val AvroConfig = config("avro")

    val stringType = SettingKey[String]("string-type", "Type for representing strings. " +
      "Possible values: CharSequence, String, Utf8. Default: CharSequence.")

    val fieldVisibility = SettingKey[String]("field-visibiliy", "Field Visibility for the properties" +
      "Possible values: private, public, public_deprecated. Default: public_deprecated.")

    val generate = TaskKey[Seq[File]]("generate", "Generate the Java sources for the Avro files.")

    lazy val avroSettings: Seq[Setting[_]] = inConfig(AvroConfig)(Seq[Setting[_]](
      sourceDirectory := (sourceDirectory in Compile).value / "avro",
      javaSource := (sourceManaged in Compile).value / "compiled_avro",
      stringType := "CharSequence",
      fieldVisibility := "public_deprecated",
      version := "1.7.7",

      managedClasspath := {
        Classpaths.managedJars(AvroConfig, classpathTypes.value, update.value)
      },
      generate := sourceGeneratorTask.value)
    ) ++ Seq[Setting[_]](
      sourceGenerators in Compile += (generate in AvroConfig).taskValue,
      managedSourceDirectories in Compile += (javaSource in AvroConfig).value,
      cleanFiles += (javaSource in AvroConfig).value,
      libraryDependencies += "org.apache.avro" % "avro-compiler" % (version in AvroConfig).value,
      ivyConfigurations += AvroConfig
    )
  }

  import autoImport._
  override def requires = sbt.plugins.JvmPlugin

  // This plugin is automatically enabled for projects which are JvmPlugin.
  override def trigger = allRequirements

  // a group of settings that are automatically added to projects.
  override val projectSettings = avroSettings

  def compileIdl(idl: File, target: File, stringType: StringType, fieldVisibility: FieldVisibility) {
    val parser = new Idl(idl)
    val protocol = Protocol.parse(parser.CompilationUnit.toString)
    val compiler = new SpecificCompiler(protocol)
    compiler.setStringType(stringType)
    compiler.setFieldVisibility(fieldVisibility)
    compiler.compileToDestination(null, target)
  }

  private lazy val schemaParser = new Schema.Parser()

  def compileAvsc(avsc: File, target: File, stringType: StringType, fieldVisibility: FieldVisibility) {
    val schema = schemaParser.parse(avsc)
    val compiler = new SpecificCompiler(schema)
    compiler.setStringType(stringType)
    compiler.setFieldVisibility(fieldVisibility)
    compiler.compileToDestination(null, target)
  }

  def compileAvpr(avpr: File, target: File, stringType: StringType, fieldVisibility: FieldVisibility) {
    val protocol = Protocol.parse(avpr)
    val compiler = new SpecificCompiler(protocol)
    compiler.setStringType(stringType)
    compiler.setFieldVisibility(fieldVisibility)
    compiler.compileToDestination(null, target)
  }

  private[this] def compile(srcDir: File, target: File, log: Logger, stringTypeName: String, fieldVisibilityName: String): Set[File] = {
    val stringType = StringType.valueOf(stringTypeName)
    val fieldVisibility = SpecificCompiler.FieldVisibility.valueOf(fieldVisibilityName.toUpperCase)
    log.info("Avro compiler using stringType=%s".format(stringType))

    for (idl <- (srcDir ** "*.avdl").get) {
      log.info("Compiling Avro IDL %s".format(idl))
      compileIdl(idl, target, stringType, fieldVisibility)
    }

    for (avsc <- sortSchemaFiles((srcDir ** "*.avsc").get)) {
      log.info("Compiling Avro schema %s".format(avsc))
      compileAvsc(avsc, target, stringType, fieldVisibility)
    }

    for (avpr <- (srcDir ** "*.avpr").get) {
      log.info("Compiling Avro protocol %s".format(avpr))
      compileAvpr(avpr, target, stringType, fieldVisibility)
    }

    (target ** "*.java").get.toSet
  }

  private def sourceGeneratorTask = Def.task {
    val out = streams.value
    val srcDir = (sourceDirectory in AvroConfig).value
    val javaSrc = (javaSource in AvroConfig).value
    val strType = stringType.value
    val fieldVis = fieldVisibility.value
    val cachedCompile = FileFunction.cached(out.cacheDirectory / "avro",
      inStyle = FilesInfo.lastModified,
      outStyle = FilesInfo.exists) { (in: Set[File]) =>
        compile(srcDir, javaSrc, out.log, strType, fieldVis)
      }
    cachedCompile((srcDir ** "*.av*").get.toSet).toSeq
  }

  def sortSchemaFiles(files: Traversable[File]): Seq[File] = {
    val reversed = mutable.MutableList.empty[File]
    var used: Traversable[File] = files
    while(!used.isEmpty) {
      val usedUnused = usedUnusedSchemas(used)
      reversed ++= usedUnused._2
      used = usedUnused._1
    }
    reversed.reverse.toSeq
  }

  def strContainsType(str: String, fullName: String): Boolean = {
    val typeRegex = "\\\"type\\\"\\s*:\\s*(\\\"" + fullName + "\\\")|(\\[[^\\]]*\\\"" + fullName + "\\\"\\])"
    typeRegex.r.findFirstIn(str).isDefined
  }

  def usedUnusedSchemas(files: Traversable[File]): (Traversable[File], Traversable[File]) = {
    val usedUnused = files.map { f =>
      val fullName = extractFullName(f)
      (f, files.count { candidate =>
        strContainsType(fileText(candidate), fullName)
      } )
    }.partition(_._2 > 0)
    (usedUnused._1.map(_._1), usedUnused._2.map(_._1))
  }

  def extractFullName(f: File): String = {
    val txt = fileText(f)
    val namespace = namespaceRegex.findFirstMatchIn(txt)
    val name = nameRegex.findFirstMatchIn(txt)
    if(namespace == None) {
      return name.get.group(1)
    } else {
      return s"${namespace.get.group(1)}.${name.get.group(1)}"
    }
  }

  def fileText(f: File): String = {
    val src = Source.fromFile(f)
    try {
      return src.getLines.mkString
    } finally {
      src.close()
    }
  }

  val namespaceRegex = "\\\"namespace\\\"\\s*:\\s*\"([^\\\"]+)\\\"".r
  val nameRegex = "\\\"name\\\"\\s*:\\s*\"([^\\\"]+)\\\"".r

}
