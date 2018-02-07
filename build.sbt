name := "sbt-avro-1.7"
organization := "com.cavorite"
description := "Sbt plugin for compiling Avro sources"

version := "1.1.4-SNAPSHOT"

sbtPlugin := true

scalaVersion := "2.12.4"
scalacOptions in Compile ++= Seq("-deprecation")

libraryDependencies ++= Seq(
  "org.apache.avro" % "avro" % "1.7.7",
  "org.apache.avro" % "avro-compiler" % "1.7.7",
  "org.specs2" %% "specs2-core" % "3.9.4" % "test"
)

licenses += ("BSD 3-Clause", url("https://github.com/sbt/sbt-avro/blob/master/LICENSE"))
publishMavenStyle := true
bintrayOrganization := Some("tresata")
bintrayRepository := "maven"
bintrayVcsUrl := Some("git@github.com:tresata-opensource/spark-avro.git")
bintrayPackage := name.value
bintrayReleaseOnPublish := false

scriptedLaunchOpts := { scriptedLaunchOpts.value ++
  Seq("-Xmx1024M", "-Dplugin.name=" + name.value.replace('.', '-'), "-Dplugin.version=" + version.value)
}
scriptedBufferLog := false
