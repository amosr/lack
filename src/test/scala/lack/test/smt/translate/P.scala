package lack.test.smt.translate

import lack.meta.base.{names, pretty}
import lack.meta.base.names.given
import lack.meta.core.node
import lack.meta.core.term
import lack.meta.core.term.Compound
import lack.meta.core.term.Eval
import lack.meta.core.term.Exp
import lack.meta.core.term.Val
import lack.meta.core.Sort

import lack.meta.smt.Check
import lack.meta.smt.Solver
import lack.meta.smt.system
import lack.meta.smt.Translate
import lack.meta.smt.Term

import smtlib.trees.{Commands, CommandsResponses, Terms}
import smtlib.trees.Terms.SExpr

import lack.test.hedgehog._
import lack.test.suite._

import scala.collection.immutable.SortedMap

/** SMT translation test: generate expressions and check the SMT semantics against our evaluator */
class P extends HedgehogSuite:
  val g = lack.test.core.term.exp.G(lack.test.core.term.prim.G())

  val solver = Solver.gimme()
  val supply = names.mutable.Supply(List())
  // Translation requires a dummy node. Refactor translation to remove this.
  val dummy  = node.Builder.Node(supply, List())

  def declareConsts(solver: Solver, ns: names.Namespace[Sort], prefix: names.Prefix) =
      solver.declareConsts(
        for
          (k, s) <- ns.refValues(prefix)
        yield
          Terms.SortedVar(Term.compound.qid(k).id.symbol, Term.compound.sort(s))
      )

  def solverEval(heap: Eval.Heap, exp: Exp, options: Translate.Options = Translate.Options()): Val =
    val xctx = Translate.ExpContext(
      Translate.Context(Map.empty, supply, options),
      dummy)
    solver.pushed {
      val heaps = heap.map { case (k0,v0) =>
        for
          x <- Translate.expr(xctx, Exp.Var(v0.sort, k0))
          v  = Term.compound.value(v0)
          eq = Term.compound.equal(x, v)
          _ <- system.SystemV.step(eq)
        yield
          ()
      }
      val sys =
        Translate.expr(xctx, exp) <&&
          system.SystemV.conjoin(heaps.toList)

      declareConsts(solver, sys.system.state, system.Prefix.state)
      declareConsts(solver, sys.system.row, system.Prefix.row)

      solver.assert(sys.system.step)

      solver.checkSat()
      solver.command(Commands.GetModel())
      solver.command(Commands.GetValue(sys.value, Seq())) match
        case CommandsResponses.GetValueResponseSuccess(Seq((_, v))) =>
          Term.take.value(v, exp.sort).get
        case r =>
          throw new Solver.SolverException(r, s"GetValue failed")
    }

  for p <- g.primG.table do
    property(s"primitive '${p.prim.pprString}' SMT matches eval") {
      for
        sorts      <- p.args().ppr("sorts")
        values     <- g.val_.list(sorts).ppr("values")
        evalV      <- Property.ppr(p.prim.eval(values), "evalV")
        e          <- Property.ppr(
          Exp.App(evalV.sort, p.prim, values.map(Compound.val_(_)) : _*),
          "e")

        solverV <- Property.ppr(
          solverEval(SortedMap.empty, e, Translate.Options(checkRefinement = false)),
          "solverV")
      yield
        Result.diff(evalV, solverV)(Val.approx(_, _))
    }

  property("expressions SMT matches eval, ignore overflows") {
    for
      env  <- g.sort.env(Range.linear(1, 10), lack.test.core.sort.G.all).ppr("env")
      s    <- g.sort.all.ppr("sort")
      e    <- g.raw(env, s).ppr("e")
      heap <- g.val_.heap(env).ppr("heap")

      evalV <- Property.ppr(
        Eval.exp(heap, e, Eval.Options(checkRefinement = false)),
        "evalV")

      solverV <- Property.ppr(
        solverEval(heap, e, Translate.Options(checkRefinement = false)),
        "solverV")
    yield
      Result.diff(evalV, solverV)(Val.approx(_, _))
  }

  property("expressions SMT matches eval, discard overflows") {
    for
      env  <- g.sort.env(Range.linear(1, 1), lack.test.core.sort.G.all).ppr("env")
      s    <- g.sort.all.ppr("sort")
      e    <- g.raw(env, s).small.small.small.small.small.small.ppr("e")
      heap <- g.val_.heap(env).ppr("heap")

      // Check that entire expression evaluates with no overflows; discard otherwise
      evalV <- Property.try_ {
        Eval.exp(heap, e, Eval.Options(checkRefinement = true))
      }.ppr("evalV")

      solverV <- Property.ppr(
        solverEval(heap, e, Translate.Options(checkRefinement = true)),
        "solverV")
    yield
      Result.diff(evalV, solverV)(Val.approx(_, _))
  }
