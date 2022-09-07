package lack.meta.source

import lack.meta.core
import lack.meta.source.compound.{given, _}
import lack.meta.source.stream.{Stream, SortRepr, Bool, UInt8}
import lack.meta.source.stream
import lack.meta.source.node.{Node, NodeInvocation}
import lack.meta.source.node.Activate

import scala.collection.mutable

object automaton:
  /** Syntactic sugar for describing automata.
   *
   * The automata are loosely inspired by the paper "A conservative extension
   * of synchronous data-flow with state machines" (Colaço 2005), but I haven't
   * implemented the "last" operator or weak "until" transitions yet.
   * Parameterised state machines would be quite useful too ("Mixing signals
   * and modes in synchronous data-flow systems", Colaço 2006) and seem doable.
   *
   * I'm not convinced that the semantics in either of these papers are totally
   * correct though - weak "until" resets seem to only reset the strong
   * transition (the "unless" part), rather than the main part of the state.
   *
   * The user is expected to create a node that inherits from this class and
   * define named local state objects that inherit from Automaton.State. Each
   * state object defines its transitions and bindings. The user must specify
   * the initial state by calling initial(state) from their automaton's
   * constructor.
   *
   * Too much magic?
   */
  abstract class Automaton(invocation: NodeInvocation) extends Node(invocation):
    /** The user must specify their initial state */
    def initial(state: State) =
      assert(initialState.isEmpty, s"Cannot set initial state multiple times. Have value ${initialState}, tried to set to ${state.info}")
      initialState = Some(state.info)

    private var stateCounter: Int = 0
    private def freshSC(): Int =
      stateCounter += 1
      stateCounter - 1

    opaque type St = UInt8
    private val MAX_STATES = 256
    private val pre_state = local[St]
    private val state = local[St]
    private val reset_trigger = local[Bool]
    private var initialState: Option[StateInfo] = None

    case class StateInfo(st: Stream[St], indexInt: Int, active: Stream[Bool], reset: Stream[Bool]):
      def activate = Activate(reset = reset, when = active)

    private def freshStateInfo(): StateInfo =
      val i = freshSC()

      // TODO: thread through nice name
      val st = u8(i)
      val active = (state == st)
      StateInfo(st, i, active, reset_trigger)

    case class Transition(trigger: Stream[Bool], target: Target)
    val states = mutable.Map[Int, State]()

    case class Binding(lhs: core.names.Component, lhsX: core.term.Exp, index: Int, rhs: core.term.Exp)
    val bindings = mutable.ArrayBuffer[Binding]()

    private def bindingMap: Map[core.names.Component, Map[Int, Binding]] =
      val empty = Map[core.names.Component, Map[Int, Binding]]()
      bindings.foldLeft(empty) { (mp, b) =>
        val mpi = mp.getOrElse(b.lhs, Map())
        require(!mpi.contains(b.index), s"duplicate definitions for lhs ${b.lhs} in state ${b.index}")
        mp + (b.lhs -> (mpi + (b.index -> b)))
      }

    trait Target:
      val s: StateInfo
      def isRestart: Boolean
    case class Resume(s: StateInfo) extends Target:
      val isRestart = false
    case class Restart(s: StateInfo) extends Target:
      val isRestart = true

    abstract class State(val info: StateInfo = freshStateInfo()) extends Nested(info.activate):
      states += (info.indexInt -> this)

      val transitions = mutable.ArrayBuffer[Transition]()
      val transitionsDelay = mutable.Queue[() => Unit]()

      def active: Stream[Bool] =
        info.active

      def unless(transitions: => Unit): Unit =
        transitionsDelay += (() => transitions)

      /** HACK: only call this from inside an unless block.
       * The transitions need to execute in a different context from the main
       * definitions, as the strong transition may jump to a different state.
       * Given:
       * > object STAY_ONE_TICK extends State:
       * >  unless {
       * >    restart(False --> True, DONE)
       * >  }
       * >  here := False --> True
       * The two occurrences of "False --> True" have different clocks...
       */
      def restart(trigger: Stream[Bool], state: State): Unit =
        transitions += Transition(trigger, Restart(state.info))

      /** HACK: only call this from inside an unless block. */
      def resume(trigger: Stream[Bool], state: State): Unit =
        transitions += Transition(trigger, Resume(state.info))

      /** Should only be called by Automaton.finish */
      private[source]
      def finish(): Unit =
        println(s"finish state ${info}")
        builder.withNesting(builder.nodeRef.nested) {
          val act = Activate(when = (pre_state == info.st), reset = fby(False, reset_trigger))
          builder.withNesting(builder.activate(act)) {
            transitionsDelay.removeAll().foreach(f => f())
          }
        }

      def bind[T](lhs: Automaton.this.Lhs[T], rhs: Stream[T]): Unit =
        bindings += Binding(lhs.v, lhs._exp, info.indexInt, rhs._exp)

      extension [T](lhs: Automaton.this.Lhs[T])
        protected def := (rhs: Stream[T]) =
          bind(lhs, rhs)

    /** TODO: this should be called automatically. Should source.node.Node have
     * a finish function that finishes all subnodes?
     */
    def finish(): Unit =
      require(initialState.nonEmpty, "No initial state specified. Specify the initial state with initial(S)")
      require(states.size > 0, "no states. add some states to your automaton")

      // Loop through all of the states, "finishing" them.
      // This forces each state to register its transitions.
      // The states might be declared as local objects, which are lazily
      // initialised, so one state registering its transitions might initialise
      // the target state objects. So we loop through the states array such
      // that we'll still find any values that were added by previous calls
      // to finish.
      def finishStates(): Unit =
        var i = 0
        while (i < states.size) {
          states(i).finish()
          i += 1
          require(i < MAX_STATES,
            s"state overflow: you have too many states (${states.size} >= ${MAX_STATES}).")
        }

      finishStates()

      val initialStateSt = initialState.get.indexInt
      def goTransitions(trs: List[Transition]): (Stream[Bool], Stream[St]) = trs match
        case Nil => (False, pre_state)
        case t :: trs =>
          val (rT,stT) = goTransitions(trs)
          val transition_reset_trigger = ifthenelse(t.trigger, bool(t.target.isRestart), rT)
          val transition_new_state = ifthenelse(t.trigger, t.target.s.st, stT)
          (transition_reset_trigger, transition_new_state)

      def goStates(sts: List[State]): (Stream[Bool], Stream[St]) = sts match
        case Nil => (False, pre_state)
        case s :: sts =>
          val pre_active = pre_state == s.info.st
          val (rT,stT) = goTransitions(s.transitions.toList)
          val (rX,stX) = goStates(sts)
          val state_reset_trigger = ifthenelse(pre_active, rT, rX)
          val state_new_state = ifthenelse(pre_active, stT, stX)
          (state_reset_trigger, state_new_state)

      val (resetX, stateX) = goStates(states.values.toList)
      reset_trigger := resetX
      state := stateX
      pre_state := fby(u8(initialStateSt), state)

      property("GEN: finite states") {
        val assert_finite_states = u8(0) <= pre_state && pre_state < u8(stateCounter)
        assert_finite_states
      }

      bindingMap.foreach { (lhs, mpi) =>
        require(mpi.size == states.size, s"missing some bindings for states in ${lhs}, ${mpi}")
        def go(l: List[Binding]): core.term.Exp = l match
          case List(b) => b.rhs
          case b :: bs =>
            // TODO: this won't be in a-normal form
            core.term.Exp.App(b.rhs.sort, core.term.Prim.Ite, states(b.index).active._exp, b.rhs, go(bs))

        val rhs = go(mpi.values.toList)
        builder.nested.equation(lhs, rhs)
      }
