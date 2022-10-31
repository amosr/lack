package lark.meta.core.term

import lark.meta.base.names
import lark.meta.core.Sort

/** Dynamic semantics of pure expressions. */
object Eval:
  type Heap = names.immutable.RefMap[Val]

  /** Evaluation options.
   *
   * @param checkRefinement
   *  If false, do not check that values of refinement types satisfy the
   *  refinement predicate.
   * @param arbitrary
   *  How to construct uninitialised values of given type.
   * @param prefix
   *  When looking up variables in the heap, prefix references by this.
  */
  case class Options(
    checkRefinement: Boolean = true,
    arbitrary: Sort => Val = Val.arbitrary,
    prefix: names.Prefix = names.Prefix(List())
  )

  /** Evaluate expressions under heap */
  def exp(heap: Heap, e: Exp, options: Options): Val = e match
    case e @ Exp.Var(_, v) =>
      heap.getOrElse(options.prefix(v),
        throw new except.NoSuchVariableException(e, heap, options.prefix))
    case Exp.Val(v) =>
      v
    case Exp.App(_, p, args : _*) =>
      val argsV = args.map(exp(heap, _, options))
      p.eval(argsV.toList)
    case Exp.Cast(op, e) =>
      cast(op, exp(heap, e, options), options, Some(e), Some(heap))

  /** Evaluate a cast */
  def cast(op: Exp.Cast.Op, v: Val, options: Options, e: Option[Exp] = None, heap: Option[Heap] = None): Val = op match
    case Exp.Cast.Box(r) =>
      if !options.checkRefinement || r.refinesVal(v)
      then Val.Refined(r, v)
      else throw new except.RefinementException(r, v, e, heap)
    case Exp.Cast.Unbox(t) => v match
      case Val.Refined(_, vX) => vX
      // TODO-BOUNDED-ARITH: this used to throw an exception when you tried to
      // get logical-of-bounded on a logical integer, but I have temporarily
      // changed it to just return the logical integer. This isn't so nice, but
      // the bounded arithmetic needs a serious overhaul, so a little extra
      // mess here isn't the worst.
      case v => v

  object except:
    class EvalException(msg: String) extends Exception(msg)

    class NoSuchVariableException(e: Exp.Var, heap: Heap, prefix: names.Prefix) extends EvalException(
      s"""No such variable ${e.v.pprString} with sort ${e.sort.pprString}.
        |Heap: ${names.Namespace.fromMap(heap).pprString}
        |Prefix: ${prefix.pprString}""".stripMargin)

    class RefinementException(sort: Sort.Refinement, v: Val, e: Option[Exp], h: Option[Heap]) extends EvalException(
      s"""Cannot cast value ${v.pprString} to refinement type ${sort.pprString}.
         |Expression: ${e.fold("")(_.pprString)}
         |Heap: ${h.fold("")(names.Namespace.fromMap(_).pprString)}
         |""".stripMargin)
