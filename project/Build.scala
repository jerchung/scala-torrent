import sbt._
import sbt.Keys._

object TorrentBuild extends Build {

  lazy val root = Project(
    id = "scala-torrent",
    base = file("."))
    .dependsOn(bencode)

  lazy val bencode: Project = Project(
    id = "bencode",
    base = file("bencode")
  )

}