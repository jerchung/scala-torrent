package com.github.jerchung.submarine.core.bencode

import akka.util.ByteString

import scala.collection.mutable

class Encoder {

  def encode(message: Any): Array[Byte] = {
    message match {
      case i: Int => encodeInt(i)
      case s: ByteString => encodeString(s)
      case m: Map[_,_] => encodeMap(m.asInstanceOf[Map[String, Any]])
      case l: List[_] => encodeList(l)
      case s: String => encodeString(ByteString.fromString(s))
      case _ => throw new Exception()
    }
  }

  def encodeInt(value: Int): Array[Byte] = {
    'i'.toByte +: value.toString.getBytes :+ 'e'.toByte
  }

  def encodeString(value: ByteString): Array[Byte] = {
    val buffer = mutable.ArrayBuffer[Byte]()
    buffer ++= value.length.toString.getBytes
    buffer += ':'
    buffer ++= value.toArray
    buffer.toArray
  }

  def encodeMap(value: Map[String, Any]): Array[Byte] = {
    val buffer = mutable.ArrayBuffer[Byte]()
    buffer += 'd'
    // Map keys must be in alphabetical order
    value.toList.sortWith((a, b) => a._1 < b._1).foreach { case (k, v) =>
      buffer ++= encode(k)
      buffer ++= encode(v)
    }
    buffer += 'e'
    buffer.toArray
  }

  def encodeList(value: List[Any]): Array[Byte] = {
    'l'.toByte +: value.foldLeft(mutable.ArrayBuffer[Byte]()) {
      (bytes, v) => bytes ++= encode(v)
    }.toArray :+ 'e'.toByte
  }
}
