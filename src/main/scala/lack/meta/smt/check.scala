package lack.meta.smt

import lack.meta.base.names
import lack.meta.base.pretty
import lack.meta.core.builder
import lack.meta.core.builder.Node
import lack.meta.core.sort.Sort
import lack.meta.core.term.{Exp, Prim, Val}

import lack.meta.smt.solver.Solver
import lack.meta.smt.solver.compound
import smtlib.trees.{Commands, CommandsResponses, Terms}
import smtlib.trees.Terms.SExpr

object check:
  sealed trait CheckFeasible
  object CheckFeasible:
    case class FeasibleUpTo(steps: Int) extends CheckFeasible
    case class InfeasibleAt(steps: Int) extends CheckFeasible
    case class UnknownAt(steps: Int) extends CheckFeasible


  sealed trait Bmc extends pretty.Pretty
  object Bmc:
    case class SafeUpTo(steps: Int) extends Bmc:
      def ppr = pretty.text(s"Safe for at least ${steps} steps")
    case class CounterexampleAt(steps: Int, trace: Trace) extends Bmc:
      def ppr = pretty.text("Counterexample:") <@> trace.ppr
    case class UnknownAt(steps: Int) extends Bmc:
      def ppr = pretty.text(s"Unknown (at step ${steps})")


  sealed trait Kind
  object Kind:
    case class InvariantMaintainedAt(steps: Int) extends Kind
    case class NoGood(steps: Int) extends Kind
    case class UnknownAt(steps: Int) extends Kind

  case class Trace(steps: List[Trace.Row]) extends pretty.Pretty:
    def ppr = pretty.indent(pretty.vsep(steps.map(_.ppr)))
  object Trace:
    // HACK TODO not strings
    case class Row(values: List[(String, String)]) extends pretty.Pretty:
      def ppr = pretty.braces(pretty.csep(values.map((k,v) => pretty.text(k) <+> pretty.equal <+> pretty.text(v))))
    // HACK TODO take node and filter out generated bindings
    def fromModel(steps: Int, sexpr: SExpr): Trace =
      def allDefs(s: SExpr): Iterable[(Terms.SSymbol, SExpr)] = s match
        case CommandsResponses.GetModelResponseSuccess(m) =>
          m.flatMap(allDefs)
        case Commands.DefineFun(fd) =>
          if (fd.params.isEmpty) {
            Seq((fd.name, fd.body))
          } else {
            Seq()
          }
        case _ =>
          throw new solver.SolverException(s, "can't parse model counterexample")

      val ds = allDefs(sexpr)

      val stepD = for (i <- 0 to steps) yield {
        val stepI = s"row?${i}."
        val dsI = ds.filter((k,v) => k.name.startsWith(stepI))
        val dsK = dsI.map((k,v) => (k.name.drop(stepI.length), v.toString))
        val dsF = dsK.filter((k,v) => !k.contains("?"))
        val dsS = dsF.toArray.sortBy(_._1).toList
        Row(dsS)
      }

      Trace(stepD.toList)


  def declareSystem(n: Node, solver: Solver): system.SolverSystem =
    val sys = system.translate.nodes(n.allNodes)
    sys.fundefs.foreach(solver.command)
    sys

  def stepPrefix(pfx: String, i: Int) = names.Prefix(List(names.Component(names.ComponentSymbol.fromScalaSymbol(pfx), Some(i))))
  def statePrefix(i: Int) = stepPrefix("state", i)
  def rowPrefix(i: Int) = stepPrefix("row", i)
  def initOraclePrefix = stepPrefix("init-oracle", 0)
  def stepOraclePrefix(i: Int) = stepPrefix("step-oracle", i)

  def callSystemFun(fun: system.SolverFunDef, argVars: List[Terms.SortedVar], oraclePrefix: names.Prefix, solver: Solver): Unit =
    val argsV = argVars
    val argsT = argsV.map { v => Terms.QualifiedIdentifier(Terms.Identifier(v.name)) }
    val call = Terms.FunctionApplication(fun.name, argsT)
    solver.assert(call)

  def asserts(props: List[system.SolverJudgment], row: names.Prefix, solver: Solver): Unit =
    props.foreach { prop =>
      solver.assert( compound.qid(row(prop.row)) )
    }

  def disprove(props: List[system.SolverJudgment], row: names.Prefix): Terms.Term =
    val propsT = props.map(p => compound.funapp("not", compound.qid(row(p.row))))
    compound.or(propsT : _*)

  def checkMany(top: Node, count: Int, solver: () => Solver): Unit =
    println("Checking top-level node:")
    println(top.pprString)
    println("System translation:")
    println(system.translate.nodes(top.allNodes).pprString)

    top.allNodes.foreach { n =>
      val s = solver()
      checkNode(n, count, s)
    }

  def checkNode(top: Node, count: Int, solver: Solver): Unit =
    val sys  = declareSystem(top, solver)
    val topS = sys.top
    val feaR = solver.pushed { feasible(sys, topS, count, solver) }
    val bmcR = solver.pushed { bmc(sys, topS, count, solver) }
    val indR = solver.pushed { kind(sys, topS, count, solver) }
    // LODO fix up pretty-printing
    println(s"Node ${names.Prefix(top.path).pprString}:")
    topS.assumptions.foreach { o =>
      println(s"  Assume ${o.judgment.pprString}")
    }
    topS.obligations.foreach { o =>
      println(s"  Show ${o.judgment.pprString}")
    }
    println(s"  Feasibility check:   ${feaR}")
    println(s"  Bounded model check: ${bmcR.pprString}")
    println(s"  K-inductive check:   ${indR}")

  def feasible(sys: system.SolverSystem, top: system.SolverNode, count: Int, solver: Solver): CheckFeasible =
    {
      val state = top.paramsOfNamespace(statePrefix(0), top.state)
      solver.declareConsts(state)
      callSystemFun(top.init, state, initOraclePrefix, solver)
    }

    solver.checkSat().status match
      case CommandsResponses.UnknownStatus => return CheckFeasible.UnknownAt(-1)
      case CommandsResponses.SatStatus     =>
      case CommandsResponses.UnsatStatus   => return CheckFeasible.InfeasibleAt(-1)

    for (step <- 0 until count) {
      val state  = top.paramsOfNamespace(statePrefix(step), top.state)
      val stateS = top.paramsOfNamespace(statePrefix(step + 1), top.state)
      val row    = top.paramsOfNamespace(rowPrefix(step), top.row)

      solver.declareConsts(row ++ stateS)

      callSystemFun(top.step, state ++ row ++ stateS, stepOraclePrefix(step), solver)

      asserts(top.assumptions, rowPrefix(step), solver)

      solver.checkSat().status match
        case CommandsResponses.UnknownStatus => return CheckFeasible.UnknownAt(step)
        case CommandsResponses.SatStatus     =>
        case CommandsResponses.UnsatStatus   => return CheckFeasible.InfeasibleAt(step)
    }

    CheckFeasible.FeasibleUpTo(count)


  def bmc(sys: system.SolverSystem, top: system.SolverNode, count: Int, solver: Solver): Bmc =
    {
      val state = top.paramsOfNamespace(statePrefix(0), top.state)
      solver.declareConsts(state)
      callSystemFun(top.init, state, initOraclePrefix, solver)
    }

    for (step <- 0 until count) {
      val state  = top.paramsOfNamespace(statePrefix(step), top.state)
      val stateS = top.paramsOfNamespace(statePrefix(step + 1), top.state)
      val row    = top.paramsOfNamespace(rowPrefix(step), top.row)

      solver.declareConsts(row ++ stateS)

      callSystemFun(top.step, state ++ row ++ stateS, stepOraclePrefix(step), solver)

      asserts(top.assumptions, rowPrefix(step), solver)

      solver.checkSatAssumingX(disprove(top.obligations, rowPrefix(step))) { _.status match
        case CommandsResponses.UnknownStatus => return Bmc.UnknownAt(step)
        case CommandsResponses.SatStatus     =>
          val model = solver.command(Commands.GetModel())
          return Bmc.CounterexampleAt(step, Trace.fromModel(step, model))
        case CommandsResponses.UnsatStatus   =>
      }
    }

    Bmc.SafeUpTo(count)


  def kind(sys: system.SolverSystem, top: system.SolverNode, count: Int, solver: Solver): Kind =
    {
      val state = top.paramsOfNamespace(statePrefix(0), top.state)
      solver.declareConsts(state)
    }

    for (step <- 0 until count) {
      val state  = top.paramsOfNamespace(statePrefix(step), top.state)
      val stateS = top.paramsOfNamespace(statePrefix(step + 1), top.state)
      val row    = top.paramsOfNamespace(rowPrefix(step), top.row)

      solver.declareConsts(row ++ stateS)

      callSystemFun(top.step, state ++ row ++ stateS, stepOraclePrefix(step), solver)

      asserts(top.assumptions, rowPrefix(step), solver)

      solver.checkSatAssumingX(disprove(top.obligations, rowPrefix(step))) { _.status match
        case CommandsResponses.UnknownStatus => return Kind.UnknownAt(step)
        case CommandsResponses.SatStatus     =>
          val model = solver.command(Commands.GetModel())
          val cti = Trace.fromModel(step, model)
          println(s"Counterexample to induction with ${step} steps:")
          cti.steps.foreach(p => println("  " + p.pprString))
        case CommandsResponses.UnsatStatus   => return Kind.InvariantMaintainedAt(step)
      }

      asserts(top.obligations, rowPrefix(step), solver)
    }

    Kind.NoGood(count)
