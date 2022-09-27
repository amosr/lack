package lack.meta.smt

import lack.meta.smt.term.compound
import smtlib.Interpreter
import smtlib.trees.{Commands, CommandsResponses, Terms}
import smtlib.trees.Terms.SExpr

import smtlib.interpreters.{CVC4Interpreter, Z3Interpreter}

class Solver(interpreter: Interpreter, verbose: Boolean):
  var fresh: Int = 0

  def command(cmd: SExpr): SExpr =
    val r = commandUnchecked(cmd)
    r match
      case _ : CommandsResponses.Error =>
        throw new Solver.SolverException(r)
      case _ =>
        r

  def commandUnchecked(cmd: SExpr): SExpr =
    if (verbose)
      System.err.print(s"[smt]< ${cmd}")
    val got = clean(interpreter.eval(cmd))

    if (verbose)
      got match
        case CommandsResponses.Success =>
        case _ => System.err.print(s"[smt]> ${got}")

    got

  private def clean(response: SExpr): SExpr = response match
    case r : CommandsResponses.CheckSatStatus => r
    case e : CommandsResponses.Error =>
      // The Scala smtlib parser doesn't seem to handle sat, unsat or unknown results from check-sat-assuming.
      // When it tries to parse the result it encounters an error that looks like:
      //  (error "Solver encountered exception: smtlib.parser.Parser$UnexpectedTokenException: Unexpected token at position: (74, 1). Expected: [OParen]. Found: unsat")
      //
      if (e.msg.contains("UnexpectedTokenException"))
        if (e.msg.endsWith("Found: unsat"))
          CommandsResponses.CheckSatStatus(CommandsResponses.UnsatStatus)
        else if (e.msg.endsWith("Found: sat"))
          CommandsResponses.CheckSatStatus(CommandsResponses.SatStatus)
        else if (e.msg.endsWith("Found: unknown"))
          CommandsResponses.CheckSatStatus(CommandsResponses.UnknownStatus)
        else
          e
      else
        e
    case _ => response

  def declareConst(name: Terms.SSymbol, sort: Terms.Sort): Unit =
    val r = command(Commands.DeclareConst(name, sort))
    if (!r.isInstanceOf[CommandsResponses.Success.type])
      throw new Solver.SolverException(r, s"declareConst ${name} ${sort}: expected success, but got:")

  def declareConsts(consts: Iterable[Terms.SortedVar]): Unit = consts.foreach { c =>
    declareConst(c.name, c.sort)
  }

  def assert(term: Terms.Term): SExpr =
    command(Commands.Assert(term))

  def checkSat(): CommandsResponses.CheckSatStatus =
    command(Commands.CheckSat()).asInstanceOf[CommandsResponses.CheckSatStatus]

  /** Check satisfaction assuming that some expression is true.
    */
  def checkSatAssumingX[T](prop: Terms.Term)(cont: CommandsResponses.CheckSatStatus => T): T =
    val actlit  = compound.sym(s"%actlit$fresh")
    val xactlit = Terms.QualifiedIdentifier(Terms.Identifier(actlit))
    fresh = fresh + 1
    declareConst(actlit, Terms.Sort(compound.id("Bool")))
    assert(compound.implies(xactlit, prop))
    val sat = command(Commands.CheckSatAssuming(Seq(Commands.PropLiteral(actlit, true))))
    if (sat.isInstanceOf[CommandsResponses.CheckSatStatus])
      val ret = cont(sat.asInstanceOf[CommandsResponses.CheckSatStatus])
      assert(compound.not(xactlit))
      ret
    else
      throw new Solver.SolverException(sat)

  /** Execute in a local push/pop context
    */
  def pushed[T](cont: => T): T =
    command(Commands.Push(1))
    val ret: T = cont
    command(Commands.Pop(1))
    ret

object Solver:
  def gimme(verbose: Boolean = false): Solver = new Solver(interpreters.z3(), verbose)

  object interpreters:
    def z3() = Z3Interpreter.buildDefault
    def cvc5() = new CVC4Interpreter("cvc5",
      Array("--lang", "smt2",
        "--incremental",
        "--produce-models",
        "--print-success"),
      tailPrinter = true)

  class SolverException(response: SExpr, message: String = "SMT solver returned unexpected response") extends Exception(
    s"""${message}
      ${response}""")