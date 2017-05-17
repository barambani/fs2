package fs2

import cats.Eval

import Segment._

abstract class Segment[+O,+R] { self =>
  private[fs2]
  def stage0: (Depth, (=> Unit) => Unit, O => Unit, Chunk[O] => Unit, R => Unit) => Eval[Step[O,R]]

  private[fs2]
  final def stage: (Depth, (=> Unit)=>Unit, O => Unit, Chunk[O] => Unit, R => Unit) => Eval[Step[O,R]] =
    (depth, defer, emit, emits, done) =>
      if (depth < MaxFusionDepth) stage0(depth.increment, defer, emit, emits, done)
      else evalDefer {
        stage0(Depth(0), defer,
               o => defer(emit(o)),
               os => defer(emits(os)),
               r => defer(done(r)))
      }

  def unconsChunk: Either[R, (Chunk[O],Segment[O,R])] =
    unconsChunks match {
      case Left(r) => Left(r)
      case Right((cs, tl)) =>
        firstNonEmptyChunk(cs) match {
          case Some((c,cs)) => Right(c -> cs.toList.foldRight(tl)((hd,tl) => tl cons hd))
          case None => tl.unconsChunk // should never hit this case
        }
    }

  @annotation.tailrec
  private def firstNonEmptyChunk[O](cs: Catenable[Chunk[O]]): Option[(Chunk[O],Catenable[Chunk[O]])] =
    cs.uncons match {
      case None => None
      case Some((c,cs)) =>
        if (c.isEmpty) firstNonEmptyChunk(cs)
        else Some(c -> cs)
    }

  @annotation.tailrec
  final def uncons1: Either[R, (O,Segment[O,R])] =
    unconsChunk match {
      case Left(r) => Left(r)
      case Right((c, tl)) =>
        if (c.nonEmpty) Right(c(0) -> tl.cons(Chunk.vector(c.toVector.drop(1))))
        else tl.uncons1
    }

  final def uncons: Either[R, (Segment[O,Unit],Segment[O,R])] = unconsChunks match {
    case Left(r) => Left(r)
    case Right((cs,tl)) => Right(Catenated(cs) -> tl)
  }

  final def unconsChunks: Either[R, (Catenable[Chunk[O]],Segment[O,R])] = {
    var out: Catenable[Chunk[O]] = Catenable.empty
    var result: Option[R] = None
    var ok = true
    val trampoline = makeTrampoline
    val step = stage(Depth(0),
      defer(trampoline),
      o => { out = out :+ Chunk.singleton(o); ok = false },
      os => { if (os.nonEmpty) { out = out :+ os; ok = false } },
      r => { result = Some(r); ok = false }).value
    while (ok) steps(step, trampoline)
    result match {
      case None => Right(out -> step.remainder)
      case Some(r) =>
        if (out.isEmpty) Left(r)
        else Right(out -> pure(r))
    }
  }

  final def run[O2>:O](implicit U: O2 =:= Unit): R = {
    var result: Option[R] = None
    var ok = true
    val trampoline = makeTrampoline
    val step = stage(Depth(0), defer(trampoline), _ => (), _ => (), r => { result = Some(r); ok = false }).value
    while (ok) steps(step, trampoline)
    result.get
  }

  final def foldRightLazy[B](z: B)(f: (O,=>B) => B): B = uncons1 match {
    case Left(_) => z
    case Right((hd,tl)) => f(hd, tl.foldRightLazy(z)(f))
  }

  final def sum[N>:O](initial: N)(implicit N: Numeric[N]): Segment[Nothing,N] = new Segment[Nothing,N] {
    def stage0 = (depth, defer, emit, emits, done) => {
      var b = N.zero
      self.stage(depth.increment, defer,
        o => b = N.plus(b, o),
        { case os : Chunk.Longs =>
            var i = 0
            var cs = 0L
            while (i < os.size) { cs += os.at(i); i += 1 }
            b = N.plus(b, cs.asInstanceOf[N])
          case os =>
            var i = 0
            while (i < os.size) { b = N.plus(b, os(i)); i += 1 }
        },
        r => done(b)).map(_.mapRemainder(_.sum(b)))
    }
    override def toString = s"($self).sum($initial)"
  }

  final def fold[B](z: B)(f: (B,O) => B): Segment[Nothing,B] = new Segment[Nothing,B] {
    def stage0 = (depth, defer, emit, emits, done) => {
      var b = z
      self.stage(depth.increment, defer,
        o => b = f(b, o),
        os => { var i = 0; while (i < os.size) { b = f(b, os(i)); i += 1 } },
        r => done(b)).map(_.mapRemainder(_.fold(b)(f)))
    }
    override def toString = s"($self).fold($z)($f)"
  }

  final def scan[B](z: B, emitFinal: Boolean = true)(f: (B,O) => B): Segment[B,B] = new Segment[B,B] {
    def stage0 = (depth, defer, emit, emits, done) => {
      var b = z
      self.stage(depth.increment, defer,
        o => { emit(b); b = f(b, o) },
        os => { var i = 0; while (i < os.size) { emit(b); b = f(b, os(i)); i += 1 } },
        r => { if (emitFinal) emit(b); done(b) }).map(_.mapRemainder(_.scan(b)(f)))
    }
    override def toString = s"($self).scan($z)($f)"
  }

  final def take(n: Long): Segment[O,Option[(Long,R)]] = new Segment[O,Option[(Long,R)]] {
    def stage0 = (depth, defer, emit, emits, done) => {
      var rem = n
      self.stage(depth.increment, defer,
        o => { if (rem > 0) { rem -= 1; emit(o) } else done(None) },
        os => { if (os.size <= rem) { rem -= os.size; emits(os) }
                else {
                  var i = 0
                  while (rem > 0) { rem -= 1; emit(os(i)); i += 1 }
                  done(None)
                }
              },
        r => done(Some(rem -> r))
      ).map(_.mapRemainder(_.take(rem)))
    }
    override def toString = s"($self).take($n)"
  }

  final def takeWhile(f: O => Boolean): Segment[O,Either[Long,R]] = new Segment[O,Either[Long,R]] {
    def stage0 = (depth, defer, emit, emits, done) => {
      var ok = true
      var count = 0L
      self.stage(depth.increment, defer,
        o => { ok = ok && f(o); if (ok) { emit(o); count += 1 } else done(Left(count)) },
        os => {
          var i = 0
          while (ok && i < os.size) {
            val o = os(i)
            ok = f(o)
            if (!ok) {
              var j = 0
              while (j < i) { emit(os(j)); count += 1; j += 1 }
            }
            i += 1
          }
          if (ok) emits(os) else done(Left(count))
        },
        r => done(Right(r))
      ).map(_.mapRemainder(rem => if (ok) rem.takeWhile(f) else rem.mapResult(Right(_))))
    }
    override def toString = s"($self).takeWhile(<f1>)"
  }

  final def drop(n: Long): Segment[O,(Long,R)] = new Segment[O,(Long,R)] {
    def stage0 = (depth, defer, emit, emits, done) => {
      var rem = n
      self.stage(depth.increment, defer,
        o => { if (rem > 0) rem -= 1 else emit(o) },
        os => { if (rem == 0) emits(os)
                else if (os.size <= rem) rem -= os.size
                else {
                  var i = 0
                  while (rem > 0) { rem -= 1; i += 1 }
                  while (i < os.size) { emit(os(i)); i += 1 }
                }
              },
        r => done(rem -> r)
      ).map(_.mapRemainder(_.drop(rem)))
    }
    override def toString = s"($self).drop($n)"
  }

  final def dropWhile(f: O => Boolean): Segment[O,(Boolean,R)] = new Segment[O,(Boolean,R)] {
    def stage0 = (depth, defer, emit, emits, done) => {
      var dropping = true
      self.stage(depth.increment, defer,
        o => { if (dropping) dropping = f(o); if (!dropping) emit(o) },
        os => {
          if (dropping) {
            var i = 0
            while (dropping && i < os.size) {
              val o = os(i)
              dropping = f(o)
              if (!dropping) while (i < os.size) { emit(os(i)); i += 1 }
              i += 1
            }
          } else emits(os)
        },
        r => done((dropping, r))
      ).map(_.mapRemainder(rem => if (dropping) rem.dropWhile(f) else rem.mapResult((false, _))))
    }
    override def toString = s"($self).dropWhile(<f1>)"
  }

  def map[O2](f: O => O2): Segment[O2,R] = new Segment[O2,R] {
    def stage0 = (depth, defer, emit, emits, done) => evalDefer {
      self.stage(depth.increment, defer,
        o => emit(f(o)),
        os => { var i = 0; while (i < os.size) { emit(f(os(i))); i += 1; } },
        done).map(_.mapRemainder(_ map f))
    }
    override def toString = s"($self).map(<f1>)"
  }

  final def filter[O2](p: O => Boolean): Segment[O,R] = new Segment[O,R] {
    def stage0 = (depth, defer, emit, emits, done) => evalDefer {
      self.stage(depth.increment, defer,
        o => if (p(o)) emit(o),
        os => { var i = 0; while (i < os.size) { val o = os(i); if (p(o)) emit(o); i += 1; } },
        done).map(_.mapRemainder(_ filter p))
    }
    override def toString = s"($self).filter(<pf1>)"
  }

  final def collect[O2](pf: PartialFunction[O,O2]): Segment[O2,R] = new Segment[O2,R] {
    def stage0 = (depth, defer, emit, emits, done) => evalDefer {
      self.stage(depth.increment, defer,
        o => if (pf.isDefinedAt(o)) emit(pf(o)),
        os => { var i = 0; while (i < os.size) { val o = os(i); if (pf.isDefinedAt(o)) emit(pf(o)); i += 1; } },
        done).map(_.mapRemainder(_ collect pf))
    }
    override def toString = s"($self).collect(<pf1>)"
  }

  final def mapResult[R2](f: R => R2): Segment[O,R2] = new Segment[O,R2] {
    def stage0 = (depth, defer, emit, emits, done) => evalDefer {
      self.stage(depth.increment, defer, emit, emits, r => done(f(r))).map(_.mapRemainder(_ mapResult f))
    }
    override def toString = s"($self).mapResult(<f1>)"
  }

  final def voidResult: Segment[O,Unit] = mapResult(_ => ())

  final def ++[O2>:O,R2>:R](s2: Segment[O2,R2]): Segment[O2,R2] =
    s2 match {
      case c2: Chunk[O2] if c2.isEmpty => this
      case _ => this match {
        case c: Chunk[O2] if c.isEmpty => s2
        case Catenated(s1s) => s2 match {
          case Catenated(s2s) => Catenated(s1s ++ s2s)
          case _ => Catenated(s1s :+ s2)
        }
        case s1 => s2 match {
          case Catenated(s2s) => Catenated(s1 +: s2s)
          case s2 => Catenated(Catenable(s1,s2))
        }
      }
    }

  final def cons[O2>:O](c: Segment[O2,Any]): Segment[O2,R] =
    // note - cast is fine, as `this` is guaranteed to provide an `R`,
    // overriding the `Any` produced by `c`
    c.asInstanceOf[Segment[O2,R]] ++ this

  final def drain: Segment[Nothing,R] = new Segment[Nothing,R] {
    def stage0 = (depth, defer, emit, emits, done) => evalDefer {
      self.stage(depth.increment, defer, o => (), os => (), done).map(_.mapRemainder(_.drain))
    }
    override def toString = s"($self).drain"
  }

  def foreachChunk(f: Chunk[O] => Unit): Unit = {
    var ok = true
    val trampoline = makeTrampoline
    val step = stage(Depth(0), defer(trampoline), o => f(Chunk.singleton(o)), f, r => { ok = false }).value
    while (ok) steps(step, trampoline)
  }

  def toChunks: Catenable[Chunk[O]] = {
    var acc: Catenable[Chunk[O]] = Catenable.empty
    foreachChunk(c => acc = acc :+ c)
    acc
  }

  def toChunk: Chunk[O] = Chunk.vector(toVector)

  def toVector: Vector[O] = {
    val buf = new collection.immutable.VectorBuilder[O]
    foreachChunk(c => buf ++= c.toVector)
    buf.result
  }

  final def splitAt(n: Long): (Catenable[Segment[O,Unit]], Long, Either[R,Segment[O,R]]) = {
    if (n <= 0) (Catenable.empty, 0, Right(this))
    else {
      var out: Catenable[Chunk[O]] = Catenable.empty
      var result: Option[Either[R,Segment[O,Unit]]] = None
      var rem = n
      val emits: Chunk[O] => Unit = os => {
        if (result.isDefined) {
          result = result.map(_.map(_.cons(os)))
        } else if (os.nonEmpty) {
          if (os.size <= rem) {
            out = out :+ os
            rem -= os.size
          } else  {
            val (before, after) = os.splitAtChunk(rem.toInt) // nb: toInt is safe b/c os.size is an Int and rem < os.size
            out = out :+ before
            result = Some(Right(after))
            rem = 0
          }
        }
      }
      val trampoline = makeTrampoline
      val step = stage(Depth(0),
        defer(trampoline),
        o => emits(Chunk.singleton(o)),
        os => emits(os),
        r => if (result.isEmpty) result = Some(Left(r))).value
      while (result.isEmpty) steps(step, trampoline)
      val resultAsEither: Either[R,Segment[O,R]] =
        result.map(_.map(s => step.remainder.cons(s))).getOrElse(Right(step.remainder))
      (out, n - rem, resultAsEither)
    }
  }

  final def splitWhile(p: O => Boolean, emitFailure: Boolean = false): (Catenable[Segment[O,Unit]], Boolean, Either[R,Segment[O,R]]) = {
    var out: Catenable[Chunk[O]] = Catenable.empty
    var result: Option[Either[R,Segment[O,Unit]]] = None
    var ok = true
    val emits: Chunk[O] => Unit = os => {
      if (result.isDefined) {
        result = result.map(_.map(_.cons(os)))
      } else {
        var i = 0
        var j = 0
        while (ok && i < os.size) {
          ok = ok && p(os(i))
          if (!ok) j = i
          i += 1
        }
        if (ok) out = out :+ os
        else {
          val (before, after) = os.splitAtChunk(if (emitFailure) j + 1 else j)
          out = out :+ before
          result = Some(Right(after))
        }
      }
    }
    val trampoline = makeTrampoline
    val step = stage(Depth(0),
      defer(trampoline),
      o => emits(Chunk.singleton(o)),
      os => emits(os),
      r => if (result.isEmpty) result = Some(Left(r))).value
    while (result.isEmpty) steps(step, trampoline)
    val resultAsEither: Either[R,Segment[O,R]] =
      result.map(_.map(s => step.remainder.cons(s))).getOrElse(Right(step.remainder))
    (out, ok, resultAsEither)
  }

  def zipWith[O2,R2,O3](that: Segment[O2,R2])(f: (O,O2) => O3): Segment[O3,Either[(R,Segment[O2,R2]),(R2,Segment[O,R])]] =
    new Segment[O3,Either[(R,Segment[O2,R2]),(R2,Segment[O,R])]] {
      def stage0 = (depth, defer, emit, emits, done) => evalDefer {
        var l = new scala.collection.mutable.Queue[Chunk[O]]
        var lpos = 0
        var lStepped = false
        var r = new scala.collection.mutable.Queue[Chunk[O2]]
        var rpos = 0
        var rStepped = false
        def doZip: Unit = {
          var lh = if (l.isEmpty) null else l.head
          var rh = if (r.isEmpty) null else r.head
          var out1: Option[O3] = None
          var out: scala.collection.immutable.VectorBuilder[O3] = null
          while ((lh ne null) && lpos < lh.size && (rh ne null) && rpos < rh.size) {
            val zipCount = (lh.size - lpos) min (rh.size - rpos)
            if (zipCount == 1) {
              out1 = Some(f(lh(lpos),rh(rpos)))
              lpos += 1
              rpos += 1
            } else {
              if (out eq null) {
                out = new scala.collection.immutable.VectorBuilder[O3]()
                if (out1.isDefined) {
                  out += out1.get
                  out1 = None
                }
              }
              var i = 0
              while (i < zipCount) {
                out += f(lh(lpos),rh(rpos))
                i += 1
                lpos += 1
                rpos += 1
              }
            }
            if (lpos == lh.size) {
              l.dequeue
              lh = if (l.isEmpty) null else l.head
              lpos = 0
            }
            if (rpos == rh.size) {
              r.dequeue
              rh = if (r.isEmpty) null else r.head
              rpos = 0
            }
          }
          if (out1.isDefined) emit(out1.get)
          else if (out ne null) emits(Chunk.vector(out.result))
        }
        val emitsL: Chunk[O] => Unit = os => { l += os; doZip }
        val emitsR: Chunk[O2] => Unit = os => { r += os; doZip }
        var theStepR: Segment.Step[O2,R2] = null
        def unusedL: Segment[O,Unit] = if (l.isEmpty) Segment.empty else l.tail.foldLeft(if (lpos == 0) l.head else l.head.drop(lpos).voidResult)(_ ++ _)
        def unusedR: Segment[O2,Unit] = if (r.isEmpty) Segment.empty else r.tail.foldLeft(if (rpos == 0) r.head else r.head.drop(rpos).voidResult)(_ ++ _)
        for {
          stepL <- self.stage(depth, defer, o => emitsL(Chunk.singleton(o)), emitsL, r2 => {
            done(Left(r2 -> (if (theStepR eq null) that else theStepR.remainder.cons(unusedR))))
          })
          stepR <- that.stage(depth, defer, o2 => emitsR(Chunk.singleton(o2)), emitsR, r2 => {
            done(Right(r2 -> (stepL.remainder.cons(unusedL))))
          })
        } yield {
          theStepR = stepR
          step {
            val remL: Segment[O,R] = if (lStepped) stepL.remainder.cons(unusedL) else self
            val remR: Segment[O2,R2] = if (rStepped) stepR.remainder.cons(unusedR) else that
            remL.zipWith(remR)(f)
          } {
            if (l.isEmpty) { lStepped = true; stepL.step() } else { rStepped = true; stepR.step() }
          }
        }
      }
      override def toString = s"($self).zipWith($that)(<f1>)"
    }

  override def hashCode: Int = toVector.hashCode
  override def equals(a: Any): Boolean = a match {
    case s: Segment[O,R] => this.toVector == s.toVector
    case _ => false
  }
}

object Segment {
  def empty[O]: Segment[O,Unit] = Chunk.empty

  def pure[O,R](r: R): Segment[O,R] = new Segment[O,R] {
    def stage0 = (_,_,_,_,done) => Eval.later(step(pure(r))(done(r)))
    override def toString = s"pure($r)"
  }

  def singleton[O](o: O): Segment[O,Unit] = new Segment[O,Unit] {
    def stage0 = (_, _, emit, _, done) => Eval.later {
      var emitted = false
      step(if (emitted) empty else singleton(o)) {
        emit(o)
        done(())
        emitted = true
      }
    }
    override def toString = s"singleton($o)"
  }

  def vector[O](os: Vector[O]): Segment[O,Unit] = Chunk.vector(os)
  def indexedSeq[O](os: IndexedSeq[O]): Segment[O,Unit] = Chunk.indexedSeq(os)
  def seq[O](os: Seq[O]): Segment[O,Unit] = Chunk.seq(os)
  def array[O](os: Array[O]): Segment[O,Unit] = Chunk.array(os)
  def catenated[O,R](os: Catenable[Segment[O,R]]): Option[Segment[O,R]] =
    if (os.isEmpty) None else Some(Catenated(os))

  private[fs2]
  case class Catenated[+O,+R](s: Catenable[Segment[O,R]]) extends Segment[O,R] {
    def stage0 = (depth, defer, emit, emits, done) => Eval.always {
      var res: Option[R] = None
      var ind = 0
      val staged = s.map(_.stage(depth.increment, defer, emit, emits, r => { res = Some(r); ind += 1 }).value)
      var i = staged
      def rem = if (i.isEmpty) pure(res.get) else Catenated(i.map(_.remainder))
      step(rem) {
        i.uncons match {
          case None => done(res.get)
          case Some((hd, tl)) =>
            val ind0 = ind
            hd.step()
            defer {
              if (ind == ind0) i = hd +: tl
              else i = tl
            }
        }
      }
    }
    override def toString = s"catenated(${s.toList.mkString(", ")})"
  }

  def constant[O](o: O): Segment[O,Unit] = new Segment[O,Unit] {
    def stage0 = (depth, defer, emit, emits, done) => Eval.later { step(constant(o)) { emit(o) } }
    override def toString = s"constant($o)"
  }

  def unfold[S,O](s: S)(f: S => Option[(O,S)]): Segment[O,Unit] = new Segment[O,Unit] {
    def stage0 = (depth, _, emit, emits, done) => {
      var s0 = s
      Eval.later { step(unfold(s0)(f)) {
        f(s0) match {
          case None => done(())
          case Some((h,t)) => emit(h); s0 = t
        }
      }}
    }
    override def toString = s"unfold($s)($f)"
  }

  def from(n: Long, by: Long = 1): Segment[Long,Nothing] = new Segment[Long,Nothing] {
    def stage0 = (_, _, _, emits, _) => {
      var m = n
      var buf = new Array[Long](32)
      Eval.later { step(from(m,by)) {
        var i = 0
        while (i < buf.length) { buf(i) = m; m += by; i += 1 }
        emits(Chunk.longs(buf))
      }}
    }
    override def toString = s"from($n, $by)"
  }

  def step[O,R](rem: => Segment[O,R])(s: => Unit): Step[O,R] =
    new Step(Eval.always(rem), () => s)

  final class Step[+O,+R](val remainder0: Eval[Segment[O,R]], val step: () => Unit) {
    final def remainder: Segment[O,R] = remainder0.value
    final def mapRemainder[O2,R2](f: Segment[O,R] => Segment[O2,R2]): Step[O2,R2] =
      new Step(remainder0 map f, step)
    override def toString = "Step$" + ##
  }

  private val MaxFusionDepth: Depth = Depth(50)

  private def steps(t: Step[Any,Any], tailcalls: java.util.LinkedList[() => Unit]): Unit = {
    t.step()
    while (!tailcalls.isEmpty()) {
      val tc = tailcalls.remove()
      tc()
    }
  }

  private def makeTrampoline = new java.util.LinkedList[() => Unit]()
  private def defer(t: java.util.LinkedList[() => Unit]): (=>Unit) => Unit =
    u => t.addLast(() => u)

  // note - Eval.defer seems to not be stack safe
  private def evalDefer[A](e: => Eval[A]): Eval[A] = Eval.now(()) flatMap { _ => e }

  final case class Depth(value: Int) extends AnyVal {
    def increment: Depth = Depth(value + 1)
    def <(that: Depth): Boolean = value < that.value
  }
}
