package lark.meta.source

import lark.meta.base.num.Integer
import lark.meta.base.{names, pretty}
import lark.meta.core
import lark.meta.source.Stream
import lark.meta.source.Stream.SortRepr

import scala.collection.mutable
import scala.reflect.ClassTag

/** Helper class for declaring nodes.
 * The easiest way to declare a node it to define a case class that extends
 * this class, for example:
 *
 * > case class SoFar(e: Stream[Bool], invocation: Node.Invocation) extends Node(invocation):
 * >   val result = output[Bool]
 * >   result := fby(False, result) && e
 *
 */
abstract class Node(invocation: Node.Invocation) extends node.Base(invocation) with node.Sugar:
  val base = this

object Node:
  type Builder    = node.Builder
  type Invocation = node.Invocation