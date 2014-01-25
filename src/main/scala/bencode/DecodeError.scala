package org.jerchung.torrent.bencode

class BencodeError(msg:String) extends IllegalArgumentException(msg)
case class DecodeError(msg: String) extends BencodeError(msg)
case class FileFormatError(msg: String) extends BencodeError(msg)