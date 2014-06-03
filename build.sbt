name :="torrent"

scalaVersion in ThisBuild :="2.10.2"

version :="1.0"

scalacOptions ++= Seq("-unchecked", "-deprecation","-feature")

resolvers ++= Seq(
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  "Twitter Repository" at "http://maven.twttr.com/"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2.3",
  "com.twitter" %% "util-collection" % "6.3.6",
  "com.github.nscala-time" %% "nscala-time" % "0.6.0",
  "net.databinder.dispatch" %% "dispatch-core" % "0.11.0",
  "com.escalatesoft.subcut" %% "subcut" % "2.0"
)

libraryDependencies ++= Seq(
  "org.scalatest" % "scalatest_2.10" % "2.0" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.3" % "test",
  "org.mockito" % "mockito-core" % "1.9.5" % "test",
  "org.specs2" %% "specs2" % "2.3.7" % "test"
)