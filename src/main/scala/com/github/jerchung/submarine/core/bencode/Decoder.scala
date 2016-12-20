package com.github.jerchung.submarine.core.bencode

import akka.util.ByteString
import com.github.jerchung.submarine.core.implicits.Convert._

/*
Implemented using protocol described bencode
https://wiki.theory.org/BitTorrentSpecification#Bencoding
*/

//TODO - Make sure tail recursion flag is on

class Decoder {

  def isDigit(num: Byte): Boolean = num >= '0' && num <= '9'

  def decode(input: List[Byte]): Any = {
    decodeInternal(input)._1
  }

  private def decodeInternal(input: List[Byte]): (Any, List[Byte]) = {
    input match {
      case 'd' :: bytes => decodeDictionary(bytes)
      case 'l' :: bytes => decodeList(bytes)
      case 'i' :: bytes => decodeInteger(bytes)
      case d :: bytes if isDigit(d) => decodeByteString(d :: bytes)
      case byte => throw new DecodeError("Invalid Bencode format - got char " +
        s"${byte} when should have gotten d, l, i, or digit")
    }
  }

  def decodeByteString(input: List[Byte]): (ByteString, List[Byte]) = {

    def decodeLength(input: List[Byte], length: Int): (Int, List[Byte]) = {
      input match {
        case ':' :: bytes => (length, bytes)
        case d :: bytes if isDigit(d) => decodeLength(bytes, (length * 10) + (d - 48))
        case _ => throw new DecodeError("Non digit byte before getting ':' termination char")
      }
    }

    val (length, remaining) = decodeLength(input, 0)
    val (stringBytes, tail) = remaining.splitAt(length)
    val bytes = ByteString.fromArray(stringBytes.toArray)
    (bytes, tail)
  }

  def decodeDictionary(
    input: List[Byte],
    result: Map[String, Any] = Map()): (Map[String, Any], List[Byte]) = {
    input match {
      case Nil =>
        throw new DecodeError("No more input before termination char e")
      case 'e' :: tail =>
        (result, tail)
      case _ =>
        val (key, keyRemaining) = decodeByteString(input)
        val (value, remaining) = decodeInternal(keyRemaining)
        decodeDictionary(remaining, result + (key.toChars -> value))
    }
  }

  def decodeList(
    input: List[Byte],
    result: List[Any] = List()): (List[Any], List[Byte]) = {
    input match {
      case Nil =>
        throw DecodeError("No more input before termination char e")
      case 'e' :: bytes =>
        (result.reverse, bytes)
      case _ =>
        val (value, bytes) = decodeInternal(input)
        decodeList(bytes, value :: result)
    }
  }

  def decodeInteger(input: List[Byte], result: Int = 0): (Int, List[Byte]) = {
    input match {
      case 'e' :: bytes => (result, bytes)
      case b :: bytes if isDigit(b) => decodeInteger(bytes, result * 10 + (b - 48))
      case b :: _ => throw DecodeError(s"Hit invalid char $b before termination char 'e'")
      case Nil => throw DecodeError("Hit EOF before termination char 'e' in decoding integer")
    }
  }
}
