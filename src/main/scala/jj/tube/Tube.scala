package jj.tube

import cascading.pipe._
import cascading.pipe.joiner.{Joiner, InnerJoin}
import cascading.tuple.Fields
import cascading.tuple.Fields._
import cascading.operation.Insert
import cascading.pipe.assembly._
import cascading.operation.aggregator.First
import CustomOps._
import Tube._

object Tube {
  def apply(name: String) = new Tube(new Pipe(name))

  def apply(name: String, previous: Pipe) = new Tube(new Pipe(name, previous))

  implicit def toPipe(tube: Tube) = tube.pipe

  implicit def toTube(pipe: Pipe) = new Tube(pipe)
}

class Tube(var pipe: Pipe) extends Grouping with GroupOperator with RowOperator with FieldsTransform with MathOperation {
  def checkpoint = this << new Checkpoint(pipe)

  def merge(tubes: Tube*) = this << new Merge(pipe :: tubes.map(_.pipe).toList: _*)

  def <<(op: Pipe) = {
    pipe = op
    this
  }
}

trait Grouping {
  this: Tube =>

  def aggregateBy(key: Fields, aggregators: AggregateBy*) = this << new AggregateBy(pipe, key, aggregators: _*)

  //TODO incorporate every in that op
  def groupBy(key: Fields, sort: Fields, reverse: Boolean = false) = this << new GroupBy(pipe, key, sort, reverse)

  def coGroup(leftKey: Fields, rightCollection: Tube, rightKey: Fields, joiner: Joiner = new InnerJoin) =
    this << new CoGroup(pipe, leftKey, rightCollection, rightKey, joiner)

  def hashJoin(leftKey: Fields, rightCollection: Tube, rightKey: Fields, joiner: Joiner = new InnerJoin) =
    this << new HashJoin(pipe, leftKey, rightCollection, rightKey, joiner)

  def unique(fields: Fields) = this << new Unique(pipe, fields)
}

//TODO method replace
trait RowOperator {
  this: Tube =>

  //TODO each supporting List => List
  def each(input: Fields = ALL, funcScheme: Fields = UNKNOWN, outScheme: Fields = ALL)
          (function: (Map[String, String] => Map[String, Any])) =
    this << new Each(pipe, input, asFunction(function).setOutputScheme(funcScheme), outScheme)

  def filter(input: Fields = ALL)(filter: Map[String, String] => Boolean) = this << new Each(pipe, input, asFilter(filter))
}

trait GroupOperator {
  this: Tube =>

  def every(input: Fields = ALL, bufferScheme: Fields = UNKNOWN, outScheme: Fields = RESULTS)
           (buffer: (Map[String, String], Iterator[Map[String, String]]) => List[Map[String, Any]]) =
    this << new Every(pipe, input, asBuffer(buffer).setOutputScheme(bufferScheme), outScheme)

  def top(group: Fields, sort: Fields, reverse: Boolean = false, limit: Int = 1) = {
    groupBy(group, sort, reverse)
    this << new Every(pipe, VALUES, new First(limit))
  }
}

trait FieldsTransform {
  this: Tube =>

  def discard(field: Fields) = this << new Discard(pipe, field)

  def rename(from: Fields, to: Fields) = this << new Rename(pipe, from, to)

  def retain(fields: Fields) = this << new Retain(pipe, fields)

  def coerce(fields: Fields, klass: Class[_]) = this << new Coerce(pipe, fields, (1 to fields.size).map(_ => klass): _*)

  def insert(field: Fields, value: String*) = this << new Each(pipe, new Insert(field, value: _*), ALL)
}

trait MathOperation {
  this: Tube =>

  def divide(leftOp: String, rightOp: String, outField: String) = math(leftOp, rightOp, outField) {
    (a, b) => a / b
  }

  def multiply(leftOp: String, rightOp: String, outField: String) = math(leftOp, rightOp, outField) {
    (a, b) => a * b
  }

  def plus(leftOp: String, rightOp: String, outField: String) = math(leftOp, rightOp, outField) {
    (a, b) => a + b
  }

  def minus(leftOp: String, rightOp: String, outField: String) = math(leftOp, rightOp, outField) {
    (a, b) => a - b
  }

  def math(leftOp: String, rightOp: String, outField: String)(func: (Double, Double) => Double) = {
    this << each((leftOp, rightOp), outField) {
      row =>
        Map(outField -> func(row(leftOp).toDouble, row(rightOp).toDouble))
    }
  }

  def math(operand: String, outField: String)(func: Double => Double) = {
    this << each(operand, outField) {
      row =>
        Map(outField -> func(row(operand).toDouble))
    }
  }
}
