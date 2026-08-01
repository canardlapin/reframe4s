package reframe4s.halfflow.internal

import scala.reflect.ClassTag

object PrimitiveBuffers:

  def ofSize[A](n: Int)(using ClassTag[A]): Array[A] =
    Array.ofDim[A](n)

  def fromArray[A](arr: Array[A]): Array[A] =
    arr.clone()

  def fill[A](n: Int)(elem: => A)(using ClassTag[A]): Array[A] =
    val out = Array.ofDim[A](n)
    var i = 0
    while i < n do
      out(i) = elem
      i += 1
    out

  def fillConst[A](n: Int, elem: A)(using ClassTag[A]): Array[A] =
    val out = Array.ofDim[A](n)
    var i = 0
    while i < n do
      out(i) = elem
      i += 1
    out

  def tabulate[A](n: Int)(f: Int => A)(using ClassTag[A]): Array[A] =
    val out = Array.ofDim[A](n)
    var i = 0
    while i < n do
      out(i) = f(i)
      i += 1
    out

  def copyInto[A](src: Array[A], srcPos: Int, dest: Array[A], destPos: Int, length: Int): Unit =
    var i = 0
    while i < length do
      dest(destPos + i) = src(srcPos + i)
      i += 1
