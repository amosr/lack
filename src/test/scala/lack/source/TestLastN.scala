package lack.source

import lack.meta.base.num.Integer
import lack.meta.source.compound.{given, _}
import lack.meta.source.compound.implicits._
import lack.meta.source.stream.{Stream, SortRepr, Bool, UInt8}
import lack.meta.source.stream
import lack.meta.source.node.{Builder, Node, NodeInvocation}
import lack.meta.driver.check

class TestLastN extends munit.FunSuite:
  test("lastN") {
    check.success() { new LemmaLastN(3, _) }
  }

  class LemmaLastN(n: Integer, invocation: NodeInvocation) extends Node(invocation):
    val e      = local[Bool]
    val lastN  = LastN(n,     e)
    val lastSN = LastN(n + 1, e)
    check("invariant LastN(n, e).count <= LastN(n + 1, e).count <= LastN(n, e).count + 1") {
      lastN.count <= lastSN.count && lastSN.count <= lastN.count + 1
    }
    check("forall n e. LastN(n + 1, e) ==> LastN(n, e)") {
      lastSN.out ==> lastN.out
    }

  class LastN(n: Integer, e: Stream[Bool], invocation: NodeInvocation) extends Node(invocation):
    require(n <= 255)

    val count     = local[UInt8]
    val out       = output[Bool]
    val pre_count = u8(0) -> pre(count)

    count := cond(
      when(e && pre_count <  n) { pre_count + 1 },
      when(e && pre_count >= n) { n },
      otherwise                 { 0 }
    )

    val chk = out   := count >= n

    check("0 <= count <= n") {
      u8(0) <= count && count <= n
    }

    // check("count <= ${n - 1} (not true!)") {
    //   count <= (n - 1)
    // }

  object LastN:
    def apply(n: Integer, e: Stream[stream.Bool])(using builder: Builder, location: lack.meta.macros.Location) =
      builder.invoke { invocation =>
        invocation.metaarg("n", n)
        new LastN(n, invocation.arg("e", e), invocation)
      }
