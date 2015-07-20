// Copyright 2014-2015 Commonwealth Bank of Australia
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package au.com.cba.omnia.grimlock.scalding

import au.com.cba.omnia.grimlock.framework.{
  Along,
  Cell,
  ExpandableMatrix => BaseExpandableMatrix,
  ExtractWithDimension,
  ExtractWithKey,
  InMemory,
  Locate,
  Matrix => BaseMatrix,
  Matrixable => BaseMatrixable,
  Nameable => BaseNameable,
  Over,
  ReduceableMatrix => BaseReduceableMatrix,
  Reducers,
  Slice,
  Tuner,
  Type,
  Unbalanced
}
import au.com.cba.omnia.grimlock.framework.aggregate._
import au.com.cba.omnia.grimlock.framework.content._
import au.com.cba.omnia.grimlock.framework.content.metadata._
import au.com.cba.omnia.grimlock.framework.encoding._
import au.com.cba.omnia.grimlock.framework.pairwise._
import au.com.cba.omnia.grimlock.framework.partition._
import au.com.cba.omnia.grimlock.framework.position._
import au.com.cba.omnia.grimlock.framework.sample._
import au.com.cba.omnia.grimlock.framework.squash._
import au.com.cba.omnia.grimlock.framework.transform._
import au.com.cba.omnia.grimlock.framework.utility._
import au.com.cba.omnia.grimlock.framework.window._

import au.com.cba.omnia.grimlock.scalding.Matrix._
import au.com.cba.omnia.grimlock.scalding.Matrixable._

import cascading.flow.FlowDef
import com.twitter.scalding.{ Mode, TextLine }
import com.twitter.scalding.typed.{ IterablePipe, Grouped, TypedPipe, TypedSink, ValuePipe }

import java.io.{ File, PrintWriter }
import java.lang.{ ProcessBuilder, Thread }
import java.nio.file.Paths

import scala.collection.immutable.HashSet
import scala.io.Source
import scala.reflect.ClassTag

private[scalding] object ScaldingImplicits {

  implicit def hashSetSemigroup[A] = new com.twitter.algebird.Semigroup[HashSet[A]] {
    def plus(l: HashSet[A], r: HashSet[A]): HashSet[A] = l ++ r
  }

  implicit def mapSemigroup[K, V] = new com.twitter.algebird.Semigroup[Map[K, V]] {
    def plus(l: Map[K, V], r: Map[K, V]): Map[K, V] = l ++ r
  }

  implicit def cellOrdering[P <: Position] = new Ordering[Cell[P]] {
    def compare(l: Cell[P], r: Cell[P]) = l.toString.compare(r.toString)
  }

  implicit def contentOrdering = new Ordering[Content] {
    def compare(l: Content, r: Content) = l.toString.compare(r.toString)
  }

  implicit def serialisePosition[T <: Position](key: T): Array[Byte] = {
    key.toShortString("|").toCharArray.map(_.toByte)
  }
}

/** Base trait for matrix operations using a `TypedPipe[Cell[P]]`. */
trait Matrix[P <: Position] extends BaseMatrix[P] with Persist[Cell[P]] {
  type U[A] = TypedPipe[A]
  type E[B] = ValuePipe[B]
  type S = Matrix[P]

  import ScaldingImplicits._

  def change[D <: Dimension, T](slice: Slice[P, D], positions: T, schema: Schema, tuner: Tuner)(
    implicit ev1: PosDimDep[P, D], ev2: BaseNameable[T, P, slice.S, D, TypedPipe],
      ev3: ClassTag[slice.S]): U[Cell[P]] = {
    val pos = ev2.convert(this, slice, positions)
    val f = (change: Boolean, cell: Cell[P]) => change match {
      case true => schema.decode(cell.content.value.toShortString).map { case con => Cell(cell.position, con) }
      case false => Some(cell)
    }

    tuner match {
      case InMemory() =>
        data
          .flatMapWithValue(pos.map { case (p, _) => HashSet(p) }.sum) {
            case (c, vo) => f(vo.get.contains(slice.selected(c.position)), c)
          }
      case Reducers(reducers) =>
        data
          .groupBy { case c => slice.selected(c.position) }
          .withReducers(reducers)
          .leftJoin(Grouped(pos))
          .flatMap { case (_, (c, o)) => f(!o.isEmpty, c) }
      case Unbalanced(reducers) =>
        data
          .groupBy { case c => slice.selected(c.position) }
          .sketch(reducers)
          .leftJoin(Grouped(pos))
          .flatMap { case (_, (c, o)) => f(!o.isEmpty, c) }
    }
  }

  def get[T](positions: T, tuner: Tuner)(implicit ev1: PositionDistributable[T, P, TypedPipe],
    ev2: ClassTag[P]): U[Cell[P]] = {
    val pos = ev1.convert(positions)

    tuner match {
      case InMemory() =>
        data
          .flatMapWithValue(pos.map { case p => HashSet(p) }.sum) {
            case (c, vo) => if (vo.get.contains(c.position)) { Some(c) } else { None }
          }
      case Reducers(reducers) =>
        data
          .groupBy { case c => c.position }
          .withReducers(reducers)
          .join(Grouped(pos.map { case p => (p, ()) }))
          .map { case (_, (c, _)) => c }
      case Unbalanced(reducers) =>
        data
          .groupBy { case c => c.position }
          .sketch(reducers)
          .join(Grouped(pos.map { case p => (p, ()) }))
          .map { case (_, (c, _)) => c }
    }
  }

  def join[D <: Dimension](slice: Slice[P, D], that: S)(implicit ev1: PosDimDep[P, D], ev2: P =!= Position1D,
    ev3: ClassTag[slice.S]): U[Cell[P]] = {
    val keep = names(slice)
      .groupBy { case (p, i) => p }
      .join(that.names(slice).groupBy { case (p, i) => p })

    data
      .groupBy { case c => slice.selected(c.position) }
      .join(keep)
      .map { case (_, (c, _)) => c } ++
      that
      .data
      .groupBy { case c => slice.selected(c.position) }
      .join(keep)
      .map { case (_, (c, _)) => c }
  }

  def names[D <: Dimension](slice: Slice[P, D])(implicit ev1: PosDimDep[P, D], ev2: slice.S =!= Position0D,
    ev3: ClassTag[slice.S]): U[(slice.S, Long)] = {
    Names.number(data.map { case c => slice.selected(c.position) }.distinct)
  }

  def pairwise[D <: Dimension, Q <: Position, T](slice: Slice[P, D], comparer: Comparer, operators: T, tuner: Tuner)(
    implicit ev1: PosDimDep[P, D], ev2: Operable[T, slice.S, slice.R, Q], ev3: slice.S =!= Position0D,
    ev4: ClassTag[slice.S], ev5: ClassTag[slice.R]): U[Cell[Q]] = {
    val o = ev2.convert(operators)

    pairwiseTuples(slice, comparer, tuner)(data, data)
      .flatMap { case (lc, lr, rc, rr) => o.compute(lc, lr, rc, rr).toList }
  }

  def pairwiseWithValue[D <: Dimension, Q <: Position, T, W](slice: Slice[P, D], comparer: Comparer, operators: T,
    value: E[W], tuner: Tuner)(implicit ev1: PosDimDep[P, D], ev2: OperableWithValue[T, slice.S, slice.R, Q, W],
      ev3: slice.S =!= Position0D, ev4: ClassTag[slice.S], ev5: ClassTag[slice.R]): U[Cell[Q]] = {
    val o = ev2.convert(operators)

    pairwiseTuples(slice, comparer, tuner)(data, data)
      .flatMapWithValue(value) { case ((lc, lr, rc, rr), vo) => o.computeWithValue(lc, lr, rc, rr, vo.get).toList }
  }

  def pairwiseBetween[D <: Dimension, Q <: Position, T](slice: Slice[P, D], comparer: Comparer, that: S, operators: T,
    tuner: Tuner)(implicit ev1: PosDimDep[P, D], ev2: Operable[T, slice.S, slice.R, Q], ev3: slice.S =!= Position0D,
      ev4: ClassTag[slice.S], ev5: ClassTag[slice.R]): U[Cell[Q]] = {
    val o = ev2.convert(operators)

    pairwiseTuples(slice, comparer, tuner)(data, that.data)
      .flatMap { case (lc, lr, rc, rr) => o.compute(lc, lr, rc, rr).toList }
  }

  def pairwiseBetweenWithValue[D <: Dimension, Q <: Position, T, W](slice: Slice[P, D], comparer: Comparer, that: S,
    operators: T, value: E[W], tuner: Tuner)(implicit ev1: PosDimDep[P, D],
    ev2: OperableWithValue[T, slice.S, slice.R, Q, W], ev3: slice.S =!= Position0D, ev4: ClassTag[slice.S],
      ev5: ClassTag[slice.R]): U[Cell[Q]] = {
    val o = ev2.convert(operators)

    pairwiseTuples(slice, comparer, tuner)(data, that.data)
      .flatMapWithValue(value) { case ((lc, lr, rc, rr), vo) => o.computeWithValue(lc, lr, rc, rr, vo.get).toList }
  }

  def rename(renamer: (Cell[P]) => P): U[Cell[P]] = data.map { case c => Cell(renamer(c), c.content) }

  def renameWithValue[W](renamer: (Cell[P], W) => P, value: E[W]): U[Cell[P]] = {
    data.mapWithValue(value) { case (c, vo) => Cell(renamer(c, vo.get), c.content) }
  }

  def sample[T](samplers: T)(implicit ev: Sampleable[T, P]): U[Cell[P]] = {
    val sampler = ev.convert(samplers)

    data.filter { case c => sampler.select(c) }
  }

  def sampleWithValue[T, W](samplers: T, value: E[W])(implicit ev: SampleableWithValue[T, P, W]): U[Cell[P]] = {
    val sampler = ev.convert(samplers)

    data.filterWithValue(value) { case (c, vo) => sampler.selectWithValue(c, vo.get) }
  }

  def set[T](positions: T, value: Content, tuner: Tuner)(implicit ev1: PositionDistributable[T, P, TypedPipe],
    ev2: ClassTag[P]): U[Cell[P]] = {
    set(ev1.convert(positions).map { case p => Cell(p, value) }, tuner)
  }

  def set[T](values: T, tuner: Tuner)(implicit ev1: BaseMatrixable[T, P, TypedPipe], ev2: ClassTag[P]): U[Cell[P]] = {
    data
      .groupBy { case c => c.position }
      .outerJoin(ev1.convert(values).groupBy { case c => c.position }) // Sketched doesn't have outerJoin
      .map { case (_, (co, cn)) => cn.getOrElse(co.get) }
  }

  def shape(): U[Cell[Position1D]] = {
    Grouped(data
      .flatMap { case c => c.position.coordinates.map(_.toString).zipWithIndex.map(_.swap) }
      .distinct)
      .size
      .map {
        case (i, s) => Cell(Position1D(Dimension.All(i).toString), Content(DiscreteSchema[Codex.LongCodex](), s))
      }
  }

  def size[D <: Dimension](dim: D, distinct: Boolean = false)(implicit ev: PosDimDep[P, D]): U[Cell[Position1D]] = {
    val coords = data.map { case c => c.position(dim) }
    val dist = if (distinct) { coords } else { coords.distinct(Value.Ordering) }

    dist
      .map { case _ => 1L }
      .sum
      .map { case sum => Cell(Position1D(dim.toString), Content(DiscreteSchema[Codex.LongCodex](), sum)) }
  }

  def slice[D <: Dimension, T](slice: Slice[P, D], positions: T, keep: Boolean, tuner: Tuner)(
    implicit ev1: PosDimDep[P, D], ev2: BaseNameable[T, P, slice.S, D, TypedPipe],
    ev3: ClassTag[slice.S]): U[Cell[P]] = {
    val pos = ev2.convert(this, slice, positions)
    val f = (check: Boolean, cell: Cell[P]) => if (keep == check) { Some(cell) } else { None }

    tuner match {
      case InMemory() =>
        data
          .flatMapWithValue(pos.map { case (p, _) => HashSet(p) }.sum) {
            case (c, vo) => f(vo.get.contains(slice.selected(c.position)), c)
          }
      case Reducers(reducers) =>
        data
          .groupBy { case c => slice.selected(c.position) }
          .withReducers(reducers)
          .leftJoin(Grouped(pos))
          .flatMap { case (_, (c, o)) => f(!o.isEmpty, c) }
      case Unbalanced(reducers) =>
        data
          .groupBy { case c => slice.selected(c.position) }
          .sketch(reducers)
          .leftJoin(Grouped(pos))
          .flatMap { case (_, (c, o)) => f(!o.isEmpty, c) }
    }
  }

  def slide[D <: Dimension, Q <: Position, T](slice: Slice[P, D], windows: T)(implicit ev1: PosDimDep[P, D],
    ev2: Windowable[T, slice.S, slice.R, Q], ev3: slice.R =!= Position0D, ev4: ClassTag[slice.S],
    ev5: ClassTag[slice.R]): U[Cell[Q]] = {
    val w = ev2.convert(windows)

    data
      .map { case Cell(p, c) => (Cell(slice.selected(p), c), slice.remainder(p)) }
      .groupBy { case (c, r) => c.position }
      .sortBy { case (c, r) => r }
      .scanLeft(Option.empty[(w.T, Collection[Cell[Q]])]) {
        case (None, (c, r)) => Some(w.initialise(c, r))
        case (Some((t, _)), (c, r)) => Some(w.present(c, r, t))
      }
      .flatMap {
        case (p, Some((t, c))) => c.toList
        case _ => List()
      }
  }

  def slideWithValue[D <: Dimension, Q <: Position, T, W](slice: Slice[P, D], windows: T, value: E[W])(
    implicit ev1: PosDimDep[P, D], ev2: WindowableWithValue[T, slice.S, slice.R, Q, W], ev3: slice.R =!= Position0D,
    ev4: ClassTag[slice.S], ev5: ClassTag[slice.R]): U[Cell[Q]] = {
    val w = ev2.convert(windows)

    data
      .mapWithValue(value) { case (Cell(p, c), vo) => (Cell(slice.selected(p), c), slice.remainder(p), vo.get) }
      .groupBy { case (c, r, v) => c.position }
      .sortBy { case (c, r, v) => r }
      .scanLeft(Option.empty[(w.T, Collection[Cell[Q]])]) {
        case (None, (c, r, v)) => Some(w.initialiseWithValue(c, r, v))
        case (Some((t, _)), (c, r, v)) => Some(w.presentWithValue(c, r, v, t))
      }
      .flatMap {
        case (p, Some((t, c))) => c.toList
        case _ => List()
      }
  }

  def split[Q, T](partitioners: T)(implicit ev: Partitionable[T, P, Q]): U[(Q, Cell[P])] = {
    val partitioner = ev.convert(partitioners)

    data.flatMap { case c => partitioner.assign(c).toList(c) }
  }

  def splitWithValue[Q, T, W](partitioners: T, value: E[W])(
    implicit ev: PartitionableWithValue[T, P, Q, W]): U[(Q, Cell[P])] = {
    val partitioner = ev.convert(partitioners)

    data.flatMapWithValue(value) { case (c, vo) => partitioner.assignWithValue(c, vo.get).toList(c) }
  }

  def stream[Q <: Position](command: String, script: String, separator: String,
    parser: String => Option[Cell[Q]]): U[Cell[Q]] = {
    val lines = Source.fromFile(script).getLines.toList
    val smfn = (k: Unit, itr: Iterator[String]) => {
      val tmp = File.createTempFile("grimlock-", "-" + Paths.get(script).getFileName().toString())
      val name = tmp.getAbsolutePath
      tmp.deleteOnExit()

      val writer = new PrintWriter(name, "UTF-8")
      for (line <- lines) {
        writer.println(line)
      }
      writer.close()

      val process = new ProcessBuilder(command, name).start()

      new Thread() {
        override def run() {
          val out = new PrintWriter(process.getOutputStream)
          for (cell <- itr) {
            out.println(cell)
          }
          out.close()
        }
      }.start()

      val result = Source.fromInputStream(process.getInputStream).getLines()

      new Iterator[String] {
        def next(): String = result.next()
        def hasNext: Boolean = {
          if (result.hasNext) {
            true
          } else {
            val status = process.waitFor()
            if (status != 0) {
              throw new Exception(s"Subprocess '${command} ${script}' exited with status ${status}")
            }
            false
          }
        }
      }
    }

    data
      .map(_.toString(separator, false))
      .groupAll
      .mapGroup(smfn)
      .values
      .flatMap(parser(_))
  }

  def summarise[D <: Dimension, Q <: Position, T](slice: Slice[P, D], aggregators: T)(implicit ev1: PosDimDep[P, D],
    ev2: Aggregatable[T, P, slice.S, Q], ev3: ClassTag[slice.S]): U[Cell[Q]] = {
    summarise(slice, aggregators, 108)
  }

  def summarise[D <: Dimension, Q <: Position, T](slice: Slice[P, D], aggregators: T, reducers: Int)(
    implicit ev1: PosDimDep[P, D], ev2: Aggregatable[T, P, slice.S, Q], ev3: ClassTag[slice.S]): U[Cell[Q]] = {
    val a = ev2.convert(aggregators)

    Grouped(data.map { case c => (slice.selected(c.position), a.map { case b => b.prepare(c) }) })
      .withReducers(reducers)
      .reduce[List[Any]] {
        case (lt, rt) => (a, lt, rt).zipped.map { case (b, l, r) => b.reduce(l.asInstanceOf[b.T], r.asInstanceOf[b.T]) }
      }
      .flatMap { case (p, t) => (a, t).zipped.flatMap { case (b, s) => b.present(p, s.asInstanceOf[b.T]).toList } }
  }

  def summariseWithValue[D <: Dimension, Q <: Position, T, W](slice: Slice[P, D], aggregators: T, value: E[W])(
    implicit ev1: PosDimDep[P, D], ev2: AggregatableWithValue[T, P, slice.S, Q, W],
    ev3: ClassTag[slice.S]): U[Cell[Q]] = summariseWithValue(slice, aggregators, value, 108)

  def summariseWithValue[D <: Dimension, Q <: Position, T, W](slice: Slice[P, D], aggregators: T, value: E[W],
    reducers: Int)(implicit ev1: PosDimDep[P, D], ev2: AggregatableWithValue[T, P, slice.S, Q, W],
      ev3: ClassTag[slice.S]): U[Cell[Q]] = {
    val a = ev2.convert(aggregators)

    Grouped(data.mapWithValue(value) {
        case (c, vo) => (slice.selected(c.position), a.map { case b => b.prepareWithValue(c, vo.get) })
      })
      .withReducers(reducers)
      .reduce[List[Any]] {
        case (lt, rt) => (a, lt, rt).zipped.map { case (b, l, r) => b.reduce(l.asInstanceOf[b.T], r.asInstanceOf[b.T]) }
      }
      .flatMapWithValue(value) {
        case ((p, t), vo) => (a, t).zipped.flatMap {
          case (b, s) => b.presentWithValue(p, s.asInstanceOf[b.T], vo.get).toList
        }
      }
  }

  def toMap()(implicit ev: ClassTag[P]): E[Map[P, Content]] = {
    data
      .map { case c => Map(c.position -> c.content) }
      .sum
  }

  def toMap[D <: Dimension](slice: Slice[P, D])(implicit ev1: PosDimDep[P, D], ev2: slice.S =!= Position0D,
    ev3: ClassTag[slice.S]): E[Map[slice.S, slice.C]] = {
    data
      .map { case c => (c.position, slice.toMap(c)) }
      .groupBy { case (p, m) => slice.selected(p) }
      .reduce[(P, Map[slice.S, slice.C])] { case ((lp, lm), (rp, rm)) => (lp, slice.combineMaps(lp, lm, rm)) }
      .map { case (_, (_, m)) => m }
      .sum
  }

  def transform[Q <: Position, T](transformers: T)(implicit ev: Transformable[T, P, Q]): U[Cell[Q]] = {
    val transformer = ev.convert(transformers)

    data.flatMap { case c => transformer.present(c).toList }
  }

  def transformWithValue[Q <: Position, T, W](transformers: T, value: E[W])(
    implicit ev: TransformableWithValue[T, P, Q, W]): U[Cell[Q]] = {
    val transformer = ev.convert(transformers)

    data.flatMapWithValue(value) { case (c, vo) => transformer.presentWithValue(c, vo.get).toList }
  }

  def types[D <: Dimension](slice: Slice[P, D], specific: Boolean = false)(implicit ev1: PosDimDep[P, D],
    ev2: slice.S =!= Position0D, ev3: ClassTag[slice.S]): U[(slice.S, Type)] = {
    Grouped(data.map { case Cell(p, c) => (slice.selected(p), c.schema.kind) })
      .reduce[Type] { case (lt, rt) => Type.getCommonType(lt, rt) }
      .map { case (p, t) => (p, if (specific) t else t.getGeneralisation()) }
  }

  /** @note Comparison is performed based on the string representation of the `Content`. */
  def unique(): U[Content] = {
    data
      .map { case c => c.content }
      .distinct
  }

  /** @note Comparison is performed based on the string representation of the `Content`. */
  def unique[D <: Dimension](slice: Slice[P, D])(implicit ev: slice.S =!= Position0D): U[Cell[slice.S]] = {
    data
      .map { case Cell(p, c) => Cell(slice.selected(p), c) }
      .distinct
  }

  def which(predicate: Predicate)(implicit ev: ClassTag[P]): U[P] = {
    data.collect { case c if predicate(c) => c.position }
  }

  def which[D <: Dimension, T](slice: Slice[P, D], positions: T, predicate: Predicate)(implicit ev1: PosDimDep[P, D],
    ev2: BaseNameable[T, P, slice.S, D, TypedPipe], ev3: ClassTag[slice.S], ev4: ClassTag[P]): U[P] = {
    which(slice, List((positions, predicate)))
  }

  def which[D <: Dimension, T](slice: Slice[P, D], pospred: List[(T, Predicate)])(implicit ev1: PosDimDep[P, D],
    ev2: BaseNameable[T, P, slice.S, D, TypedPipe], ev3: ClassTag[slice.S], ev4: ClassTag[P]): U[P] = {
    val nampred = pospred.map { case (pos, pred) => ev2.convert(this, slice, pos).map { case (p, i) => (p, pred) } }
    val pipe = nampred.tail.foldLeft(nampred.head)((b, a) => b ++ a)

    data
      .groupBy { case c => slice.selected(c.position) }
      .join(pipe.groupBy { case (p, pred) => p })
      .collect { case (_, (c, (_, predicate))) if predicate(c) => c.position }
  }

  val data: U[Cell[P]]

  protected def saveDictionary(names: U[(Position1D, Long)], file: String, dictionary: String, separator: String)(
    implicit flow: FlowDef, mode: Mode) = {
    names
      .map { case (p, i) => p.toShortString(separator) + separator + i }
      .write(TypedSink(TextLine(dictionary.format(file))))

    Grouped(names)
  }

  protected def saveDictionary(names: U[(Position1D, Long)], file: String, dictionary: String, separator: String,
    dim: Dimension)(implicit flow: FlowDef, mode: Mode) = {
    names
      .map { case (p, i) => p.toShortString(separator) + separator + i }
      .write(TypedSink(TextLine(dictionary.format(file, dim.index))))

    Grouped(names)
  }

  private def pairwiseTuples[D <: Dimension](slice: Slice[P, D], comparer: Comparer, tuner: Tuner)(ldata: U[Cell[P]],
    rdata: U[Cell[P]])(implicit ev1: PosDimDep[P, D],
      ev2: ClassTag[slice.S]): U[(Cell[slice.S], slice.R, Cell[slice.S], slice.R)] = {
    tuner match {
      case InMemory() =>
        ldata
          .flatMapWithValue(rdata.map { case c => List(c) }.sum) {
            case (l, vo) =>
              vo.get.collect {
                case r if comparer.keep(slice.selected(l.position), slice.selected(r.position)) =>
                  (Cell(slice.selected(l.position), l.content), slice.remainder(l.position),
                    Cell(slice.selected(r.position), r.content), slice.remainder(r.position))
              }
          }
      case Reducers(reducers) =>
        val lnames = ldata.map { case Cell(p, _) => (slice.selected(p), ()) }.distinct
        val rnames = rdata.map { case Cell(p, _) => (slice.selected(p), ()) }.distinct
        val rl = Grouped(rnames)
           .withReducers(4)
           .forceToReducers
           .map { case (p, _) => List(p) }
           .sum

        val keys = Grouped(lnames)
          .withReducers(512)
          .forceToReducers
          .keys
          .flatMapWithValue(rl) { case (l, vo) => vo.get.collect { case r if comparer.keep(l, r) => (r, l) } }
          .forceToDisk

        ldata
          .groupBy { case Cell(p, _) => slice.selected(p) }
          .withReducers(reducers)
          .join(Grouped(rdata
                  .groupBy { case Cell(p, _) => slice.selected(p) }
                  .withReducers(128)
                  .join(Grouped(keys))
                  .map { case (r, (c, l)) => (l, c) }))
          .map {
            case (_, (lc, rc)) => (Cell(slice.selected(lc.position), lc.content), slice.remainder(lc.position),
              Cell(slice.selected(rc.position), rc.content), slice.remainder(rc.position))
          }
      case Unbalanced(reducers) =>
        val lnames = ldata.map { case Cell(p, _) => (slice.selected(p), ()) }.distinct
        val rnames = rdata.map { case Cell(p, _) => (slice.selected(p), ()) }.distinct
        val rl = Grouped(rnames)
           .withReducers(4)
           .forceToReducers
           .map { case (p, _) => List(p) }
           .sum

        val keys = Grouped(lnames)
          .withReducers(512)
          .forceToReducers
          .keys
          .flatMapWithValue(rl) { case (l, vo) => vo.get.collect { case r if comparer.keep(l, r) => (r, l) } }
          .forceToDisk

        ldata
          .groupBy { case Cell(p, _) => slice.selected(p) }
          .sketch(reducers)
          .join(Grouped(rdata
                  .groupBy { case Cell(p, _) => slice.selected(p) }
                  .withReducers(128)
                  .join(Grouped(keys))
                  .map { case (r, (c, l)) => (l, c) }))
          .map {
            case (_, (lc, rc)) => (Cell(slice.selected(lc.position), lc.content), slice.remainder(lc.position),
              Cell(slice.selected(rc.position), rc.content), slice.remainder(rc.position))
          }
    }
  }
}

/** Base trait for methods that reduce the number of dimensions or that can be filled using a `TypedPipe[Cell[P]]`. */
trait ReduceableMatrix[P <: Position with ReduceablePosition] extends BaseReduceableMatrix[P] { self: Matrix[P] =>

  def fill[D <: Dimension, Q <: Position](slice: Slice[P, D], values: U[Cell[Q]])(implicit ev1: PosDimDep[P, D],
    ev2: ClassTag[P], ev3: ClassTag[slice.S], ev4: slice.S =:= Q): U[Cell[P]] = {
    val dense = domain
      .groupBy[Slice[P, D]#S] { case p => slice.selected(p) }
      .join(values.groupBy { case c => c.position.asInstanceOf[slice.S] })
      .map { case (_, (p, c)) => Cell(p, c.content) }

    dense
      .groupBy { case c => c.position }
      .leftJoin(data.groupBy { case c => c.position })
      .map { case (p, (fc, co)) => co.getOrElse(fc) }
  }

  def fill(value: Content)(implicit ev: ClassTag[P]): U[Cell[P]] = {
    domain
      .groupBy { case p => p }
      .leftJoin(data.groupBy { case c => c.position })
      .map { case (p, (_, co)) => co.getOrElse(Cell(p, value)) }
  }

  def melt[D <: Dimension, F <: Dimension](dim: D, into: F, separator: String = ".")(implicit ev1: PosDimDep[P, D],
    ev2: PosDimDep[P, F], ne: D =!= F): U[Cell[P#L]] = {
    data.map { case Cell(p, c) => Cell(p.melt(dim, into, separator), c) }
  }

  def squash[D <: Dimension, T](dim: D, squasher: T)(implicit ev1: PosDimDep[P, D],
    ev2: Squashable[T, P]): U[Cell[P#L]] = {
    val squash = ev2.convert(squasher)

    data
      .groupBy { case c => c.position.remove(dim) }
      .reduce[Cell[P]] { case (x, y) => squash.reduce(dim, x, y) }
      .map { case (p, c) => Cell(p, c.content) }
  }

  def squashWithValue[D <: Dimension, T, W](dim: D, squasher: T, value: E[W])(implicit ev1: PosDimDep[P, D],
    ev2: SquashableWithValue[T, P, W]): U[Cell[P#L]] = {
    val squash = ev2.convert(squasher)

    data
      .leftCross(value)
      .groupBy { case (c, vo) => c.position.remove(dim) }
      .reduce[(Cell[P], Option[W])] { case ((x, xvo), (y, yvo)) => (squash.reduceWithValue(dim, x, y, xvo.get), xvo) }
      .map { case (p, (c, _)) => Cell(p, c.content) }
  }
}

/** Base trait for methods that expand the number of dimension of a matrix using a `TypedPipe[Cell[P]]`. */
trait ExpandableMatrix[P <: Position with ExpandablePosition] extends BaseExpandableMatrix[P] { self: Matrix[P] =>

  def expand[Q <: Position](expander: Cell[P] => Q)(implicit ev: PosExpDep[P, Q]): TypedPipe[Cell[Q]] = {
    data.map { case c => Cell(expander(c), c.content) }
  }

  def expandWithValue[Q <: Position, W](expander: (Cell[P], W) => Q, value: ValuePipe[W])(
    implicit ev: PosExpDep[P, Q]): TypedPipe[Cell[Q]] = {
    data.mapWithValue(value) { case (c, vo) => Cell(expander(c, vo.get), c.content) }
  }
}

// TODO: Make this work on more than 2D matrices and share with Spark
trait MatrixDistance { self: Matrix[Position2D] with ReduceableMatrix[Position2D] =>

  import au.com.cba.omnia.grimlock.library.aggregate._
  import au.com.cba.omnia.grimlock.library.pairwise._
  import au.com.cba.omnia.grimlock.library.transform._
  import au.com.cba.omnia.grimlock.library.window._

  /**
   * Compute correlations.
   *
   * @param slice Encapsulates the dimension for which to compute correlations.
   * @param tuner The tuner for the job.
   *
   * @return A `U[Cell[Position1D]]` with all pairwise correlations.
   */
  def correlation[D <: Dimension](slice: Slice[Position2D, D], tuner: Tuner = Unbalanced())(
    implicit ev1: PosDimDep[Position2D, D], ev2: ClassTag[slice.S], ev3: ClassTag[slice.R]): U[Cell[Position1D]] = {
    implicit def UP2DSC2M1D(data: U[Cell[slice.S]]): Matrix1D = new Matrix1D(data.asInstanceOf[U[Cell[Position1D]]])
    implicit def UP2DRMC2M2D(data: U[Cell[slice.R#M]]): Matrix2D = new Matrix2D(data.asInstanceOf[U[Cell[Position2D]]])

    val mean = data
      .summarise(slice, Mean[Position2D, slice.S]())
      .toMap(Over(First))

    val centered = data
      .transformWithValue(Subtract(ExtractWithDimension(slice.dimension)
        .andThenPresent((con: Content) => con.value.asDouble)), mean)

    val denom = centered
      .transform(Power[Position2D](2))
      .summarise(slice, Sum[Position2D, slice.S]())
      .pairwise(Over(First), Lower, Times(Locate.OperatorString[Position1D, Position0D]("(%1$s*%2$s)")), tuner)
      .transform(SquareRoot[Position1D]())
      .toMap(Over(First))

    centered
      .pairwise(slice, Lower, Times(Locate.OperatorString[slice.S, slice.R]("(%1$s*%2$s)")), tuner)
      .summarise(Over(First), Sum[Position2D, Position1D]())
      .transformWithValue(Fraction(ExtractWithDimension[Dimension.First, Position1D, Content](First)
        .andThenPresent(_.value.asDouble)), denom)
  }

  /**
   * Compute mutual information.
   *
   * @param slice Encapsulates the dimension for which to compute mutual information.
   * @param tuner The tuner for the job.
   *
   * @return A `U[Cell[Position1D]]` with all pairwise mutual information.
   */
  def mutualInformation[D <: Dimension](slice: Slice[Position2D, D], tuner: Tuner = Unbalanced())(
    implicit ev1: PosDimDep[Position2D, D], ev2: ClassTag[slice.S], ev3: ClassTag[slice.R]): U[Cell[Position1D]] = {
    implicit def UP2DRMC2M2D(data: U[Cell[slice.R#M]]): Matrix2D = new Matrix2D(data.asInstanceOf[U[Cell[Position2D]]])

    val dim = slice match {
      case Over(First) => Second
      case Over(Second) => First
      case Along(d) => d
      case _ => throw new Exception("unexpected dimension")
    }

    implicit object P3D extends PosDimDep[Position3D, dim.type]

    type W = Map[Position1D, Content]

    val extractor = ExtractWithDimension[Dimension.First, Position2D, Content](First)
      .andThenPresent(_.value.asDouble)

    val mhist = new Matrix2D(data)
      .expand((c: Cell[Position2D]) => c.position.append(c.content.value.toShortString))
      .summarise(Along[Position3D, dim.type](dim), Count[Position3D, Position2D]())

    val mcount = mhist
      .summarise(Over(First), Sum[Position2D, Position1D]())
      .toMap()

    val marginal = mhist
      .summariseWithValue(Over(First), Entropy[Position2D, Position1D, W](extractor)
        .andThenExpandWithValue((cell, _) => cell.position.append("marginal")), mcount)
      .pairwise(Over(First), Upper, Plus(Locate.OperatorString[Position1D, Position1D]("%s,%s")), tuner)

    val jhist = new Matrix2D(data)
      .pairwise(slice, Upper, Concatenate(Locate.OperatorString[slice.S, slice.R]("%s,%s")), tuner)
      .expand((c: Cell[Position2D]) => c.position.append(c.content.value.toShortString))
      .summarise(Along(Second), Count[Position3D, Position2D]())

    val jcount = jhist
      .summarise(Over(First), Sum[Position2D, Position1D]())
      .toMap()

    val joint = jhist
      .summariseWithValue(Over(First), Entropy[Position2D, Position1D, W](extractor, negate = true)
        .andThenExpandWithValue((cell, _) => cell.position.append("joint")), jcount)

    (marginal ++ joint)
      .summarise(Over(First), Sum[Position2D, Position1D]())
  }

  /**
   * Compute Gini index.
   *
   * @param slice Encapsulates the dimension for which to compute the Gini index.
   * @param tuner The tuner for the job.
   *
   * @return A `U[Cell[Position1D]]` with all pairwise Gini indices.
   */
  def gini[D <: Dimension](slice: Slice[Position2D, D], tuner: Tuner = Unbalanced())(
    implicit ev1: PosDimDep[Position2D, D], ev2: ClassTag[slice.S], ev3: ClassTag[slice.R]): U[Cell[Position1D]] = {
    implicit def UP2DSC2M1D(data: U[Cell[slice.S]]): Matrix1D = new Matrix1D(data.asInstanceOf[U[Cell[Position1D]]])
    implicit def UP2DSMC2M2D(data: U[Cell[slice.S#M]]): Matrix2D = new Matrix2D(data.asInstanceOf[U[Cell[Position2D]]])

    def isPositive = (cell: Cell[Position2D]) => cell.content.value.asDouble.map(_ > 0).getOrElse(false)
    def isNegative = (cell: Cell[Position2D]) => cell.content.value.asDouble.map(_ <= 0).getOrElse(false)

    val extractor = ExtractWithDimension[Dimension.First, Position2D, Content](First)
      .andThenPresent(_.value.asDouble)

    val pos = data
      .transform(Compare[Position2D](isPositive))
      .summarise(slice, Sum[Position2D, slice.S]())
      .toMap(Over(First))

    val neg = data
      .transform(Compare[Position2D](isNegative))
      .summarise(slice, Sum[Position2D, slice.S]())
      .toMap(Over(First))

    val tpr = data
      .transform(Compare[Position2D](isPositive))
      .slide(slice, CumulativeSum(Locate.WindowString[slice.S, slice.R]()))
      .transformWithValue(Fraction(extractor), pos)
      .slide(Over(First), BinOp((l: Double, r: Double) => r + l,
        Locate.WindowPairwiseString[Position1D, Position1D]("%2$s.%1$s")))

    val fpr = data
      .transform(Compare[Position2D](isNegative))
      .slide(slice, CumulativeSum(Locate.WindowString[slice.S, slice.R]()))
      .transformWithValue(Fraction(extractor), neg)
      .slide(Over(First), BinOp((l: Double, r: Double) => r - l,
        Locate.WindowPairwiseString[Position1D, Position1D]("%2$s.%1$s")))

    tpr
      .pairwiseBetween(Along(First), Diagonal, fpr,
        Times(Locate.OperatorString[Position1D, Position1D]("(%1$s*%2$s)")), tuner)
      .summarise(Along(First), Sum[Position2D, Position1D]())
      .transformWithValue(Subtract(ExtractWithKey[Position1D, String, Double]("one"), true),
        ValuePipe(Map(Position1D("one") -> 1.0)))
  }
}

object Matrix {
  /**
   * Read column oriented, pipe separated matrix data into a `TypedPipe[Cell[Position1D]]`.
   *
   * @param file      The file to read from.
   * @param separator The column separator.
   * @param first     The codex for decoding the first dimension.
   */
  def load1D(file: String, separator: String = "|", first: Codex = StringCodex): TypedPipe[Cell[Position1D]] = {
    TypedPipe.from(TextLine(file)).flatMap { Cell.parse1D(separator, first)(_) }
  }

  /**
   * Read column oriented, pipe separated data into a `TypedPipe[Cell[Position1D]]`.
   *
   * @param file      The file to read from.
   * @param dict      The dictionary describing the features in the data.
   * @param separator The column separator.
   * @param first     The codex for decoding the first dimension.
   */
  def load1DWithDictionary(file: String, dict: Map[String, Schema], separator: String = "|",
    first: Codex = StringCodex): TypedPipe[Cell[Position1D]] = {
    TypedPipe.from(TextLine(file)).flatMap { Cell.parse1DWithDictionary(dict, separator, first)(_) }
  }

  /**
   * Read column oriented, pipe separated data into a `TypedPipe[Cell[Position1D]]`.
   *
   * @param file      The file to read from.
   * @param schema    The schema for decoding the data.
   * @param separator The column separator.
   * @param first     The codex for decoding the first dimension.
   */
  def load1DWithSchema(file: String, schema: Schema, separator: String = "|",
    first: Codex = StringCodex): TypedPipe[Cell[Position1D]] = {
    TypedPipe.from(TextLine(file)).flatMap { Cell.parse1DWithSchema(schema, separator, first)(_) }
  }

  /**
   * Read column oriented, pipe separated matrix data into a `TypedPipe[Cell[Position2D]]`.
   *
   * @param file      The file to read from.
   * @param separator The column separator.
   * @param first     The codex for decoding the first dimension.
   * @param second    The codex for decoding the second dimension.
   */
  def load2D(file: String, separator: String = "|", first: Codex = StringCodex,
    second: Codex = StringCodex): TypedPipe[Cell[Position2D]] = {
    TypedPipe.from(TextLine(file)).flatMap { Cell.parse2D(separator, first, second)(_) }
  }

  /**
   * Read column oriented, pipe separated data into a `TypedPipe[Cell[Position2D]]`.
   *
   * @param file      The file to read from.
   * @param dict      The dictionary describing the features in the data.
   * @param dim       The dimension on which to apply the dictionary.
   * @param separator The column separator.
   * @param first     The codex for decoding the first dimension.
   * @param second    The codex for decoding the second dimension.
   */
  def load2DWithDictionary[D <: Dimension](file: String, dict: Map[String, Schema], dim: D = Second,
    separator: String = "|", first: Codex = StringCodex, second: Codex = StringCodex)(
      implicit ev: PosDimDep[Position2D, D]): TypedPipe[Cell[Position2D]] = {
    TypedPipe.from(TextLine(file)).flatMap { Cell.parse2DWithDictionary(dict, dim, separator, first, second)(_) }
  }

  /**
   * Read column oriented, pipe separated data into a `TypedPipe[Cell[Position2D]]`.
   *
   * @param file      The file to read from.
   * @param schema    The schema for decoding the data.
   * @param separator The column separator.
   * @param first     The codex for decoding the first dimension.
   * @param second    The codex for decoding the second dimension.
   */
  def load2DWithSchema(file: String, schema: Schema, separator: String = "|", first: Codex = StringCodex,
    second: Codex = StringCodex): TypedPipe[Cell[Position2D]] = {
    TypedPipe.from(TextLine(file)).flatMap { Cell.parse2DWithSchema(schema, separator, first, second)(_) }
  }

  /**
   * Read column oriented, pipe separated matrix data into a `TypedPipe[Cell[Position3D]]`.
   *
   * @param file      The file to read from.
   * @param separator The column separator.
   * @param first     The codex for decoding the first dimension.
   * @param second    The codex for decoding the second dimension.
   * @param third     The codex for decoding the third dimension.
   */
  def load3D(file: String, separator: String = "|", first: Codex = StringCodex, second: Codex = StringCodex,
    third: Codex = StringCodex): TypedPipe[Cell[Position3D]] = {
    TypedPipe.from(TextLine(file)).flatMap { Cell.parse3D(separator, first, second, third)(_) }
  }

  /**
   * Read column oriented, pipe separated data into a `TypedPipe[Cell[Position3D]]`.
   *
   * @param file      The file to read from.
   * @param dict      The dictionary describing the features in the data.
   * @param dim       The dimension on which to apply the dictionary.
   * @param separator The column separator.
   * @param first     The codex for decoding the first dimension.
   * @param second    The codex for decoding the second dimension.
   * @param third     The codex for decoding the third dimension.
   */
  def load3DWithDictionary[D <: Dimension](file: String, dict: Map[String, Schema], dim: D = Second,
    separator: String = "|", first: Codex = StringCodex, second: Codex = StringCodex, third: Codex = StringCodex)(
      implicit ev: PosDimDep[Position3D, D]): TypedPipe[Cell[Position3D]] = {
    TypedPipe.from(TextLine(file)).flatMap { Cell.parse3DWithDictionary(dict, dim, separator, first, second, third)(_) }
  }

  /**
   * Read column oriented, pipe separated data into a `TypedPipe[Cell[Position3D]]`.
   *
   * @param file      The file to read from.
   * @param schema    The schema for decoding the data.
   * @param separator The column separator.
   * @param first     The codex for decoding the first dimension.
   * @param second    The codex for decoding the second dimension.
   * @param third     The codex for decoding the third dimension.
   */
  def load3DWithSchema(file: String, schema: Schema, separator: String = "|", first: Codex = StringCodex,
    second: Codex = StringCodex, third: Codex = StringCodex): TypedPipe[Cell[Position3D]] = {
    TypedPipe.from(TextLine(file)).flatMap { Cell.parse3DWithSchema(schema, separator, first, second, third)(_) }
  }

  /**
   * Read column oriented, pipe separated matrix data into a `TypedPipe[Cell[Position4D]]`.
   *
   * @param file      The file to read from.
   * @param separator The column separator.
   * @param first     The codex for decoding the first dimension.
   * @param second    The codex for decoding the second dimension.
   * @param third     The codex for decoding the third dimension.
   * @param fourth    The codex for decoding the fourth dimension.
   */
  def load4D(file: String, separator: String = "|", first: Codex = StringCodex, second: Codex = StringCodex,
    third: Codex = StringCodex, fourth: Codex = StringCodex): TypedPipe[Cell[Position4D]] = {
    TypedPipe.from(TextLine(file)).flatMap { Cell.parse4D(separator, first, second, third, fourth)(_) }
  }

  /**
   * Read column oriented, pipe separated data into a `TypedPipe[Cell[Position4D]]`.
   *
   * @param file      The file to read from.
   * @param dict      The dictionary describing the features in the data.
   * @param dim       The dimension on which to apply the dictionary.
   * @param separator The column separator.
   * @param first     The codex for decoding the first dimension.
   * @param second    The codex for decoding the second dimension.
   * @param third     The codex for decoding the third dimension.
   * @param fourth    The codex for decoding the fourth dimension.
   */
  def load4DWithDictionary[D <: Dimension](file: String, dict: Map[String, Schema], dim: D = Second,
    separator: String = "|", first: Codex = StringCodex, second: Codex = StringCodex, third: Codex = StringCodex,
    fourth: Codex = StringCodex)(implicit ev: PosDimDep[Position4D, D]): TypedPipe[Cell[Position4D]] = {
    TypedPipe.from(TextLine(file)).flatMap {
      Cell.parse4DWithDictionary(dict, dim, separator, first, second, third, fourth)(_)
    }
  }

  /**
   * Read column oriented, pipe separated data into a `TypedPipe[Cell[Position4D]]`.
   *
   * @param file      The file to read from.
   * @param schema    The schema for decoding the data.
   * @param separator The column separator.
   * @param first     The codex for decoding the first dimension.
   * @param second    The codex for decoding the second dimension.
   * @param third     The codex for decoding the third dimension.
   * @param fourth    The codex for decoding the fourth dimension.
   */
  def load4DWithSchema(file: String, schema: Schema, separator: String = "|", first: Codex = StringCodex,
    second: Codex = StringCodex, third: Codex = StringCodex,
    fourth: Codex = StringCodex): TypedPipe[Cell[Position4D]] = {
    TypedPipe.from(TextLine(file)).flatMap {
      Cell.parse4DWithSchema(schema, separator, first, second, third, fourth)(_)
    }
  }

  /**
   * Read column oriented, pipe separated matrix data into a `TypedPipe[Cell[Position5D]]`.
   *
   * @param file      The file to read from.
   * @param separator The column separator.
   * @param first     The codex for decoding the first dimension.
   * @param second    The codex for decoding the second dimension.
   * @param third     The codex for decoding the third dimension.
   * @param fourth    The codex for decoding the fourth dimension.
   * @param fifth     The codex for decoding the fifth dimension.
   */
  def load5D(file: String, separator: String = "|", first: Codex = StringCodex, second: Codex = StringCodex,
    third: Codex = StringCodex, fourth: Codex = StringCodex,
    fifth: Codex = StringCodex): TypedPipe[Cell[Position5D]] = {
    TypedPipe.from(TextLine(file)).flatMap { Cell.parse5D(separator, first, second, third, fourth, fifth)(_) }
  }

  /**
   * Read column oriented, pipe separated data into a `TypedPipe[Cell[Position5D]]`.
   *
   * @param file      The file to read from.
   * @param dict      The dictionary describing the features in the data.
   * @param dim       The dimension on which to apply the dictionary.
   * @param separator The column separator.
   * @param first     The codex for decoding the first dimension.
   * @param second    The codex for decoding the second dimension.
   * @param third     The codex for decoding the third dimension.
   * @param fourth    The codex for decoding the fourth dimension.
   * @param fifth     The codex for decoding the fifth dimension.
   */
  def load5DWithDictionary[D <: Dimension](file: String, dict: Map[String, Schema], dim: D = Second,
    separator: String = "|", first: Codex = StringCodex, second: Codex = StringCodex, third: Codex = StringCodex,
    fourth: Codex = StringCodex, fifth: Codex = StringCodex)(
    implicit ev: PosDimDep[Position5D, D]): TypedPipe[Cell[Position5D]] = {
    TypedPipe.from(TextLine(file)).flatMap {
      Cell.parse5DWithDictionary(dict, dim, separator, first, second, third, fourth, fifth)(_)
    }
  }

  /**
   * Read column oriented, pipe separated data into a `TypedPipe[Cell[Position5D]]`.
   *
   * @param file      The file to read from.
   * @param schema    The schema for decoding the data.
   * @param separator The column separator.
   * @param first     The codex for decoding the first dimension.
   * @param second    The codex for decoding the second dimension.
   * @param third     The codex for decoding the third dimension.
   * @param fourth    The codex for decoding the fourth dimension.
   * @param fifth     The codex for decoding the fifth dimension.
   */
  def load5DWithSchema(file: String, schema: Schema, separator: String = "|", first: Codex = StringCodex,
    second: Codex = StringCodex, third: Codex = StringCodex, fourth: Codex = StringCodex,
    fifth: Codex = StringCodex): TypedPipe[Cell[Position5D]] = {
    TypedPipe.from(TextLine(file)).flatMap {
      Cell.parse5DWithSchema(schema, separator, first, second, third, fourth, fifth)(_)
    }
  }

  /**
   * Read tabled data into a `TypedPipe[Cell[Position2D]]`.
   *
   * @param table     The file (table) to read from.
   * @param columns   `List[(String, Schema)]` describing each column in the table.
   * @param pkeyIndex Index (into `columns`) describing which column is the primary key.
   * @param separator The column separator.
   *
   * @note The returned `Position2D` consists of 2 string values. The first string value is the contents of the primary
   *       key column. The second string value is the name of the column.
   */
  def loadTable(table: String, columns: List[(String, Schema)], pkeyIndex: Int = 0,
    separator: String = "\u0001"): TypedPipe[Cell[Position2D]] = {
    TypedPipe.from(TextLine(table)).flatMap { Cell.parseTable(columns, pkeyIndex, separator)(_) }
  }

  /** Conversion from `TypedPipe[Cell[Position1D]]` to a Scalding `Matrix1D`. */
  implicit def TP2M1(data: TypedPipe[Cell[Position1D]]): Matrix1D = new Matrix1D(data)
  /** Conversion from `TypedPipe[Cell[Position2D]]` to a Scalding `Matrix2D`. */
  implicit def TP2M2(data: TypedPipe[Cell[Position2D]]): Matrix2D = new Matrix2D(data)
  /** Conversion from `TypedPipe[Cell[Position3D]]` to a Scalding `Matrix3D`. */
  implicit def TP2M3(data: TypedPipe[Cell[Position3D]]): Matrix3D = new Matrix3D(data)
  /** Conversion from `TypedPipe[Cell[Position4D]]` to a Scalding `Matrix4D`. */
  implicit def TP2TM4(data: TypedPipe[Cell[Position4D]]): Matrix4D = new Matrix4D(data)
  /** Conversion from `TypedPipe[Cell[Position5D]]` to a Scalding `Matrix5D`. */
  implicit def TP2M5(data: TypedPipe[Cell[Position5D]]): Matrix5D = new Matrix5D(data)
  /** Conversion from `TypedPipe[Cell[Position6D]]` to a Scalding `Matrix6D`. */
  implicit def TP2M6(data: TypedPipe[Cell[Position6D]]): Matrix6D = new Matrix6D(data)
  /** Conversion from `TypedPipe[Cell[Position7D]]` to a Scalding `Matrix7D`. */
  implicit def TP2M7(data: TypedPipe[Cell[Position7D]]): Matrix7D = new Matrix7D(data)
  /** Conversion from `TypedPipe[Cell[Position8D]]` to a Scalding `Matrix8D`. */
  implicit def TP2M8(data: TypedPipe[Cell[Position8D]]): Matrix8D = new Matrix8D(data)
  /** Conversion from `TypedPipe[Cell[Position9D]]` to a Scalding `Matrix9D`. */
  implicit def TP2M9(data: TypedPipe[Cell[Position9D]]): Matrix9D = new Matrix9D(data)

  /** Conversion from `List[(Valueable, Content)]` to a Scalding `Matrix1D`. */
  implicit def LV1C2M1[V: Valueable](list: List[(V, Content)]): Matrix1D = {
    new Matrix1D(new IterablePipe(list.map { case (v, c) => Cell(Position1D(v), c) }))
  }
  /** Conversion from `List[(Valueable, Valueable, Content)]` to a Scalding `Matrix2D`. */
  implicit def LV2C2M2[V: Valueable, W: Valueable](list: List[(V, W, Content)]): Matrix2D = {
    new Matrix2D(new IterablePipe(list.map { case (v, w, c) => Cell(Position2D(v, w), c) }))
  }
  /** Conversion from `List[(Valueable, Valueable, Valueable, Content)]` to a Scalding `Matrix3D`. */
  implicit def LV3C2M3[V: Valueable, W: Valueable, X: Valueable](list: List[(V, W, X, Content)]): Matrix3D = {
    new Matrix3D(new IterablePipe(list.map { case (v, w, x, c) => Cell(Position3D(v, w, x), c) }))
  }
  /** Conversion from `List[(Valueable, Valueable, Valueable, Valueable, Content)]` to a Scalding `Matrix4D`. */
  implicit def LV4C2M4[V: Valueable, W: Valueable, X: Valueable, Y: Valueable](
    list: List[(V, W, X, Y, Content)]): Matrix4D = {
    new Matrix4D(new IterablePipe(list.map { case (v, w, x, y, c) => Cell(Position4D(v, w, x, y), c) }))
  }
  /**
   * Conversion from `List[(Valueable, Valueable, Valueable, Valueable, Valueable, Content)]` to a Scalding `Matrix5D`.
   */
  implicit def LV5C2M5[V: Valueable, W: Valueable, X: Valueable, Y: Valueable, Z: Valueable](
    list: List[(V, W, X, Y, Z, Content)]): Matrix5D = {
    new Matrix5D(new IterablePipe(list.map { case (v, w, x, y, z, c) => Cell(Position5D(v, w, x, y, z), c) }))
  }
  /**
   * Conversion from `List[(Valueable, Valueable, Valueable, Valueable, Valueable, Valueable, Content)]` to a
   * Scalding `Matrix6D`.
   */
  implicit def LV6C2M6[U: Valueable, V: Valueable, W: Valueable, X: Valueable, Y: Valueable, Z: Valueable](
    list: List[(U, V, W, X, Y, Z, Content)]): Matrix6D = {
    new Matrix6D(new IterablePipe(list.map { case (u, v, w, x, y, z, c) => Cell(Position6D(u, v, w, x, y, z), c) }))
  }
  /**
   * Conversion from `List[(Valueable, Valueable, Valueable, Valueable, Valueable, Valueable, Valueable, Content)]`
   * to a Scalding `Matrix7D`.
   */
  implicit def LV7C2M7[T: Valueable, U: Valueable, V: Valueable, W: Valueable, X: Valueable, Y: Valueable, Z: Valueable](
    list: List[(T, U, V, W, X, Y, Z, Content)]): Matrix7D = {
    new Matrix7D(new IterablePipe(list.map {
      case (t, u, v, w, x, y, z, c) => Cell(Position7D(t, u, v, w, x, y, z), c)
    }))
  }
  /**
   * Conversion from `List[(Valueable, Valueable, Valueable, Valueable, Valueable, Valueable, Valueable, Valueable,
   * Content)]` to a Scalding `Matrix8D`.
   */
  implicit def LV8C2M8[S: Valueable, T: Valueable, U: Valueable, V: Valueable, W: Valueable, X: Valueable, Y: Valueable, Z: Valueable](
    list: List[(S, T, U, V, W, X, Y, Z, Content)]): Matrix8D = {
    new Matrix8D(new IterablePipe(list.map {
      case (s, t, u, v, w, x, y, z, c) => Cell(Position8D(s, t, u, v, w, x, y, z), c)
    }))
  }
  /**
   * Conversion from `List[(Valueable, Valueable, Valueable, Valueable, Valueable, Valueable, Valueable, Valueable,
   * Valueable, Content)]` to a Scalding `Matrix9D`.
   */
  implicit def LV9C2M9[R: Valueable, S: Valueable, T: Valueable, U: Valueable, V: Valueable, W: Valueable, X: Valueable, Y: Valueable, Z: Valueable](
    list: List[(R, S, T, U, V, W, X, Y, Z, Content)]): Matrix9D = {
    new Matrix9D(new IterablePipe(list.map {
      case (r, s, t, u, v, w, x, y, z, c) => Cell(Position9D(r, s, t, u, v, w, x, y, z), c)
    }))
  }
}

/**
 * Rich wrapper around a `TypedPipe[Cell[Position1D]]`.
 *
 * @param data `TypedPipe[Cell[Position1D]]`.
 */
class Matrix1D(val data: TypedPipe[Cell[Position1D]]) extends Matrix[Position1D] with ExpandableMatrix[Position1D] {
  def domain(): U[Position1D] = names(Over(First)).map { case (p, i) => p }

  /**
   * Persist a `Matrix1D` as sparse matrix file (index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position1D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|")(implicit flow: FlowDef,
    mode: Mode): U[Cell[Position1D]] = {
    saveAsIVWithNames(file, names(Over(First)), dictionary, separator)
  }

  /**
   * Persist a `Matrix1D` as sparse matrix file (index, value).
   *
   * @param file       File to write to.
   * @param names      The names to use for the first dimension (according to their ordering).
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position1D]]`; that is it returns `data`.
   *
   * @note If `names` contains a subset of the columns, then only those columns get persisted to file.
   */
  def saveAsIVWithNames(file: String, names: U[(Position1D, Long)], dictionary: String = "%1$s.dict.%2$d",
    separator: String = "|")(implicit flow: FlowDef, mode: Mode): U[Cell[Position1D]] = {
    data
      .groupBy { case c => c.position }
      .join(saveDictionary(names, file, dictionary, separator, First))
      .map { case (_, (c, i)) => i + separator + c.content.value.toShortString }
      .write(TypedSink(TextLine(file)))

    data
  }
}

/**
 * Rich wrapper around a `TypedPipe[Cell[Position2D]]`.
 *
 * @param data `TypedPipe[Cell[Position2D]]`.
 */
class Matrix2D(val data: TypedPipe[Cell[Position2D]]) extends Matrix[Position2D] with ReduceableMatrix[Position2D]
  with ExpandableMatrix[Position2D] with MatrixDistance {
  def domain(): U[Position2D] = {
    names(Over(First))
      .map { case (Position1D(c), i) => c }
      .cross(names(Over(Second)).map { case (Position1D(c), i) => c })
      .map { case (c1, c2) => Position2D(c1, c2) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first  Dimension used for the first coordinate.
   * @param second Dimension used for the second coordinate.
   */
  def permute[D <: Dimension, F <: Dimension](first: D, second: F)(implicit ev1: PosDimDep[Position2D, D],
    ev2: PosDimDep[Position2D, F], ev3: D =!= F): U[Cell[Position2D]] = {
    data.map { case Cell(p, c) => Cell(p.permute(List(first, second)), c) }
  }

  /**
   * Persist a `Matrix2D` as a CSV file.
   *
   * @param slice       Encapsulates the dimension that makes up the columns.
   * @param file        File to write to.
   * @param separator   Column separator to use.
   * @param escapee     The method for escaping the separator character.
   * @param writeHeader Indicator of the header should be written to a separate file.
   * @param header      Postfix for the header file name.
   * @param writeRowId  Indicator if row names should be written.
   * @param rowId       Column name of row names.
   *
   * @return A `TypedPipe[Cell[Position2D]]`; that is it returns `data`.
   */
  def saveAsCSV[D <: Dimension](slice: Slice[Position2D, D], file: String, separator: String = "|",
    escapee: Escape = Quote(), writeHeader: Boolean = true, header: String = "%s.header", writeRowId: Boolean = true,
    rowId: String = "id")(implicit ev1: BaseNameable[TypedPipe[(slice.S, Long)], Position2D, slice.S, D, TypedPipe],
      ev2: PosDimDep[Position2D, D], ev3: ClassTag[slice.S], flow: FlowDef, mode: Mode): U[Cell[Position2D]] = {
    saveAsCSVWithNames(slice, file, names(slice), separator, escapee, writeHeader, header, writeRowId, rowId)
  }

  /**
   * Persist a `Matrix2D` as a CSV file.
   *
   * @param slice       Encapsulates the dimension that makes up the columns.
   * @param file        File to write to.
   * @param names       The names to use for the columns (according to their ordering).
   * @param separator   Column separator to use.
   * @param escapee     The method for escaping the separator character.
   * @param writeHeader Indicator of the header should be written to a separate file.
   * @param header      Postfix for the header file name.
   * @param writeRowId  Indicator if row names should be written.
   * @param rowId       Column name of row names.
   *
   * @return A `TypedPipe[Cell[Position2D]]`; that is it returns `data`.
   *
   * @note If `names` contains a subset of the columns, then only those columns get persisted to file.
   */
  def saveAsCSVWithNames[T, D <: Dimension](slice: Slice[Position2D, D], file: String, names: T,
    separator: String = "|", escapee: Escape = Quote(), writeHeader: Boolean = true, header: String = "%s.header",
    writeRowId: Boolean = true, rowId: String = "id")(implicit ev1: BaseNameable[T, Position2D, slice.S, D, TypedPipe],
      ev2: PosDimDep[Position2D, D], ev3: ClassTag[slice.S], flow: FlowDef, mode: Mode): U[Cell[Position2D]] = {
    // Note: Usage of .toShortString should be safe as data is written as string anyways. It does assume that all
    //       indices have unique short string representations.
    val columns = ev1.convert(this, slice, names)
      .map { List(_) }
      .sum
      .map { _.sortBy(_._2).map { case (p, i) => escapee.escape(p.toShortString(""), separator) } }

    if (writeHeader) {
      columns
        .map {
          case lst => (if (writeRowId) escapee.escape(rowId, separator) + separator else "") + lst.mkString(separator)
        }
        .write(TypedSink(TextLine(header.format(file))))
    }

    data
      .groupBy { case c => slice.remainder(c.position).toShortString("") }
      .mapValues {
        case Cell(p, c) => Map(escapee.escape(slice.selected(p).toShortString(""), separator) ->
          escapee.escape(c.value.toShortString, separator))
      }
      .sum
      .flatMapWithValue(columns) {
        case ((key, values), optCols) => optCols.map {
          case cols => (key, cols.map { case c => values.getOrElse(c, "") })
        }
      }
      .map {
        case (i, lst) => (if (writeRowId) escapee.escape(i, separator) + separator else "") + lst.mkString(separator)
      }
      .write(TypedSink(TextLine(file)))

    data
  }

  /**
   * Persist a `Matrix2D` as sparse matrix file (index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position2D]]`; that is it returns `data`.
   *
   * @note R's slam package has a simple triplet matrix format (which in turn is used by the tm package). This format
   *       should be compatible.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|")(implicit flow: FlowDef,
    mode: Mode): U[Cell[Position2D]] = {
    saveAsIVWithNames(file, names(Over(First)), names(Over(Second)), dictionary, separator)
  }

  /**
   * Persist a `Matrix2D` as sparse matrix file (index, index, value).
   *
   * @param file       File to write to.
   * @param namesI     The names to use for the first dimension (according to their ordering).
   * @param namesJ     The names to use for the second dimension (according to their ordering).
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position2D]]`; that is it returns `data`.
   *
   * @note If `names` contains a subset of the columns, then only those columns get persisted to file.
   * @note R's slam package has a simple triplet matrix format (which in turn is used by the tm package). This format
   *       should be compatible.
   */
  def saveAsIVWithNames(file: String, namesI: U[(Position1D, Long)], namesJ: U[(Position1D, Long)],
    dictionary: String = "%1$s.dict.%2$d", separator: String = "|")(implicit flow: FlowDef,
      mode: Mode): U[Cell[Position2D]] = {
    data
      .groupBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(namesI, file, dictionary, separator, First))
      .values
      .groupBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(namesJ, file, dictionary, separator, Second))
      .map { case (_, ((c, i), j)) => i + separator + j + separator + c.content.value.toShortString }
      .write(TypedSink(TextLine(file)))

    data
  }

  /**
   * Persist a `Matrix2D` as a LDA file.
   *
   * @param slice      Encapsulates the dimension that makes up the columns.
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   * @param addId      Indicator if each line should start with the row id followed by `separator`.
   *
   * @return A `TypedPipe[Cell[Position2D]]`; that is it returns `data`.
   */
  def saveAsLDA[D <: Dimension](slice: Slice[Position2D, D], file: String, dictionary: String = "%s.dict",
    separator: String = "|", addId: Boolean = false)(implicit ev: PosDimDep[Position2D, D], flow: FlowDef,
      mode: Mode): U[Cell[Position2D]] = {
    saveAsLDAWithNames(slice, file, names(Along(slice.dimension)), dictionary, separator, addId)
  }

  /**
   * Persist a `Matrix2D` as a LDA file.
   *
   * @param slice      Encapsulates the dimension that makes up the columns.
   * @param file       File to write to.
   * @param names      The names to use for the columns (according to their ordering).
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   * @param addId      Indicator if each line should start with the row id followed by `separator`.
   *
   * @return A `TypedPipe[Cell[Position2D]]`; that is it returns `data`.
   *
   * @note If `names` contains a subset of the columns, then only those columns get persisted to file.
   */
  def saveAsLDAWithNames[D <: Dimension](slice: Slice[Position2D, D], file: String, names: U[(Position1D, Long)],
    dictionary: String = "%s.dict", separator: String = "|", addId: Boolean = false)(
      implicit ev: PosDimDep[Position2D, D], flow: FlowDef, mode: Mode): U[Cell[Position2D]] = {
    data
      .groupBy { case c => slice.remainder(c.position).asInstanceOf[Position1D] }
      .join(saveDictionary(names, file, dictionary, separator))
      .map { case (_, (Cell(p, c), i)) => (p, " " + i + ":" + c.value.toShortString, 1L) }
      .groupBy { case (p, ics, m) => slice.selected(p) }
      .reduce[(Position2D, String, Long)] { case ((p, ls, lm), (_, rs, rm)) => (p, ls + rs, lm + rm) }
      .map { case (p, (_, ics, m)) => if (addId) p.toShortString(separator) + separator + m + ics else m + ics }
      .write(TypedSink(TextLine(file)))

    data
  }

  /**
   * Persist a `Matrix2D` as a Vowpal Wabbit file.
   *
   * @param slice      Encapsulates the dimension that makes up the columns.
   * @param labels     The labels to write with.
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position2D]]`; that is it returns `data`.
   */
  def saveAsVW[D <: Dimension](slice: Slice[Position2D, D], labels: U[Cell[Position1D]], file: String,
    dictionary: String = "%s.dict", separator: String = ":")(implicit ev: PosDimDep[Position2D, D], flow: FlowDef,
      mode: Mode): U[Cell[Position2D]] = {
    saveAsVWWithNames(slice, labels, file, names(Along(slice.dimension)), dictionary, separator)
  }

  /**
   * Persist a `Matrix2D` as a Vowpal Wabbit file.
   *
   * @param slice      Encapsulates the dimension that makes up the columns.
   * @param labels     The labels to write with.
   * @param file       File to write to.
   * @param names      The names to use for the columns (according to their ordering).
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position2D]]`; that is it returns `data`.
   *
   * @note If `names` contains a subset of the columns, then only those columns get persisted to file.
   */
  def saveAsVWWithNames[D <: Dimension](slice: Slice[Position2D, D], labels: U[Cell[Position1D]], file: String,
    names: U[(Position1D, Long)], dictionary: String = "%s.dict", separator: String = ":")(
      implicit ev: PosDimDep[Position2D, D], flow: FlowDef, mode: Mode): U[Cell[Position2D]] = {
    data
      .groupBy { case c => slice.remainder(c.position).asInstanceOf[Position1D] }
      .join(saveDictionary(names, file, dictionary, separator))
      .map { case (_, (Cell(p, c), i)) => (p, " " + i + ":" + c.value.toShortString) }
      .groupBy { case (p, ics) => slice.selected(p).asInstanceOf[Position1D] }
      .reduce[(Position2D, String)] { case ((p, ls), (_, rs)) => (p, ls + rs) }
      .join(labels.groupBy { case c => c.position })
      .map { case (p, ((_, ics), c)) => c.content.value.toShortString + " " + p.toShortString(separator) + "|" + ics }
      .write(TypedSink(TextLine(file)))

    data
  }
}

/**
 * Rich wrapper around a `TypedPipe[Cell[Position3D]]`.
 *
 * @param data `TypedPipe[Cell[Position3D]]`.
 */
class Matrix3D(val data: TypedPipe[Cell[Position3D]]) extends Matrix[Position3D] with ReduceableMatrix[Position3D]
  with ExpandableMatrix[Position3D] {
  def domain(): U[Position3D] = {
    names(Over(First))
      .map { case (Position1D(c), i) => c }
      .cross(names(Over(Second)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Third)).map { case (Position1D(c), i) => c })
      .map { case ((c1, c2), c3) => Position3D(c1, c2, c3) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first  Dimension used for the first coordinate.
   * @param second Dimension used for the second coordinate.
   * @param third  Dimension used for the third coordinate.
   */
  def permute[D <: Dimension, F <: Dimension, G <: Dimension](first: D, second: F, third: G)(
    implicit ev1: PosDimDep[Position3D, D], ev2: PosDimDep[Position3D, F], ev3: PosDimDep[Position3D, G],
    ev4: Distinct3[D, F, G]): U[Cell[Position3D]] = {
    data.map { case Cell(p, c) => Cell(p.permute(List(first, second, third)), c) }
  }

  /**
   * Persist a `Matrix3D` as sparse matrix file (index, index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position3D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|")(implicit flow: FlowDef,
    mode: Mode): U[Cell[Position3D]] = {
    saveAsIVWithNames(file, names(Over(First)), names(Over(Second)), names(Over(Third)), dictionary, separator)
  }

  /**
   * Persist a `Matrix3D` as sparse matrix file (index, index, index, value).
   *
   * @param file       File to write to.
   * @param namesI     The names to use for the first dimension (according to their ordering).
   * @param namesJ     The names to use for the second dimension (according to their ordering).
   * @param namesK     The names to use for the third dimension (according to their ordering).
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position3D]]`; that is it returns `data`.
   *
   * @note If `names` contains a subset of the columns, then only those columns get persisted to file.
   */
  def saveAsIVWithNames(file: String, namesI: U[(Position1D, Long)], namesJ: U[(Position1D, Long)],
    namesK: U[(Position1D, Long)], dictionary: String = "%1$s.dict.%2$d", separator: String = "|")(
      implicit flow: FlowDef, mode: Mode): U[Cell[Position3D]] = {
    data
      .groupBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(namesI, file, dictionary, separator, First))
      .values
      .groupBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(namesJ, file, dictionary, separator, Second))
      .map { case (_, ((c, i), j)) => (c, i, j) }
      .groupBy { case (c, i, j) => Position1D(c.position(Third)) }
      .join(saveDictionary(namesK, file, dictionary, separator, Third))
      .map { case (_, ((c, i, j), k)) => i + separator + j + separator + k + separator + c.content.value.toShortString }
      .write(TypedSink(TextLine(file)))

    data
  }
}

/**
 * Rich wrapper around a `TypedPipe[Cell[Position4D]]`.
 *
 * @param data `TypedPipe[Cell[Position4D]]`.
 */
class Matrix4D(val data: TypedPipe[Cell[Position4D]]) extends Matrix[Position4D] with ReduceableMatrix[Position4D]
  with ExpandableMatrix[Position4D] {
  def domain(): U[Position4D] = {
    names(Over(First))
      .map { case (Position1D(c), i) => c }
      .cross(names(Over(Second)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Third)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Fourth)).map { case (Position1D(c), i) => c })
      .map { case (((c1, c2), c3), c4) => Position4D(c1, c2, c3, c4) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first  Dimension used for the first coordinate.
   * @param second Dimension used for the second coordinate.
   * @param third  Dimension used for the third coordinate.
   * @param fourth Dimension used for the fourth coordinate.
   */
  def permute[D <: Dimension, F <: Dimension, G <: Dimension, H <: Dimension](first: D, second: F, third: G,
    fourth: H)(implicit ev1: PosDimDep[Position4D, D], ev2: PosDimDep[Position4D, F], ev3: PosDimDep[Position4D, G],
      ev4: PosDimDep[Position4D, H], ev5: Distinct4[D, F, G, H]): U[Cell[Position4D]] = {
    data.map { case Cell(p, c) => Cell(p.permute(List(first, second, third, fourth)), c) }
  }

  /**
   * Persist a `Matrix4D` as sparse matrix file (index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position4D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|")(implicit flow: FlowDef,
    mode: Mode): U[Cell[Position4D]] = {
    saveAsIVWithNames(file, names(Over(First)), names(Over(Second)), names(Over(Third)), names(Over(Fourth)),
      dictionary, separator)
  }

  /**
   * Persist a `Matrix4D` as sparse matrix file (index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param namesI     The names to use for the first dimension (according to their ordering).
   * @param namesJ     The names to use for the second dimension (according to their ordering).
   * @param namesK     The names to use for the third dimension (according to their ordering).
   * @param namesL     The names to use for the fourth dimension (according to their ordering).
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position4D]]`; that is it returns `data`.
   *
   * @note If `names` contains a subset of the columns, then only those columns get persisted to file.
   */
  def saveAsIVWithNames(file: String, namesI: U[(Position1D, Long)], namesJ: U[(Position1D, Long)],
    namesK: U[(Position1D, Long)], namesL: U[(Position1D, Long)], dictionary: String = "%1$s.dict.%2$d",
    separator: String = "|")(implicit flow: FlowDef, mode: Mode): U[Cell[Position4D]] = {
    data
      .groupBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(namesI, file, dictionary, separator, First))
      .values
      .groupBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(namesJ, file, dictionary, separator, Second))
      .map { case (_, ((c, i), j)) => (c, i, j) }
      .groupBy { case (c, i, j) => Position1D(c.position(Third)) }
      .join(saveDictionary(namesK, file, dictionary, separator, Third))
      .map { case (_, ((c, i, j), k)) => (c, i, j, k) }
      .groupBy { case (c, i, j, k) => Position1D(c.position(Fourth)) }
      .join(saveDictionary(namesL, file, dictionary, separator, Fourth))
      .map {
        case (_, ((c, i, j, k), l)) =>
          i + separator + j + separator + k + separator + l + separator + c.content.value.toShortString
      }
      .write(TypedSink(TextLine(file)))

    data
  }
}

/**
 * Rich wrapper around a `TypedPipe[Cell[Position5D]]`.
 *
 * @param data `TypedPipe[Cell[Position5D]]`.
 */
class Matrix5D(val data: TypedPipe[Cell[Position5D]]) extends Matrix[Position5D] with ReduceableMatrix[Position5D]
  with ExpandableMatrix[Position5D] {
  def domain(): U[Position5D] = {
    names(Over(First))
      .map { case (Position1D(c), i) => c }
      .cross(names(Over(Second)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Third)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Fourth)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Fifth)).map { case (Position1D(c), i) => c })
      .map { case ((((c1, c2), c3), c4), c5) => Position5D(c1, c2, c3, c4, c5) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first  Dimension used for the first coordinate.
   * @param second Dimension used for the second coordinate.
   * @param third  Dimension used for the third coordinate.
   * @param fourth Dimension used for the fourth coordinate.
   * @param fifth  Dimension used for the fifth coordinate.
   */
  def permute[D <: Dimension, F <: Dimension, G <: Dimension, H <: Dimension, I <: Dimension](first: D, second: F,
    third: G, fourth: H, fifth: I)(implicit ev1: PosDimDep[Position5D, D], ev2: PosDimDep[Position5D, F],
      ev3: PosDimDep[Position5D, G], ev4: PosDimDep[Position5D, H], ev5: PosDimDep[Position5D, I],
      ev6: Distinct5[D, F, G, H, I]): U[Cell[Position5D]] = {
    data.map { case Cell(p, c) => Cell(p.permute(List(first, second, third, fourth, fifth)), c) }
  }

  /**
   * Persist a `Matrix5D` as sparse matrix file (index, index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position5D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|")(implicit flow: FlowDef,
    mode: Mode): U[Cell[Position5D]] = {
    saveAsIVWithNames(file, names(Over(First)), names(Over(Second)), names(Over(Third)), names(Over(Fourth)),
      names(Over(Fifth)), dictionary, separator)
  }

  /**
   * Persist a `Matrix5D` as sparse matrix file (index, index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param namesI     The names to use for the first dimension (according to their ordering).
   * @param namesJ     The names to use for the second dimension (according to their ordering).
   * @param namesK     The names to use for the third dimension (according to their ordering).
   * @param namesL     The names to use for the fourth dimension (according to their ordering).
   * @param namesM     The names to use for the fifth dimension (according to their ordering).
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position5D]]`; that is it returns `data`.
   *
   * @note If `names` contains a subset of the columns, then only those columns get persisted to file.
   */
  def saveAsIVWithNames(file: String, namesI: U[(Position1D, Long)], namesJ: U[(Position1D, Long)],
    namesK: U[(Position1D, Long)], namesL: U[(Position1D, Long)], namesM: U[(Position1D, Long)],
    dictionary: String = "%1$s.dict.%2$d", separator: String = "|")(implicit flow: FlowDef,
      mode: Mode): U[Cell[Position5D]] = {
    data
      .groupBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(namesI, file, dictionary, separator, First))
      .values
      .groupBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(namesJ, file, dictionary, separator, Second))
      .map { case (_, ((c, i), j)) => (c, i, j) }
      .groupBy { case (c, i, j) => Position1D(c.position(Third)) }
      .join(saveDictionary(namesK, file, dictionary, separator, Third))
      .map { case (_, ((c, i, j), k)) => (c, i, j, k) }
      .groupBy { case (c, i, j, k) => Position1D(c.position(Fourth)) }
      .join(saveDictionary(namesL, file, dictionary, separator, Fourth))
      .map { case (_, ((c, i, j, k), l)) => (c, i, j, k, l) }
      .groupBy { case (c, i, j, k, l) => Position1D(c.position(Fifth)) }
      .join(saveDictionary(namesM, file, dictionary, separator, Fifth))
      .map {
        case (_, ((c, i, j, k, l), m)) =>
          i + separator + j + separator + k + separator + l + separator + m + separator + c.content.value.toShortString
      }
      .write(TypedSink(TextLine(file)))

    data
  }
}

/**
 * Rich wrapper around a `TypedPipe[Cell[Position6D]]`.
 *
 * @param data `TypedPipe[Cell[Position6D]]`.
 */
class Matrix6D(val data: TypedPipe[Cell[Position6D]]) extends Matrix[Position6D] with ReduceableMatrix[Position6D]
  with ExpandableMatrix[Position6D] {
  def domain(): U[Position6D] = {
    names(Over(First))
      .map { case (Position1D(c), i) => c }
      .cross(names(Over(Second)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Third)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Fourth)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Fifth)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Sixth)).map { case (Position1D(c), i) => c })
      .map { case (((((c1, c2), c3), c4), c5), c6) => Position6D(c1, c2, c3, c4, c5, c6) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first  Dimension used for the first coordinate.
   * @param second Dimension used for the second coordinate.
   * @param third  Dimension used for the third coordinate.
   * @param fourth Dimension used for the fourth coordinate.
   * @param fifth  Dimension used for the fifth coordinate.
   * @param sixth  Dimension used for the sixth coordinate.
   */
  def permute[D <: Dimension, F <: Dimension, G <: Dimension, H <: Dimension, I <: Dimension, J <: Dimension](
    first: D, second: F, third: G, fourth: H, fifth: I, sixth: J)(implicit ev1: PosDimDep[Position6D, D],
      ev2: PosDimDep[Position6D, F], ev3: PosDimDep[Position6D, G], ev4: PosDimDep[Position6D, H],
      ev5: PosDimDep[Position6D, I], ev6: PosDimDep[Position6D, J],
      ev7: Distinct6[D, F, G, H, I, J]): U[Cell[Position6D]] = {
    data.map { case Cell(p, c) => Cell(p.permute(List(first, second, third, fourth, fifth, sixth)), c) }
  }

  /**
   * Persist a `Matrix6D` as sparse matrix file (index, index, index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position6D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|")(implicit flow: FlowDef,
    mode: Mode): U[Cell[Position6D]] = {
    saveAsIVWithNames(file, names(Over(First)), names(Over(Second)), names(Over(Third)), names(Over(Fourth)),
      names(Over(Fifth)), names(Over(Sixth)), dictionary, separator)
  }

  /**
   * Persist a `Matrix6D` as sparse matrix file (index, index, index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param namesI     The names to use for the first dimension (according to their ordering).
   * @param namesJ     The names to use for the second dimension (according to their ordering).
   * @param namesK     The names to use for the third dimension (according to their ordering).
   * @param namesL     The names to use for the fourth dimension (according to their ordering).
   * @param namesM     The names to use for the fifth dimension (according to their ordering).
   * @param namesN     The names to use for the sixth dimension (according to their ordering).
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position6D]]`; that is it returns `data`.
   *
   * @note If `names` contains a subset of the columns, then only those columns get persisted to file.
   */
  def saveAsIVWithNames(file: String, namesI: U[(Position1D, Long)], namesJ: U[(Position1D, Long)],
    namesK: U[(Position1D, Long)], namesL: U[(Position1D, Long)], namesM: U[(Position1D, Long)],
    namesN: U[(Position1D, Long)], dictionary: String = "%1$s.dict.%2$d", separator: String = "|")(
      implicit flow: FlowDef, mode: Mode): U[Cell[Position6D]] = {
    data
      .groupBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(namesI, file, dictionary, separator, First))
      .values
      .groupBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(namesJ, file, dictionary, separator, Second))
      .map { case (_, ((c, i), j)) => (c, i, j) }
      .groupBy { case (c, i, j) => Position1D(c.position(Third)) }
      .join(saveDictionary(namesK, file, dictionary, separator, Third))
      .map { case (_, ((c, i, j), k)) => (c, i, j, k) }
      .groupBy { case (c, i, j, k) => Position1D(c.position(Fourth)) }
      .join(saveDictionary(namesL, file, dictionary, separator, Fourth))
      .map { case (_, ((c, i, j, k), l)) => (c, i, j, k, l) }
      .groupBy { case (c, i, j, k, l) => Position1D(c.position(Fifth)) }
      .join(saveDictionary(namesM, file, dictionary, separator, Fifth))
      .map { case (_, ((c, i, j, k, l), m)) => (c, i, j, k, l, m) }
      .groupBy { case (c, i, j, k, l, m) => Position1D(c.position(Sixth)) }
      .join(saveDictionary(namesN, file, dictionary, separator, Sixth))
      .map {
        case (_, ((c, i, j, k, l, m), n)) =>
          i + separator + j + separator + k + separator + l + separator + m + separator +
            n + separator + c.content.value.toShortString
      }
      .write(TypedSink(TextLine(file)))

    data
  }
}

/**
 * Rich wrapper around a `TypedPipe[Cell[Position7D]]`.
 *
 * @param data `TypedPipe[Cell[Position7D]]`.
 */
class Matrix7D(val data: TypedPipe[Cell[Position7D]]) extends Matrix[Position7D] with ReduceableMatrix[Position7D]
  with ExpandableMatrix[Position7D] {
  def domain(): U[Position7D] = {
    names(Over(First))
      .map { case (Position1D(c), i) => c }
      .cross(names(Over(Second)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Third)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Fourth)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Fifth)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Sixth)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Seventh)).map { case (Position1D(c), i) => c })
      .map { case ((((((c1, c2), c3), c4), c5), c6), c7) => Position7D(c1, c2, c3, c4, c5, c6, c7) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first   Dimension used for the first coordinate.
   * @param second  Dimension used for the second coordinate.
   * @param third   Dimension used for the third coordinate.
   * @param fourth  Dimension used for the fourth coordinate.
   * @param fifth   Dimension used for the fifth coordinate.
   * @param sixth   Dimension used for the sixth coordinate.
   * @param seventh Dimension used for the seventh coordinate.
   */
  def permute[D <: Dimension, F <: Dimension, G <: Dimension, H <: Dimension, I <: Dimension, J <: Dimension, K <: Dimension](
    first: D, second: F, third: G, fourth: H, fifth: I, sixth: J, seventh: K)(implicit ev1: PosDimDep[Position7D, D],
      ev2: PosDimDep[Position7D, F], ev3: PosDimDep[Position7D, G], ev4: PosDimDep[Position7D, H],
      ev5: PosDimDep[Position7D, I], ev6: PosDimDep[Position7D, J], ev7: PosDimDep[Position7D, K],
      ev8: Distinct7[D, F, G, H, I, J, K]): U[Cell[Position7D]] = {
    data.map { case Cell(p, c) => Cell(p.permute(List(first, second, third, fourth, fifth, sixth, seventh)), c) }
  }

  /**
   * Persist a `Matrix7D` as sparse matrix file (index, index, index, index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position7D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|")(implicit flow: FlowDef,
    mode: Mode): U[Cell[Position7D]] = {
    saveAsIVWithNames(file, names(Over(First)), names(Over(Second)), names(Over(Third)), names(Over(Fourth)),
      names(Over(Fifth)), names(Over(Sixth)), names(Over(Seventh)), dictionary, separator)
  }

  /**
   * Persist a `Matrix7D` as sparse matrix file (index, index, index, index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param namesI     The names to use for the first dimension (according to their ordering).
   * @param namesJ     The names to use for the second dimension (according to their ordering).
   * @param namesK     The names to use for the third dimension (according to their ordering).
   * @param namesL     The names to use for the fourth dimension (according to their ordering).
   * @param namesM     The names to use for the fifth dimension (according to their ordering).
   * @param namesN     The names to use for the sixth dimension (according to their ordering).
   * @param namesO     The names to use for the seventh dimension (according to their ordering).
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position7D]]`; that is it returns `data`.
   *
   * @note If `names` contains a subset of the columns, then only those columns get persisted to file.
   */
  def saveAsIVWithNames(file: String, namesI: U[(Position1D, Long)], namesJ: U[(Position1D, Long)],
    namesK: U[(Position1D, Long)], namesL: U[(Position1D, Long)], namesM: U[(Position1D, Long)],
    namesN: U[(Position1D, Long)], namesO: U[(Position1D, Long)], dictionary: String = "%1$s.dict.%2$d",
    separator: String = "|")(implicit flow: FlowDef, mode: Mode): U[Cell[Position7D]] = {
    data
      .groupBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(namesI, file, dictionary, separator, First))
      .values
      .groupBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(namesJ, file, dictionary, separator, Second))
      .map { case (_, ((c, i), j)) => (c, i, j) }
      .groupBy { case (c, i, j) => Position1D(c.position(Third)) }
      .join(saveDictionary(namesK, file, dictionary, separator, Third))
      .map { case (_, ((c, i, j), k)) => (c, i, j, k) }
      .groupBy { case (c, i, j, k) => Position1D(c.position(Fourth)) }
      .join(saveDictionary(namesL, file, dictionary, separator, Fourth))
      .map { case (_, ((c, i, j, k), l)) => (c, i, j, k, l) }
      .groupBy { case (c, i, j, k, l) => Position1D(c.position(Fifth)) }
      .join(saveDictionary(namesM, file, dictionary, separator, Fifth))
      .map { case (_, ((c, i, j, k, l), m)) => (c, i, j, k, l, m) }
      .groupBy { case (c, i, j, k, l, m) => Position1D(c.position(Sixth)) }
      .join(saveDictionary(namesN, file, dictionary, separator, Sixth))
      .map { case (_, ((c, i, j, k, l, m), n)) => (c, i, j, k, l, m, n) }
      .groupBy { case (c, i, j, k, l, m, n) => Position1D(c.position(Seventh)) }
      .join(saveDictionary(namesO, file, dictionary, separator, Seventh))
      .map {
        case (_, ((c, i, j, k, l, m, n), o)) =>
          i + separator + j + separator + k + separator + l + separator + m + separator +
            n + separator + o + separator + c.content.value.toShortString
      }
      .write(TypedSink(TextLine(file)))

    data
  }
}

/**
 * Rich wrapper around a `TypedPipe[Cell[Position8D]]`.
 *
 * @param data `TypedPipe[Cell[Position8D]]`.
 */
class Matrix8D(val data: TypedPipe[Cell[Position8D]]) extends Matrix[Position8D] with ReduceableMatrix[Position8D]
  with ExpandableMatrix[Position8D] {
  def domain(): U[Position8D] = {
    names(Over(First))
      .map { case (Position1D(c), i) => c }
      .cross(names(Over(Second)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Third)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Fourth)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Fifth)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Sixth)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Seventh)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Eighth)).map { case (Position1D(c), i) => c })
      .map { case (((((((c1, c2), c3), c4), c5), c6), c7), c8) => Position8D(c1, c2, c3, c4, c5, c6, c7, c8) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first   Dimension used for the first coordinate.
   * @param second  Dimension used for the second coordinate.
   * @param third   Dimension used for the third coordinate.
   * @param fourth  Dimension used for the fourth coordinate.
   * @param fifth   Dimension used for the fifth coordinate.
   * @param sixth   Dimension used for the sixth coordinate.
   * @param seventh Dimension used for the seventh coordinate.
   * @param eighth  Dimension used for the eighth coordinate.
   */
  def permute[D <: Dimension, F <: Dimension, G <: Dimension, H <: Dimension, I <: Dimension, J <: Dimension, K <: Dimension, L <: Dimension](
    first: D, second: F, third: G, fourth: H, fifth: I, sixth: J, seventh: K, eighth: L)(
      implicit ev1: PosDimDep[Position8D, D], ev2: PosDimDep[Position8D, F], ev3: PosDimDep[Position8D, G],
      ev4: PosDimDep[Position8D, H], ev5: PosDimDep[Position8D, I], ev6: PosDimDep[Position8D, J],
      ev7: PosDimDep[Position8D, K], ev8: PosDimDep[Position8D, L],
      ev9: Distinct8[D, F, G, H, I, J, K, L]): U[Cell[Position8D]] = {
    data.map {
      case Cell(p, c) => Cell(p.permute(List(first, second, third, fourth, fifth, sixth, seventh, eighth)), c)
    }
  }

  /**
   * Persist a `Matrix8D` as sparse matrix file (index, index, index, index, index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position8D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|")(implicit flow: FlowDef,
    mode: Mode): U[Cell[Position8D]] = {
    saveAsIVWithNames(file, names(Over(First)), names(Over(Second)), names(Over(Third)), names(Over(Fourth)),
      names(Over(Fifth)), names(Over(Sixth)), names(Over(Seventh)), names(Over(Eighth)), dictionary, separator)
  }

  /**
   * Persist a `Matrix8D` as sparse matrix file (index, index, index, index, index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param namesI     The names to use for the first dimension (according to their ordering).
   * @param namesJ     The names to use for the second dimension (according to their ordering).
   * @param namesK     The names to use for the third dimension (according to their ordering).
   * @param namesL     The names to use for the fourth dimension (according to their ordering).
   * @param namesM     The names to use for the fifth dimension (according to their ordering).
   * @param namesN     The names to use for the sixth dimension (according to their ordering).
   * @param namesO     The names to use for the seventh dimension (according to their ordering).
   * @param namesP     The names to use for the eighth dimension (according to their ordering).
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position8D]]`; that is it returns `data`.
   *
   * @note If `names` contains a subset of the columns, then only those columns get persisted to file.
   */
  def saveAsIVWithNames(file: String, namesI: U[(Position1D, Long)], namesJ: U[(Position1D, Long)],
    namesK: U[(Position1D, Long)], namesL: U[(Position1D, Long)], namesM: U[(Position1D, Long)],
    namesN: U[(Position1D, Long)], namesO: U[(Position1D, Long)], namesP: U[(Position1D, Long)],
    dictionary: String = "%1$s.dict.%2$d", separator: String = "|")(implicit flow: FlowDef,
      mode: Mode): U[Cell[Position8D]] = {
    data
      .groupBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(namesI, file, dictionary, separator, First))
      .values
      .groupBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(namesJ, file, dictionary, separator, Second))
      .map { case (_, ((c, i), j)) => (c, i, j) }
      .groupBy { case (c, i, j) => Position1D(c.position(Third)) }
      .join(saveDictionary(namesK, file, dictionary, separator, Third))
      .map { case (_, ((c, i, j), k)) => (c, i, j, k) }
      .groupBy { case (c, i, j, k) => Position1D(c.position(Fourth)) }
      .join(saveDictionary(namesL, file, dictionary, separator, Fourth))
      .map { case (_, ((c, i, j, k), l)) => (c, i, j, k, l) }
      .groupBy { case (c, i, j, k, l) => Position1D(c.position(Fifth)) }
      .join(saveDictionary(namesM, file, dictionary, separator, Fifth))
      .map { case (_, ((c, i, j, k, l), m)) => (c, i, j, k, l, m) }
      .groupBy { case (c, i, j, k, l, m) => Position1D(c.position(Sixth)) }
      .join(saveDictionary(namesN, file, dictionary, separator, Sixth))
      .map { case (_, ((c, i, j, k, l, m), n)) => (c, i, j, k, l, m, n) }
      .groupBy { case (c, i, j, k, l, m, n) => Position1D(c.position(Seventh)) }
      .join(saveDictionary(namesO, file, dictionary, separator, Seventh))
      .map { case (_, ((c, i, j, k, l, m, n), o)) => (c, i, j, k, l, m, n, o) }
      .groupBy { case (c, i, j, k, l, m, n, o) => Position1D(c.position(Eighth)) }
      .join(saveDictionary(namesP, file, dictionary, separator, Eighth))
      .map {
        case (_, ((c, i, j, k, l, m, n, o), p)) =>
          i + separator + j + separator + k + separator + l + separator + m + separator +
            n + separator + o + separator + p + separator + c.content.value.toShortString
      }
      .write(TypedSink(TextLine(file)))

    data
  }
}

/**
 * Rich wrapper around a `TypedPipe[Cell[Position9D]]`.
 *
 * @param data `TypedPipe[Cell[Position9D]]`.
 */
class Matrix9D(val data: TypedPipe[Cell[Position9D]]) extends Matrix[Position9D] with ReduceableMatrix[Position9D] {
  def domain(): U[Position9D] = {
    names(Over(First))
      .map { case (Position1D(c), i) => c }
      .cross(names(Over(Second)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Third)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Fourth)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Fifth)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Sixth)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Seventh)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Eighth)).map { case (Position1D(c), i) => c })
      .cross(names(Over(Ninth)).map { case (Position1D(c), i) => c })
      .map { case ((((((((c1, c2), c3), c4), c5), c6), c7), c8), c9) => Position9D(c1, c2, c3, c4, c5, c6, c7, c8, c9) }
  }

  /**
   * Permute the order of the coordinates in a position.
   *
   * @param first   Dimension used for the first coordinate.
   * @param second  Dimension used for the second coordinate.
   * @param third   Dimension used for the third coordinate.
   * @param fourth  Dimension used for the fourth coordinate.
   * @param fifth   Dimension used for the fifth coordinate.
   * @param sixth   Dimension used for the sixth coordinate.
   * @param seventh Dimension used for the seventh coordinate.
   * @param eighth  Dimension used for the eighth coordinate.
   * @param ninth   Dimension used for the ninth coordinate.
   */
  def permute[D <: Dimension, F <: Dimension, G <: Dimension, H <: Dimension, I <: Dimension, J <: Dimension, K <: Dimension, L <: Dimension, M <: Dimension](
    first: D, second: F, third: G, fourth: H, fifth: I, sixth: J, seventh: K, eighth: L, ninth: M)(
      implicit ev1: PosDimDep[Position9D, D], ev2: PosDimDep[Position9D, F], ev3: PosDimDep[Position9D, G],
      ev4: PosDimDep[Position9D, H], ev5: PosDimDep[Position9D, I], ev6: PosDimDep[Position9D, J],
      ev7: PosDimDep[Position9D, K], ev8: PosDimDep[Position9D, L], ev9: PosDimDep[Position9D, M],
      ev10: Distinct9[D, F, G, H, I, J, K, L, M]): U[Cell[Position9D]] = {
    data.map {
      case Cell(p, c) => Cell(p.permute(List(first, second, third, fourth, fifth, sixth, seventh, eighth, ninth)), c)
    }
  }

  /**
   * Persist a `Matrix9D` as sparse matrix file (index, index, index, index, index, index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position9D]]`; that is it returns `data`.
   */
  def saveAsIV(file: String, dictionary: String = "%1$s.dict.%2$d", separator: String = "|")(implicit flow: FlowDef,
    mode: Mode): U[Cell[Position9D]] = {
    saveAsIVWithNames(file, names(Over(First)), names(Over(Second)), names(Over(Third)), names(Over(Fourth)),
      names(Over(Fifth)), names(Over(Sixth)), names(Over(Seventh)), names(Over(Eighth)), names(Over(Ninth)),
      dictionary, separator)
  }

  /**
   * Persist a `Matrix9D` as sparse matrix file (index, index, index, index, index, index, index, index, index, value).
   *
   * @param file       File to write to.
   * @param namesI     The names to use for the first dimension (according to their ordering).
   * @param namesJ     The names to use for the second dimension (according to their ordering).
   * @param namesK     The names to use for the third dimension (according to their ordering).
   * @param namesL     The names to use for the fourth dimension (according to their ordering).
   * @param namesM     The names to use for the fifth dimension (according to their ordering).
   * @param namesN     The names to use for the sixth dimension (according to their ordering).
   * @param namesO     The names to use for the seventh dimension (according to their ordering).
   * @param namesP     The names to use for the eighth dimension (according to their ordering).
   * @param namesQ     The names to use for the ninth dimension (according to their ordering).
   * @param dictionary Pattern for the dictionary file name.
   * @param separator  Column separator to use in dictionary file.
   *
   * @return A `TypedPipe[Cell[Position9D]]`; that is it returns `data`.
   *
   * @note If `names` contains a subset of the columns, then only those columns get persisted to file.
   */
  def saveAsIVWithNames(file: String, namesI: U[(Position1D, Long)], namesJ: U[(Position1D, Long)],
    namesK: U[(Position1D, Long)], namesL: U[(Position1D, Long)], namesM: U[(Position1D, Long)],
    namesN: U[(Position1D, Long)], namesO: U[(Position1D, Long)], namesP: U[(Position1D, Long)],
    namesQ: U[(Position1D, Long)], dictionary: String = "%1$s.dict.%2$d", separator: String = "|")(
      implicit flow: FlowDef, mode: Mode): U[Cell[Position9D]] = {
    data
      .groupBy { case c => Position1D(c.position(First)) }
      .join(saveDictionary(namesI, file, dictionary, separator, First))
      .values
      .groupBy { case (c, i) => Position1D(c.position(Second)) }
      .join(saveDictionary(namesJ, file, dictionary, separator, Second))
      .map { case (_, ((c, i), j)) => (c, i, j) }
      .groupBy { case (c, i, j) => Position1D(c.position(Third)) }
      .join(saveDictionary(namesK, file, dictionary, separator, Third))
      .map { case (_, ((c, i, j), k)) => (c, i, j, k) }
      .groupBy { case (c, i, j, k) => Position1D(c.position(Fourth)) }
      .join(saveDictionary(namesL, file, dictionary, separator, Fourth))
      .map { case (_, ((c, i, j, k), l)) => (c, i, j, k, l) }
      .groupBy { case (c, i, j, k, l) => Position1D(c.position(Fifth)) }
      .join(saveDictionary(namesM, file, dictionary, separator, Fifth))
      .map { case (_, ((c, i, j, k, l), m)) => (c, i, j, k, l, m) }
      .groupBy { case (c, i, j, k, l, m) => Position1D(c.position(Sixth)) }
      .join(saveDictionary(namesN, file, dictionary, separator, Sixth))
      .map { case (_, ((c, i, j, k, l, m), n)) => (c, i, j, k, l, m, n) }
      .groupBy { case (c, i, j, k, l, m, n) => Position1D(c.position(Seventh)) }
      .join(saveDictionary(namesO, file, dictionary, separator, Seventh))
      .map { case (_, ((c, i, j, k, l, m, n), o)) => (c, i, j, k, l, m, n, o) }
      .groupBy { case (c, i, j, k, l, m, n, o) => Position1D(c.position(Eighth)) }
      .join(saveDictionary(namesP, file, dictionary, separator, Eighth))
      .map { case (_, ((c, i, j, k, l, m, n, o), p)) => (c, i, j, k, l, m, n, o, p) }
      .groupBy { case (c, i, j, k, l, m, n, o, p) => Position1D(c.position(Ninth)) }
      .join(saveDictionary(namesQ, file, dictionary, separator, Ninth))
      .map {
        case (_, ((c, i, j, k, l, m, n, o, p), q)) =>
          i + separator + j + separator + k + separator + l + separator + m + separator +
            n + separator + o + separator + p + separator + q + separator + c.content.value.toShortString
      }
      .write(TypedSink(TextLine(file)))

    data
  }
}

/** Scalding Companion object for the `Matrixable` type class. */
object Matrixable {
  /** Converts a `TypedPipe[Cell[P]]` into a `TypedPipe[Cell[P]]`; that is, it is a  pass through. */
  implicit def TPC2TPM[P <: Position]: BaseMatrixable[TypedPipe[Cell[P]], P, TypedPipe] = {
    new BaseMatrixable[TypedPipe[Cell[P]], P, TypedPipe] { def convert(t: TypedPipe[Cell[P]]): TypedPipe[Cell[P]] = t }
  }

  /** Converts a `List[Cell[P]]` into a `TypedPipe[Cell[P]]`. */
  implicit def LC2TPM[P <: Position]: BaseMatrixable[List[Cell[P]], P, TypedPipe] = {
    new BaseMatrixable[List[Cell[P]], P, TypedPipe] {
      def convert(t: List[Cell[P]]): TypedPipe[Cell[P]] = new IterablePipe(t)
    }
  }

  /** Converts a `Cell[P]` into a `TypedPipe[Cell[P]]`. */
  implicit def C2TPM[P <: Position]: BaseMatrixable[Cell[P], P, TypedPipe] = {
    new BaseMatrixable[Cell[P], P, TypedPipe] {
      def convert(t: Cell[P]): TypedPipe[Cell[P]] = new IterablePipe(List(t))
    }
  }
}

