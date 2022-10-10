package lack.meta.core.term.prim

import lack.meta.base.num
import lack.meta.base.num.{Integer, Real}
import lack.meta.base.{names, pretty}
import lack.meta.core.Sort
import lack.meta.core.term.{Prim, Val}
import lack.meta.core.term.Prim.{Simple, Prim_nn_n, Prim_nn_b, CheckException, EvalException}

/** Primitive definitions and table.
 * Make sure to add any newly-defined primitives to Table.table and generators
 * to src/test/scala/lack/meta/core/term/prim/G.scala.
 */
object Table:

  /** Table of all base primitives.
    * These are the primitives that operate on base types and take no static
    * arguments.
    *
    * Later, if we have primitives for record constructors and accessors, then
    * those primitives will be dependent on the set of types in the program. */
  val base : List[Prim] = List(
    And, Or, Not, Implies,
    Ite,
    Negate,
    Add, Sub, Mul, Div,
    Le, Lt, Ge, Gt, Eq
  )

  case object And extends Simple("and", List(Sort.Bool, Sort.Bool), Sort.Bool):
    def evalX(args: List[Val]): Val = args match
      case List(Val.Bool(a), Val.Bool(b)) => Val.Bool(a && b)
      case _ => throw EvalException.impossible(this, args)

  case object Or extends Simple("or", List(Sort.Bool, Sort.Bool), Sort.Bool):
    def evalX(args: List[Val]): Val = args match
      case List(Val.Bool(a), Val.Bool(b)) => Val.Bool(a || b)
      case _ => throw EvalException.impossible(this, args)

  case object Not extends Simple("not", List(Sort.Bool), Sort.Bool):
    def evalX(args: List[Val]): Val = args match
      case List(Val.Bool(a)) => Val.Bool(!a)
      case _ => throw EvalException.impossible(this, args)

  case object Implies extends Simple("=>", List(Sort.Bool, Sort.Bool), Sort.Bool):
    def evalX(args: List[Val]): Val = args match
      case List(Val.Bool(a), Val.Bool(b)) => Val.Bool(if (a) b else true)
      case _ => throw EvalException.impossible(this, args)

  /** Ternary operator, "if-then-else". The name "ite" comes from SMTlib. */
  case object Ite extends Prim:
    def ppr = pretty.text("ite")
    def pprType = "[T]. (Boolean, T, T) => T"

    def sort(args: List[Sort]) = args match
      case List(Sort.Bool, t, f) if t == f =>
        t
      case List(Sort.Bool, t, f) =>
        throw CheckException.exactSame(this, args)
      case _ =>
        throw new CheckException(this, args, "")

    def eval(args: List[Val]): Val = args match
      case List(Val.Bool(p), t, f) => if (p) t else f
      case _ => throw new EvalException(this, args, "")


  case object Negate extends Prim:
    def ppr = pretty.text("negate")
    def pprType = "[T: Numeric]. T => T.logical"

    def sort(args: List[Sort]) = args match
      case List(s) if s.isInstanceOf[Sort.Numeric] =>
        s
      case _ => throw new CheckException(this, args, "")
    def eval(args: List[Val]): Val = args match
      case List(Val.Int(i)) => Val.Int(- i)
      case List(Val.Real(i)) => Val.Real(- i)
      case _ => throw new EvalException(this, args, "")

  case object Add extends Prim_nn_n:
    def ppr = pretty.text("+")
    def int(i: Integer, j: Integer) = i + j
    def real(i: Real, j: Real) = i + j

  case object Sub extends Prim_nn_n:
    def ppr = pretty.text("-")
    def int(i: Integer, j: Integer) = i - j
    def real(i: Real, j: Real) = i - j

  case object Mul extends Prim_nn_n:
    def ppr = pretty.text("*")
    def int(i: Integer, j: Integer) = i * j
    def real(i: Real, j: Real) = i * j

  /** "Safe" division where x / 0 = 0, which agrees with Isabelle and Z3 semantics
   * for integers. SMTLib's division also has interesting rounding behaviour:
   * "Regardless of sign of m,
   *   when n is positive, (div m n) is the floor of the rational number m/n;
   *   when n is negative, (div m n) is the ceiling of m/n.
   * https://smtlib.cs.uiowa.edu/theories-Ints.shtml
   *
   * We implement the same rounding behaviour to match SMTLib. It would be
   * possible to encode C-style division in SMT too, but I don't want to make
   * the SMT solver's life any harder than necessary. Integer division is
   * already hard enough.
   *
   * SMTLib's bitvector semantics for division are that bvudiv returns a
   * bitvector with all ones. For signed division with bvsdiv, the result is -1
   * or 1 depending on the sign of the left-hand-side. If we ever use
   * bitvectors, we must be careful to wrap bv.div to keep the x/0=0 semantics.
   */
  case object Div extends Prim_nn_n:
    def ppr = pretty.text("/")
    def int(m: Integer, n: Integer) =
      if n == 0
      then 0
      else
        val d = m / n
        val r = m % n
        if n > 0 && m >= 0
        then d
        else if n > 0 && m < 0
        then d - (if r != 0 then 1 else 0)
        else if n < 0 && m >= 0
        then d
        else d + (if r != 0 then 1 else 0)

    def real(i: Real, j: Real) =
      if j == 0
      then 0
      else i / j

  case object Le extends Prim_nn_b:
    def ppr = pretty.text("<=")
    def int(i: Integer, j: Integer) = i <= j
    def real(i: Real, j: Real) = i <= j

  case object Lt extends Prim_nn_b:
    def ppr = pretty.text("<")
    def int(i: Integer, j: Integer) = i < j
    def real(i: Real, j: Real) = i < j

  case object Ge extends Prim_nn_b:
    def ppr = pretty.text(">=")
    def int(i: Integer, j: Integer) = i >= j
    def real(i: Real, j: Real) = i >= j

  case object Gt extends Prim_nn_b:
    def ppr = pretty.text(">")
    def int(i: Integer, j: Integer) = i > j
    def real(i: Real, j: Real) = i > j

  case object Eq extends Prim:
    def ppr = pretty.text("=")
    def pprType = "[T]. (T,T) => Bool"

    def sort(args: List[Sort]) = args match
      case List(i, j)
        if i == j =>
        Sort.Bool
      case _ =>
        throw CheckException.exactSame(this, args)

    def eval(args: List[Val]): Val = args match
      case List(i, j) => Val.Bool(i == j)
      case _ =>
        throw new EvalException(this, args, "")
