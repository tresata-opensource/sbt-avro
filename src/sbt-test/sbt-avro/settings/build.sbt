name := "settings-test"
scalaVersion := "2.12.4"

libraryDependencies ++= Seq(
  "org.specs2" %% "specs2-core" % "3.9.4" % "test"
)

(stringType in AvroConfig) := "String"
(fieldVisibility in AvroConfig) := "public"
