name :="torrent"

scalaVersion in ThisBuild :="2.11.8"

version :="1.0"

scalacOptions ++= Seq("-unchecked", "-deprecation","-feature")

resolvers ++= Seq(
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  "Twitter Repository" at "http://maven.twttr.com/"
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.14",
  "com.typesafe.akka" %% "akka-http-core" % "10.0.0",
  "com.typesafe.akka" %% "akka-http" % "10.0.0",
  "com.twitter" %% "util-collection" % "6.35.0",
  "com.github.scopt" %% "scopt" % "3.5.0",
  "com.typesafe" % "config" % "1.3.1"
)

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.0.1" % "test",
  "org.mockito" % "mockito-core" % "2.4.1" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.14" % "test"
)
