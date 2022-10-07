package lack.meta.core.target

import lack.meta.base.{names, pretty}
import lack.meta.base.names.given

import lack.meta.core.target.c.{Printer => P}

import lack.meta.core.Sort
import lack.meta.core.term
import lack.meta.core.term.{Exp, Val, Compound, Bounded}
import lack.meta.core.term.Bounded.BEX
import lack.meta.core.node.{Node, Schedule, Variable}

import lack.meta.core.obc
import lack.meta.core.obc.Obc.{Statement, Method, Class}

import scala.collection.immutable.SortedMap

/** Translating from Obc to C */
object C:

  case class Options(
    basename: String,
    classes:  names.immutable.RefMap[Class],
    includes: List[String] = List("#include <lack/lack.h>"),
    version: String = "v0", // TODO hook version numbers up to git and ci
    check: obc.Check.Options = obc.Check.Options()
  )

  /** Print header file with structs and function prototypes. */
  def header(options: Options): pretty.Doc =
    prelude(options) <@>
    pretty.vsep(options.classes.values.map(Header.klass(_)).toSeq)

  /** Print source file with function definitions. */
  def source(options: Options, selfInclude: Boolean = true): pretty.Doc =
    val includes =
      if selfInclude
      then options.includes ++ List(s"#include \"${options.basename}.h\"")
      else options.includes
    prelude(options.copy(includes = includes)) <@>
    pretty.vsep(options.classes.values.map(Source.klass(_, options)).toSeq)

  /** Prelude with information about generated file */
  def prelude(options: Options): pretty.Doc =
    val text =
      s"${options.basename} generated by lack version ${options.version}"
    val txxt = text.map(c => '*')
    val lines = List(
      s"/***${txxt}**/",
      s"/** ${text} */",
      s"/***${txxt}**/",
      "") ++ options.includes ++ List("")
    pretty.vsep(lines.map(pretty.text(_)))

  object Names:
    def state(klass: names.Ref): names.Ref =
      P.Ident.suffix(klass, "state")
    def stateP(klass: names.Ref) = P.Ident.ref(state(klass))

    def out(klass: names.Ref, method: names.Component): names.Ref =
      P.Ident.suffix(names.Ref(klass.fullyQualifiedPath, method), "out")
    def outP(klass: names.Ref, method: names.Component) = P.Ident.ref(out(klass, method))

  /** Printing headers */
  object Header:
    def state_decl(k: Class): pretty.Doc =
      val objectsP = k.objects.map { case (k,v) =>
        P.Decl.var_(k, Names.state(v))
      }
      val fieldsP = k.fields.map { kv =>
        P.Decl.var_(kv.name, kv.sort)
      }

      P.Decl.struct(Names.state(k.name), objectsP ++ fieldsP)

    def method(k: Class, m: Method): pretty.Doc =
      val name = names.Ref(k.name.fullyQualifiedPath, m.name)

      val (struct_decl, ret_ty, ret_args) = m.returns match
        case List() => (List(), P.Type.void, List())
        case rets =>
          // TODO sneaky invariant mode declare nested fields here
          val fieldsP = rets.map { kv =>
            P.Decl.var_(kv.name, kv.sort)
          }
          val outN = Names.out(k.name, m.name)
          val out  = P.Decl.struct(outN, fieldsP)
          (List(out), P.Type.void, List(P.Type.ptr(P.Ident.ref(outN))))

      val argsP =
        P.Type.ptr(Names.stateP(k.name)) ::
          m.params.map { kv => P.Type.sort(kv.sort) <+> P.Ident.component(kv.name) } ++
          ret_args

      pretty.vsep(
        struct_decl ++
        List(P.Decl.fun(ret_ty, name, argsP))
      ) <> pretty.line

    def klass(k: Class): pretty.Doc =
      val methods = k.methods.map(method(k, _))
      state_decl(k) <@>
        pretty.vsep(methods) <> pretty.line

  /** Printing source files */
  object Source:
    def statement(self: pretty.Doc, s: Statement, options: Options): pretty.Doc = s match
      case Statement.Skip =>
        pretty.emptyDoc
      case Statement.Seq(p, q) =>
        statement(self, p, options) <@>
          statement(self, q, options)
      case Statement.Assign(name, e) =>
        P.Stm.assign(P.Ident.component(name), Term.exp(self, e))
      case Statement.AssignSelf(name, e) =>
        P.Stm.assign(P.Term.fieldptr(self, name), Term.exp(self, e))
      case Statement.Ite(p, t, f) =>
        val else_ = f match
          case Statement.Skip => None
          case _ => Some(statement(self, f, options))
        P.Stm.if_(Term.exp(self, p), statement(self, t, options), else_)

      case Statement.Call(assigns, klass, method, instance, args) =>
        val name  = names.Ref(klass.fullyQualifiedPath, method)
        val instP = P.Term.address(P.Term.fieldptr(self, instance))
        val argsP = args.map(Term.exp(self, _))
        val outT = Names.outP(klass, method)
        val outV = P.Ident.component(instance)
        val meth = options.classes(klass).methodsMap(method)
        (assigns, meth.returns) match
          case (None, List()) =>
            P.Stm.fun(name, instP :: argsP)
          case (Some(_), List()) =>
            P.Stm.fun(name, instP :: argsP)
          case (None, _ :: _) =>
            val discard = pretty.text("$$discard")
            P.Stm.block(
              outT <+> discard <> pretty.semi <@>
              P.Stm.fun(name, instP +: argsP :+ discard)
            )
          case (Some(storage), _ :: _) =>
            P.Stm.fun(name, instP +: argsP :+ P.Term.address(P.Ident.component(storage)))

    def method(k: Class, m: Method, options: Options): pretty.Doc =
      val name = names.Ref(k.name.fullyQualifiedPath, m.name)
      val used = (m.params ++ m.returns ++ m.locals).map(_.name) ++ m.storage.map(_.name)
      val self = P.Ident.freshen("self", used)
      val out  = P.Ident.freshen("out", used)

      val (ret_ty, ret_args, ret_stms) = m.returns match
        case List() =>
          (P.Type.void, List(), List())
        case rets =>
          val stms = rets.map { r =>
            P.Stm.assign(P.Term.fieldptr(out, r.name), P.Ident.component(r.name))
          }
          (P.Type.void, List(P.Type.ptr(Names.outP(k.name, m.name)) <+> out), stms)

      val argsP =
        (P.Type.ptr(Names.stateP(k.name)) <+> self) ::
          m.params.map { kv => P.Type.sort(kv.sort) <+> P.Ident.component(kv.name) } ++
          ret_args

      val locals = (m.locals ++ m.returns).map { case kv =>
        P.Decl.var_(kv.name, kv.sort)
      }
      val storage = m.storage.map { case s =>
        P.Decl.var_(s.name, Names.outP(s.klass, s.method))
      }

      val stms =
        locals ++
        storage ++
        List(Source.statement(self, m.body, options)) ++
        ret_stms

      ret_ty <+> P.Ident.ref(name) <> pretty.tuple(argsP) <+>
        P.Stm.block(pretty.vsep(stms)) <>
        pretty.line

    def klass(k: Class, options: Options): pretty.Doc =
      val methods = k.methods.map(method(k, _, options))
      pretty.vsep(methods)

  /** Printing terms */
  object Term:
    /** Operator precedence */
    object Ops:
      def binop(prim: term.Prim): (String, Int) =
        import term.prim.Table._
        prim match
          case Or  => ("||", 12)
          case And => ("&&", 11)
          case Lt  => ("<",  6)
          case Le  => ("<=", 6)
          case Gt  => (">",  6)
          case Ge  => (">=", 6)
          case Eq  => ("==", 6)
          case Add => ("+",  4)
          case Sub => ("-",  4)
          case Mul => ("*",  3)
          case Div => ("/",  3)
      val UNARY_PREC   = 2
      val TERNARY_PREC = 13
      val COMMA_PREC   = 15
      val PARENS_PREC  = 16

    /** Pretty-print a value */
    def val_(v: Val): pretty.Doc = v match
      case Val.Bool(b) => b.toString
      case Val.Refined(s: Sort.BoundedInteger, Val.Int(i)) =>
        val suffix = (s.signed, s.width) match
          case (true,  64) => "l"
          case (false, 64) => "ul"
          case (true,  32) => ""
          case (false, 32) => "u"
          case (_,     _)  => ""
        pretty.value(i) <> suffix
      case Val.Real(r) => pretty.value(r) <> "f"
      case _ =>
        throw new P.except.BigNumberException("value", v.ppr)

    /** Nest expression, inserting parentheses if precedence of enclosing
     * operator is lower than or equal to the precedence of inner operator.
     */
    def nest(enclosing: Int, inner: Int, doc: pretty.Doc) =
      if enclosing < inner
      then pretty.parens(doc)
      else doc

    /** Print an expression at given precedence level.
     *
     * @param self
     *  the identifier used to refer to the current method's object (this)
     * @param e
     *  expression to print
     * @param p
     *  precedence level
     */
    def bounded(
      self: pretty.Doc,
      b: BEX,
      p: Int
    ): pretty.Doc = b match
      case BEX.Val(_, v) => val_(v)
      case BEX.Var(Exp.Var(_, r)) => r.prefix match
        // Print "self.x" variables as self->x.
        // Print other variables as-is.
        case self_ :: rest
         if List(self_) == Class.self.prefix =>
          P.Term.fieldptr(self, P.Term.fields(rest, r.name))
        case _ =>
          P.Term.fields(r.prefix, r.name)

      case BEX.ReboundCast(_, s, b) =>
        pretty.parens(P.Type.sort(s)) <> pretty.parens(bounded(self, b, Ops.PARENS_PREC))

      // Unary operators
      case BEX.TakeApp(term.prim.Table.Negate, List(a)) =>
        nest(p, Ops.UNARY_PREC, pretty.text("-") <+> bounded(self, a, Ops.UNARY_PREC - 1))
      case BEX.TakeApp(term.prim.Table.Not, List(a)) =>
        nest(p, Ops.UNARY_PREC, pretty.text("!") <> bounded(self, a, Ops.UNARY_PREC))

      // Special-case binary operators
      case BEX.TakeApp(term.prim.Table.Implies, List(a, b)) =>
        P.Term.fun("lack_implies", List(bounded(self, a, Ops.COMMA_PREC), bounded(self, b, Ops.COMMA_PREC)))
      case BEX.TakeApp(term.prim.Table.Div, List(a, b)) =>
        val div  = pretty.text("lack_div_") <> P.Type.sort(b.repr)
        P.Term.fun(div, List(bounded(self, a, Ops.COMMA_PREC), bounded(self, b, Ops.COMMA_PREC)))

      case BEX.TakeApp(op, List(a, b)) =>
        val (o, pp) = Ops.binop(op)
        nest(p, pp, bounded(self, a, pp) <+> o <+> bounded(self, b, pp - 1))

      // Ternary
      case BEX.TakeApp(term.prim.Table.Ite, List(pred, t, f)) =>
        nest(p, Ops.TERNARY_PREC,
          bounded(self, pred, Ops.TERNARY_PREC - 1) <+> "?" <+>
          bounded(self, t,    Ops.TERNARY_PREC - 1) <+> ":" <+>
          bounded(self, f,    Ops.TERNARY_PREC - 1))

      case _ : (BEX.App | BEX.ReboundApp) =>
        throw new Exception(s"prim not supported: ${b.original.pprString}")

    def exp(self: pretty.Doc, e: Exp): pretty.Doc =
      val b = Bounded.bound(e)
      bounded(self, b, Ops.PARENS_PREC)
