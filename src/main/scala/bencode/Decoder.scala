package org.jerchung.torrent.bencode

import scala.collection
import akka.util.ByteString
import org.jerchung.torrent.Convert._

/*
Implemented using protocol described
for bencode
https://wiki.theory.org/BitTorrentSpecification#Bencoding
*/

//TODO - Make sure tail recursion flag is on

class Decoder {

  //Tests if ascii value is digit
  def isDigit(num: Byte): Boolean = num >= '0' & num <= '9'

  def decode(input: BufferedIterator[Byte]): Any = {
    if (isDigit(input.head)) {
      decodeByteString(input)
    } else {
      val next = input.next
      next match {
        case 'd' => decodeDictionary(input)
        case 'l' => decodeList(input)
        case 'i' => decodeInteger(input)
        case _ => throw new DecodeError("Invalid Bencode format - got char " +
          s"${next} when should have gotten d, l, i, or digit")
      }
    }
  }

  def decodeByteString(input: BufferedIterator[Byte]): ByteString = {

    def getStringLength(length: String = ""): Int = {
      val next = input.next
      next match {
        case ':' => length.toInt
        case _ => getStringLength(length + next.toChar)
      }
    }

    val length = getStringLength()
    ByteString.fromArray(input.take(length).toArray)
  }

  def decodeDictionary(
    input: BufferedIterator[Byte],
    resultMap: Map[String, Any] = Map()): Map[String, Any] = {
    if (!input.hasNext) throw new DecodeError("No more input before termination char e")
    input.head match {
      case 'e' =>
        input.next
        resultMap
      case _ =>
        //Implicit conversion takes care of ByteString -> String
        val key = decode(input).asInstanceOf[ByteString].toChars
        val value = decode(input)
        decodeDictionary(input, resultMap + (key -> value))
    }
  }

  def decodeList(
    input: BufferedIterator[Byte],
    resultList: List[Any] = List()): List[Any] = {
    if (!input.hasNext) throw new DecodeError("No more input before termination char e")
    input.head match {
      case 'e' =>
        input.drop(1)
        //Reverse at end since we've been prepending for runtime reasons
        resultList.reverse
      case _ =>
        val value = decode(input)
        decodeList(input, value :: resultList)
    }
  }

  // Read in string form of integer (Ex. "1393") char by char, then convert to Int
  def decodeInteger(input: BufferedIterator[Byte], resultInt: String = ""): Int = {
    if (!input.hasNext) throw new DecodeError("No more input before termination char e")
    val next = input.next
    next match {
      case 'e' => resultInt.toInt
      case c if (isDigit(c)) => decodeInteger(input, resultInt + c.toChar)
      case _ => throw new DecodeError("Invalid integer bencode input")
    }
  }
}