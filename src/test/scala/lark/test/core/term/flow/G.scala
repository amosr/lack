package lark.test.core.term.flow

import lark.meta.base.num
import lark.meta.core.term
import lark.meta.core.term.Check
import lark.meta.core.term.Exp
import lark.meta.core.term.Flow
import lark.meta.core.term.Val
import lark.meta.core.Sort

import lark.test.hedgehog._

/** Generators for pure expressions */
case class G(exp: lark.test.core.term.exp.G):
  val val_ = exp.val_
  val sort = exp.sort
  /** Generate a streaming expression of given type, with given environment.
   *
   * Takes two environments: "current" and "previous". The current environment
   * contains the variables that can be used in any expression, while the
   * previous environment can only be used in "pre" or "fby" contexts.
   * The current environment is usually a subset of the previous environment.
   */
  def flow(current: Check.Env, previous: Check.Env, sort: Sort): Gen[Flow] =
    Gen.choice1(
      for
        e <- exp.simp(current, sort)
      yield Flow.Pure(e),
      for
        e <- exp.simp(current, sort)
        f <- exp.simp(current, sort)
      yield Flow.Arrow(e, f),
      for
        v <- val_.value(sort)
        e <- exp.simp(previous, sort)
      yield Flow.Fby(v, e),
      for
        e <- exp.simp(previous, sort)
      yield Flow.Pre(e))
