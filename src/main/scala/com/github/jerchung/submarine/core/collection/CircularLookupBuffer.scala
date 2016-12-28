package com.github.jerchung.submarine.core.collection

import scala.util.Random

class CircularLookupBuffer[A](maxSize: Int) {

  val arr: Array[(Int, A)] = new Array[(Int, A)](maxSize)

  // Lookup for the generated random integer, to the actual index it should be at, so we can lookup the element we need
  var lookup: Map[Int, Int] = Map()

  var currentIndex = 0

  def insert(elem: A): Int = {
    currentIndex = increment(currentIndex)

    if (arr(currentIndex) != null) {
      val (prevToken, _) = arr(currentIndex)
      lookup -= prevToken
    }

    val token = Random.nextInt
    arr(currentIndex) = (token, elem)
    lookup += token -> currentIndex
    token
  }

  def foreachFromUntilCurrent(fromToken: Int)(f: A => Unit): Unit = {
  }

  private def increment(i: Int): Int = {
    val incremented = i + 1
    if (incremented > maxSize) 0 else incremented
  }
}
