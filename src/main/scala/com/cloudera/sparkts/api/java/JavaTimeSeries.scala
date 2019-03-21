/**
  * Copyright (c) 2015, Cloudera, Inc. All Rights Reserved.
  *
  * Cloudera, Inc. licenses this file to you under the Apache License,
  * Version 2.0 (the "License"). You may not use this file except in
  * compliance with the License. You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
  * CONDITIONS OF ANY KIND, either express or implied. See the License for
  * the specific language governing permissions and limitations under the
  * License.
  */

package com.cloudera.sparkts.api.java

import java.time.{ZoneId, ZonedDateTime}

import com.cloudera.sparkts.MatrixUtil._
import com.cloudera.sparkts.api.java.JavaTimeSeries._
import com.cloudera.sparkts.{DateTimeIndex, TimeSeries, UniformDateTimeIndex}
import org.apache.spark.api.java.function.{Function => JFunction, Function2 => JFunction2}
import org.apache.spark.mllib.linalg.{DenseMatrix, Vector}

import scala.collection.JavaConversions
import scala.reflect.ClassTag

class JavaTimeSeries[K](val ts: TimeSeries[K])(implicit val kClassTag: ClassTag[K])
  extends Serializable {

  def this(index: DateTimeIndex, data: DenseMatrix, keys: Array[K])
          (implicit kClassTag: ClassTag[K]) {
    this(new TimeSeries[K](index, data, keys))
  }

  def this(index: DateTimeIndex, data: DenseMatrix, keys: List[K])
          (implicit kClassTag: ClassTag[K]) {
    this(new TimeSeries[K](index, data, keys.toArray))
  }

  def this(index: DateTimeIndex, data: DenseMatrix, keys: java.util.List[K])
          (implicit kClassTag: ClassTag[K]) {
    this(index, data, JavaConversions.asScalaBuffer(keys).toArray)
  }

  def index: DateTimeIndex = ts.index

  def data: DenseMatrix = ts.data

  def dataAsArray: Array[Double] = ts.data.valuesIterator.toArray

  def keys: Array[K] = ts.keys

  /**
    * IMPORTANT: this function assumes that the DateTimeIndex is a UniformDateTimeIndex, not an
    * Irregular one.
    *
    * Lags all individual time series of the JavaTimeSeries instance by up to maxLag amount.
    * The lagged time series has its keys generated by the laggedKey function which takes
    * two input parameters: the original key and the lag order, and should return a
    * corresponding lagged key.
    *
    * Example input TimeSeries:
    * time   a   b
    * 4 pm   1   6
    * 5 pm   2   7
    * 6 pm   3   8
    * 7 pm   4   9
    * 8 pm   5   10
    *
    * With maxLag 2 and includeOriginals = true, and JavaTimeSeries.laggedStringKey, we would get:
    * time   a   lag1(a)   lag2(a)  b   lag1(b)  lag2(b)
    * 6 pm   3   2         1         8   7         6
    * 7 pm   4   3         2         9   8         7
    * 8 pm   5   4         3         10  9         8
    *
    */
  def lags[U](maxLag: Int, includeOriginals: Boolean,
              laggedKey: JFunction2[K, java.lang.Integer, U])
  : JavaTimeSeries[U] = {
    implicit val classTagOfU: ClassTag[U] = classTagOf(laggedKey)
    new JavaTimeSeries[U](ts.lags(maxLag, includeOriginals,
      (k: K, i: Int) => laggedKey.call(k, new java.lang.Integer(i))))
  }

  /**
    * This is equivalent to lags(maxLag, includeOriginals, new JavaTimeSeries.laggedPairKey()).
    * It returns JavaTimeSeries with a new key that is a pair of (original key, lag order).
    *
    */
  def lags[U >: (K, java.lang.Integer)](maxLag: Int, includeOriginals: Boolean)
  : JavaTimeSeries[(K, java.lang.Integer)] = {
    lags(maxLag, includeOriginals, new laggedPairKey[K])
  }

  /**
    * IMPORTANT: this function assumes that the DateTimeIndex is a UniformDateTimeIndex, not an
    * Irregular one.
    *
    * Lags the specified individual time series of the TimeSeries instance by up to
    * their matching lag amount.
    * Each time series can be indicated to either retain the original value, or drop it.
    *
    * In other words, the lagsPerCol has the following structure:
    *
    * ("variableName1" -> (keepOriginalValue, maxLag),
    * "variableName2" -> (keepOriginalValue, maxLag),
    * ...)
    *
    * See description of the above lags function for an example of the lagging process.
    */
  def lags[U](lagsPerCol: java.util.Map[K, (java.lang.Boolean, java.lang.Integer)],
              laggedKey: JFunction2[K, java.lang.Integer, U])
  : JavaTimeSeries[U] = {
    implicit val classTagOfU: ClassTag[U] = classTagOf(laggedKey)
    val map = JavaConversions.mapAsScalaMap(lagsPerCol).map {
      a => (a._1, (a._2._1.booleanValue(), a._2._2.intValue()))
    }.toMap
    new JavaTimeSeries[U](ts.lags(map,
      (k: K, i: Int) => laggedKey.call(k, new java.lang.Integer(i))))
  }

  /**
    * This is equivalent to lags(lagsPerCol, new JavaTimeSeries.laggedPairKey()).
    * It returns JavaTimeSeries with a new key that is a pair of (original key, lag order).
    *
    */
  def lags[U >: (K, java.lang.Integer)](lagsPerCol: java.util.Map[K, (java.lang.Boolean, java.lang.Integer)])
  : JavaTimeSeries[(K, java.lang.Integer)] = {
    lags(lagsPerCol, new laggedPairKey[K])
  }

  private[sparkts]
  def classTagOf[U](laggedKey: JFunction2[K, java.lang.Integer, U]): ClassTag[U] =
    ClassTag.apply(laggedKey.call(keys(0), new java.lang.Integer(0)).getClass)

  def slice(range: Range): JavaTimeSeries[K] = new JavaTimeSeries[K](ts.slice(range))

  def union(vec: Vector, key: K): JavaTimeSeries[K] = new JavaTimeSeries[K](ts.union(vec, key))

  /**
    * Returns a TimeSeries where each time series is differenced with the given order. The new
    * TimeSeries will be missing the first n date-times.
    */
  def differences(lag: Int): JavaTimeSeries[K] = new JavaTimeSeries[K](ts.differences(lag))

  /**
    * Returns a TimeSeries where each time series is differenced with order 1. The new TimeSeries
    * will be missing the first date-time.
    */
  def differences(): JavaTimeSeries[K] = new JavaTimeSeries[K](ts.differences())

  /**
    * Returns a TimeSeries where each time series is quotiented with the given order. The new
    * TimeSeries will be missing the first n date-times.
    */
  def quotients(lag: Int): JavaTimeSeries[K] = new JavaTimeSeries[K](ts.quotients(lag))

  /**
    * Returns a TimeSeries where each time series is quotiented with order 1. The new TimeSeries will
    * be missing the first date-time.
    */
  def quotients(): JavaTimeSeries[K] = new JavaTimeSeries[K](ts.quotients())

  /**
    * Returns a return series for each time series. Assumes periodic (as opposed to continuously
    * compounded) returns.
    */
  def price2ret(): JavaTimeSeries[K] = new JavaTimeSeries[K](ts.price2ret())

  def univariateSeriesIterator(): java.util.Iterator[Vector] =
    JavaConversions.asJavaIterator(ts.univariateSeriesIterator())

  def univariateKeyAndSeriesIterator(): java.util.Iterator[(K, Vector)] =
    JavaConversions.asJavaIterator(ts.univariateKeyAndSeriesIterator())

  def toInstants: java.util.List[(ZonedDateTime, Vector)] =
    JavaConversions.seqAsJavaList(ts.toInstants())

  /**
    * Applies a transformation to each series that preserves the time index.
    */
  def mapSeries(f: JFunction[Vector, Vector]): JavaTimeSeries[K] =
    new JavaTimeSeries[K](ts.mapSeries((v: Vector) => f.call(v), index))

  /**
    * Applies a transformation to each series that preserves the time index. Passes the key along
    * with each series.
    */
  def mapSeriesWithKey(f: JFunction2[K, Vector, Vector]): JavaTimeSeries[K] =
    new JavaTimeSeries[K](ts.mapSeriesWithKey((k: K, v: Vector) => f.call(k, v)))

  /**
    * Applies a transformation to each series such that the resulting series align with the given
    * time index.
    */
  def mapSeries(f: JFunction[Vector, Vector], newIndex: DateTimeIndex): JavaTimeSeries[K] = {
    new JavaTimeSeries[K](ts.mapSeries((v: Vector) => f.call(v), newIndex))
  }

  def mapValues[U](f: JFunction[Vector, U]): java.util.List[(K, U)] =
    JavaConversions.seqAsJavaList(ts.mapValues((v: Vector) => f.call(v)))

  /**
    * Gets the first univariate series and its key.
    */
  def head(): (K, Vector) = ts.head()

}

object JavaTimeSeries {

  class laggedStringKey extends JFunction2[String, java.lang.Integer, String] {
    def call(key: String, lagOrder: java.lang.Integer): String =
      if (lagOrder > 0) s"lag$lagOrder($key)" else key
  }

  class laggedPairKey[K] extends JFunction2[K, java.lang.Integer, (K, java.lang.Integer)] {
    def call(key: K, lagOrder: java.lang.Integer): (K, java.lang.Integer) = (key, lagOrder)
  }

  def javaTimeSeriesFromIrregularSamples[K](samples: Seq[(ZonedDateTime, Array[Double])],
                                            keys: Array[K],
                                            zone: ZoneId = ZoneId.systemDefault())
                                           (implicit kClassTag: ClassTag[K])
  : JavaTimeSeries[K] =
    new JavaTimeSeries[K](TimeSeries.timeSeriesFromIrregularSamples[K](samples, keys, zone))

  /**
    * This function should only be called when you can safely make the assumption that the time
    * samples are uniform (monotonously increasing) across time.
    */
  def javaTimeSeriesFromUniformSamples[K](samples: Seq[Array[Double]],
                                          index: UniformDateTimeIndex,
                                          keys: Array[K])
                                         (implicit kClassTag: ClassTag[K])
  : JavaTimeSeries[K] =
    new JavaTimeSeries[K](TimeSeries.timeSeriesFromUniformSamples[K](samples, index, keys))

  def javaTimeSeriesFromVectors[K](vectors: Iterable[Vector],
                                   index: DateTimeIndex,
                                   keys: Array[K])
                                  (implicit kClassTag: ClassTag[K])
  : JavaTimeSeries[K] =
    new JavaTimeSeries[K](TimeSeries.timeSeriesFromVectors(vectors, index, keys))
}
