name :="torrent"

scalaVersion in ThisBuild :="2.10.5"

version :="1.0"

scalacOptions ++= Seq("-unchecked", "-deprecation","-feature")

resolvers ++= Seq(
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  "Twitter Repository" at "http://maven.twttr.com/"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.3.12",
  "com.twitter" %% "util-collection" % "6.3.6",
  "com.github.nscala-time" %% "nscala-time" % "0.6.0",
  "com.github.scopt" %% "scopt" % "3.3.0",
  "com.typesafe.akka" % "akka-http-core-experimental_2.10" % "2.0.1",
  "com.typesafe.akka" % "akka-stream-experimental_2.10" % "2.0.1",
  "com.typesafe.akka" % "akka-http-experimental_2.10" % "2.0.1",
  "com.typesafe.akka" % "akka-http-spray-json-experimental_2.10" % "2.0.1"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-testkit" % "2.3.12" % "test",
  "org.mockito" % "mockito-core" % "1.9.5" % "test",
  "org.specs2" %% "specs2" % "2.3.7" % "test"
)
