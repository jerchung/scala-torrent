name :="bencode"

scalaVersion in ThisBuild :="2.10.2"

version :="1.0"

resolvers ++= Seq(
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2.3"
)

libraryDependencies ++= Seq(
  "org.specs2" %% "specs2" % "2.3.6" % "test",
  "org.scalatest" % "scalatest_2.10" % "2.0" % "test"
)