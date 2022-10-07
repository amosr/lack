package lack.test.core.target.c.exp

import lack.meta.base.{names, pretty}
import lack.meta.base.names.given
import lack.meta.core.term
import lack.meta.core.term.Check
import lack.meta.core.term.Eval
import lack.meta.core.term.Exp
import lack.meta.core.term.Val
import lack.meta.core.Sort

import lack.meta.core.target.c.{Printer => Pr}
import lack.meta.core.target.C

import lack.test.hedgehog._
import lack.test.suite._

import lack.test.core.target.c.Cbmc

import scala.collection.immutable.SortedMap

/** C code gen test: generate expressions and check C code against our evaluator */
class P extends HedgehogSuite:
  val g = lack.test.core.term.exp.G(lack.test.core.term.prim.G())

  property("raw expressions eval OK (refines enabled & discarded)", grind = Some(1)) {
    for
      env  <- g.sort.env(Range.linear(1, 10), lack.test.core.sort.G.runtime.all).ppr("env")
      s    <- g.sort.runtime.all.ppr("sort")

      e    <- g.raw(env, s).ppr("e")

      heap <- g.val_.heap(env).ppr("heap")

      v <- Property.try_ {
        Eval.exp(heap, e, Eval.Options(checkRefinement = true))
      }.ppr("v")

      self = pretty.text("NO_SELF_ACCESS")
      binds <- Property.try_ {
        for (k, v) <- heap
        yield Pr.Type.sort(v.sort) <+> Pr.Ident.component(k.name) <+> pretty.equal <+> C.Source.val_(v) <> pretty.semi
      }
      asserts <- Property.try_ {
        for
          (e,i) <- List(e).zipWithIndex
          vi = pretty.text("$$") <> pretty.value(i)
          expect =
            s match
              // Use approximate equality for floats. This only makes sense for
              // continuous expressions but hopefully expressions with
              // branching won't be too near the threshold.
              case Sort.Real =>
                Pr.Term.fun("lack_float_approx", List(vi, C.Source.val_(v)))
              case _ =>
                vi <+> pretty.text("==") <+> C.Source.val_(v)
        yield
          Pr.Type.sort(s) <+> vi <+> pretty.equal <+>
            C.Source.exp(self, e) <> pretty.semi <@>
            Pr.Term.fun("assert", List(expect)) <> pretty.semi
      }

      code <- Property.ppr(pretty.vsep(
        binds.toList ++ List(pretty.emptyDoc) ++ asserts.toList
      ), "code")
    yield
      checkMain(code)
  }

  test("example") {
    check("int main() { assert(true); }")
  }

  def checkMain(stms: pretty.Doc) =
    check(
      pretty.text("int main() {") <@>
        pretty.indent(stms) <@>
      pretty.text("}"))

  def check(code: pretty.Doc) =
    val options =
      C.Options(basename = "example", classes = SortedMap.empty)
    val doc =
      C.prelude(options) <@>
      code
    Cbmc.check(doc)
