package lack.examples.bug

import lack.meta.source.Compound.{given, _}
import lack.meta.source.Compound.implicits._
import lack.meta.source.Node
import lack.meta.source.Stream
import lack.meta.source.Stream.{SortRepr, Bool, Int32, UInt8}
import lack.meta.driver.Check
import lack.meta.smt.Translate

// Specification bug: surprising behaviour, but sound.
// We get overflows in contexts where the value is never used.
class IntegerOverflow extends munit.FunSuite:
  test("pre: disable overflow check ok") {
    val opt = Check.Options.noRefinement
    Check.success(opt) { new BugPre(_) }
  }

  test("pre: overflow check fails") {
    Check.failure() { new BugPre(_) }
  }

  class BugPre(invocation: Node.Invocation) extends Node(invocation):
    val zeros    = local[Int32]
    val increment = local[Int32]

    // The observable value of zeros is always 0. However, at the first step
    // the value of pre(zeros) is undefined and we increment it by one, then
    // discard that value. If pre(zeros) is 2147483647 then the increment will
    // overflow.
    zeros     := i32(0) -> (pre(zeros) + increment)
    increment := i32(1) -> i32(0)

    // This is proved fine.
    check("zeros = 0") {
      zeros == 0
    }

  test("saturating counter: 254 ok") {
    Check.success() { new BugSaturatingCounter(254, _) }
  }

  test("saturating counter: 255 fails") {
    Check.failure() { new BugSaturatingCounter(255, _) }
  }

  class BugSaturatingCounter(limit: Int, invocation: Node.Invocation) extends Node(invocation):
    val count = local[UInt8]

    val prec = fby(u8(0), count)
    count := ifthenelse(prec < limit, prec + 1, prec)
