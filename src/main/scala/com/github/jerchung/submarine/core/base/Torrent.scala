package com.github.jerchung.submarine.core.base

import java.io.File
import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import java.util.Date

import akka.util.ByteString
import com.github.jerchung.submarine.core.bencode.Bencode
import com.github.jerchung.submarine.core.implicits.Convert._

object Torrent {

  sealed trait FileMode
  object FileMode {
    case object Single extends FileMode
    case object Multiple extends FileMode
  }

  case class File(size: Int, path: String)

  val Charset = "ISO-8859-1"
  val HashAlgo = "SHA-1"

  // ByteString converted to String as needed through implicit conversion
  def apply(torrent: Map[String, Any]): Torrent = {
    new Torrent(
      announce = torrent("announce").asInstanceOf[ByteString].toChars,
      info = torrent("info").asInstanceOf[Map[String, Any]],
      creationDate = torrent.get("creation date").map { d =>
        new Date(d.asInstanceOf[Int] * 1000.toLong)
      },
      comment = torrent.get("comment").map(_.asInstanceOf[ByteString].toChars),
      createdBy = torrent.get("created by").map(_.asInstanceOf[ByteString].toChars),
      encoding = torrent.get("encoding").map(_.asInstanceOf[ByteString].toChars)
    )
  }

  def fromFile(filename: String): Torrent = {
    val bytes = Files.readAllBytes(Paths.get(filename))
    val input: List[Byte] = bytes.toList
    val metaInfo = Bencode.decode(input)
    Torrent(metaInfo.asInstanceOf[Map[String, Any]])
  }

}

class Torrent(
    val announce: String,
    val info: Map[String, Any],
    val creationDate: Option[Date],
    val comment: Option[String],
    val createdBy: Option[String],
    val encoding: Option[String]) {

  // Byte form of the string of the info_hash dictionary
  val infoBytes: Array[Byte] = Bencode.encode(info)

  // SHA-1 hash of the info_hash dictionary needed for handshake
  val infoHash: Array[Byte] = sha1(infoBytes)

  // Provided SHA-1 hash of all pieces concatenated together
  val piecesHash = info("pieces").asInstanceOf[ByteString]

  val indexedPieceHashes: Array[ByteString] = piecesHash
    .grouped(20)
    .toArray

  // Number of bytes in each piece
  val pieceSize = info("piece length").asInstanceOf[Int]

  // Torrent can have single or multiple files
  val fileMode: Torrent.FileMode = if (info.contains("files"))
    Torrent.FileMode.Multiple
  else
    Torrent.FileMode.Single

  /**
   * Can be either the name of the com.github.jerchung.submarine.core.file (Torrent.Single) or directory for files
   * (Multiple).  Gets implicitly converted to a string
   */
  val name: String = info("name").asInstanceOf[ByteString].toChars

  val files: List[Torrent.File] = fileMode match {
    case Torrent.FileMode.Single => List(Torrent.File(info("length").asInstanceOf[Int], name))
    case Torrent.FileMode.Multiple => getMultipleFiles
  }

  // Total number of pieces
  val numPieces: Int = piecesHash.length / 20

  // Total size of all files in bytes
  val totalSize: Int = files.foldLeft(0){ (size, file) => size + file.size }

  if (math.ceil(totalSize.toFloat / pieceSize) != numPieces) {
    throw new TorrentError("Bencode piece sizes and totalSize do not agree")
  }

  private lazy val getMultipleFiles: List[Torrent.File] = {
    val files = info("files").asInstanceOf[List[Map[String, Any]]]
    files map { f =>
      Torrent.File(
        f("length").asInstanceOf[Int],
        f("path")
          .asInstanceOf[List[ByteString]]
          .map { s => s.toChars}
          .mkString(File.separator)
      )
    }
  }

  def sha1(bytes: Array[Byte]): Array[Byte] = {
    MessageDigest.getInstance(Torrent.HashAlgo).digest(bytes)
  }

}
