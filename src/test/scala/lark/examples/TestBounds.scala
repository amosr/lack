package lark.examples

import lark.meta.source.Compound.{given, _}
import lark.meta.source.Compound.implicits._
import lark.meta.source.Node
import lark.meta.source.Stream
import lark.meta.source.Stream.{SortRepr, Bool, Int8, Int32}
import lark.meta.driver.Check

class TestBounds extends munit.FunSuite:
  test("bounds") {
    Check.success() { LemmaBounds(3) }
  }

  case class LemmaBounds(n: Int)(invocation: Node.Invocation) extends Node(invocation):
    // Use 32-bit arithmetic on 8-bit values
    val human           = forall[Int8]
    val OVERRIDE        = i32(100)

    val human32         = human.as[Int32]
    val human_in_bounds = human32 < OVERRIDE
    val last_in_bounds  = LastN(n, human_in_bounds)
    val mean            = MeanN(n, human32)
    val mean_in_bounds  = mean < OVERRIDE
    val prop            = last_in_bounds ==> mean_in_bounds

    check("if last_in_bounds then mean_in_bounds") {
      prop
    }

    def LastN(n: Int, e: Stream[Bool]): Stream[Bool] = n match
      case 0 => True
      case 1 => e
      case _ => e && LastN(n - 1, fby(False, e))

    def MeanN(n: Int, v: Stream[Int32]): Stream[Int32] =
      SumN(n, v) / n

    def SumN(n: Int, v: Stream[Int32]): Stream[Int32] = n match
      case 0 => 0
      case 1 => v
      case _ => v + SumN(n - 1, fby(i32(0), v))


  // class Surplus(n: Int, invocation: Node.Invocation) extends Node(invocation):
  //   def MeanN(n: Int, v: Stream[Int32]): Stream[Int32] =
  //     SumN(n, v) / n

  //   def SumN(n: Int, v: Stream[Int32]): Stream[Int32] = n match
  //     case 0 => 0
  //     case 1 => v
  //     case _ => v + SumN(n - 1, fby(0, v))

  //   def LastN(n: Int, e: Stream[Bool]): Stream[Bool] = n match
  //     case 0 => True
  //     case 1 => e
  //     case _ => e && LastN(n - 1, fby(False, e))

  //   val OVERRIDE = 100
  //   val HISTORY  = 2

  //   def SteerSelector(human: Stream[Int32], machine: Stream[Int32]): Stream[Int32] =
  //     val human_filtered = MeanN(HISTORY, human)
  //     select(
  //       when(human_filtered >= OVERRIDE) { human },
  //       otherwise { machine })

  //   val human = i32(1)
  //   val machine = i32(1)
  //   check("if no human override then machine control") {
  //     LastN(HISTORY, human < OVERRIDE) ==> (SteerSelector(human, machine) == machine)
  //   }
  //   check("if no human override then machine control") {
  //     LastN(HISTORY, human < OVERRIDE) ==> MeanN(HISTORY, human) < OVERRIDE
  //   }

  //   check("if no human override then machine control") {
  //     human < OVERRIDE && fby(False, human < OVERRIDE) ==>
  //       ((human + fby(0, human)) / 2 < OVERRIDE)
  //   }

  //   check("bounds-2: if no human override then machine control") {
  //     val human_in_bounds = human < OVERRIDE
  //     val last_fby        = fby(False, human_in_bounds)
  //     val last_in_bounds  = human_in_bounds && last_fby

  //     val mean_fby        = fby(i32(0), human)
  //     val mean_sum        = human + mean_fby
  //     val mean            = mean_sum / 2
  //     val mean_in_bounds  = mean < OVERRIDE

  //     last_in_bounds ==> mean_in_bounds
  //   }

  //   check("bounds-3: if no human override then machine control") {
  //     val human_in_bounds = human < OVERRIDE
  //     val last_fby1       = fby(False, human_in_bounds)
  //     val last_fby2       = fby(False, last_fby1)
  //     val last_in_bounds  = human_in_bounds && last_fby1 && last_fby2
  //     val mean_fby1       = fby(i32(0), human)
  //     val mean_fby2       = fby(i32(0), mean_fby1)
  //     val mean_sum        = human + mean_fby1 + mean_fby2
  //     val mean            = mean_sum / 3
  //     val mean_in_bounds  = mean < OVERRIDE

  //     last_in_bounds ==> mean_in_bounds
  //   }
