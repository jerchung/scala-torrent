package org.jerchung.torrent

import org.jerchung.bencode._
import org.jerchung.torrent.Convert._
import akka.util.ByteString
import scala.io.Source
import java.util.Date
import java.security.MessageDigest

sealed trait FileMode
case object Single extends FileMode
case object Multiple extends FileMode

case class TorrentFile(length: Int, path: String = "")

object Torrent {

  val Charset = "ISO-8859-1"
  val HashAlgo = "SHA-1"

  // ByteString converted to String as needed through implicit conversion
  def apply(torrent: Map[String, Any]): Torrent = {
    new Torrent(
      announce = torrent("announce").asInstanceOf[ByteString].toChars,
      info = torrent("info").asInstanceOf[Map[String, Any]],
      creationDate = torrent.get("creation date").map { d => new Date(d.asInstanceOf[Int] * 1000.toLong) },
      comment = torrent.get("comment").map(_.asInstanceOf[ByteString].toChars),
      createdBy = torrent.get("created by").map(_.asInstanceOf[ByteString].toChars),
      encoding = torrent.get("encoding").map(_.asInstanceOf[ByteString].toChars)
    )
  }

  def fromFile(filename: String): Torrent = {
    val source = Source.fromFile(filename, Charset)
    val input: BufferedIterator[Byte] = source.map(_.toByte).toIterator.buffered
    val metaInfo = Bencode.decode(input)
    source.close
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
  val infoBytes: Array[Byte] = Bencode.encode(info).getBytes

  // SHA-1 hash of the info_hash dictionary needed for handshake
  val infoHash = ByteString.fromArray(sha1(infoBytes))

  // Provided SHA-1 hash of all pieces concatenated together
  val piecesHash = info("pieces").asInstanceOf[ByteString]

  // Number of bytes in each piece
  val pieceSize = info("pieces length").asInstanceOf[Int]

  // Torrent can have single or multiple files
  val fileMode: FileMode = if (info contains "files") Multiple else Single

  /**
   * Can be either the name of the file (Single) or directory for files
   * (Mutiple).  Gets implicitly converted to a string
   */
  val name: String = info("name").asInstanceOf[ByteString].toChars

  val files: List[TorrentFile] = fileMode match {
    case Single =>
      List(TorrentFile(info("length").asInstanceOf[Int], name))
    case Multiple =>
      getMultipleFiles
  }

  // Total number of pieces
  val numPieces = piecesHash.length / 20

  // Total size of all files in bytes
  val totalSize = files.foldLeft(0){ (size, file) => size + file.length }

  if (math.ceil(totalSize.toFloat / pieceSize) != numPieces) {
    throw new TorrentError("Bencode piece sizes and totalSize do not agree")
  }

  private lazy val getMultipleFiles: List[TorrentFile] = {
    val files = info("files").asInstanceOf[List[Map[String, Any]]]
    files map { f =>
      TorrentFile(
        f("length").asInstanceOf[Int],
        f("path").asInstanceOf[ByteString].toChars
      )
    }
  }

  def sha1(bytes: Array[Byte]): Array[Byte] = {
    MessageDigest.getInstance(Torrent.HashAlgo).digest(bytes)
  }

}