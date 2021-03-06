/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.mllib.random

import scala.collection.mutable.ArrayBuffer

import org.scalatest.FunSuite

import org.apache.spark.SparkContext._
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.rdd.{RandomRDDPartition, RandomRDD}
import org.apache.spark.mllib.util.LocalSparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.util.StatCounter

/*
 * Note: avoid including APIs that do not set the seed for the RNG in unit tests
 * in order to guarantee deterministic behavior.
 *
 * TODO update tests to use TestingUtils for floating point comparison after PR 1367 is merged
 */
class RandomRDDGeneratorsSuite extends FunSuite with LocalSparkContext with Serializable {

  def testGeneratedRDD(rdd: RDD[Double],
      expectedSize: Long,
      expectedNumPartitions: Int,
      expectedMean: Double,
      expectedStddev: Double,
      epsilon: Double = 0.01) {
    val stats = rdd.stats()
    assert(expectedSize === stats.count)
    assert(expectedNumPartitions === rdd.partitions.size)
    assert(math.abs(stats.mean - expectedMean) < epsilon)
    assert(math.abs(stats.stdev - expectedStddev) < epsilon)
  }

  // assume test RDDs are small
  def testGeneratedVectorRDD(rdd: RDD[Vector],
      expectedRows: Long,
      expectedColumns: Int,
      expectedNumPartitions: Int,
      expectedMean: Double,
      expectedStddev: Double,
      epsilon: Double = 0.01) {
    assert(expectedNumPartitions === rdd.partitions.size)
    val values = new ArrayBuffer[Double]()
    rdd.collect.foreach { vector => {
      assert(vector.size === expectedColumns)
      values ++= vector.toArray
    }}
    assert(expectedRows === values.size / expectedColumns)
    val stats = new StatCounter(values)
    assert(math.abs(stats.mean - expectedMean) < epsilon)
    assert(math.abs(stats.stdev - expectedStddev) < epsilon)
  }

  test("RandomRDD sizes") {

    // some cases where size % numParts != 0 to test getPartitions behaves correctly
    for ((size, numPartitions) <- List((10000, 6), (12345, 1), (1000, 101))) {
      val rdd = new RandomRDD(sc, size, numPartitions, new UniformGenerator, 0L)
      assert(rdd.count() === size)
      assert(rdd.partitions.size === numPartitions)

      // check that partition sizes are balanced
      val partSizes = rdd.partitions.map(p =>
        p.asInstanceOf[RandomRDDPartition[Double]].size.toDouble)

      val partStats = new StatCounter(partSizes)
      assert(partStats.max - partStats.min <= 1)
    }

    // size > Int.MaxValue
    val size = Int.MaxValue.toLong * 100L
    val numPartitions = 101
    val rdd = new RandomRDD(sc, size, numPartitions, new UniformGenerator, 0L)
    assert(rdd.partitions.size === numPartitions)
    val count = rdd.partitions.foldLeft(0L) { (count, part) =>
      count + part.asInstanceOf[RandomRDDPartition[Double]].size
    }
    assert(count === size)

    // size needs to be positive
    intercept[IllegalArgumentException] { new RandomRDD(sc, 0, 10, new UniformGenerator, 0L) }

    // numPartitions needs to be positive
    intercept[IllegalArgumentException] { new RandomRDD(sc, 100, 0, new UniformGenerator, 0L) }

    // partition size needs to be <= Int.MaxValue
    intercept[IllegalArgumentException] {
      new RandomRDD(sc, Int.MaxValue.toLong * 100L, 99, new UniformGenerator, 0L)
    }
  }

  test("randomRDD for different distributions") {
    val size = 100000L
    val numPartitions = 10
    val poissonMean = 100.0

    for (seed <- 0 until 5) {
      val uniform = RandomRDDGenerators.uniformRDD(sc, size, numPartitions, seed)
      testGeneratedRDD(uniform, size, numPartitions, 0.5, 1 / math.sqrt(12))

      val normal = RandomRDDGenerators.normalRDD(sc, size, numPartitions, seed)
      testGeneratedRDD(normal, size, numPartitions, 0.0, 1.0)

      val poisson = RandomRDDGenerators.poissonRDD(sc, poissonMean, size, numPartitions, seed)
      testGeneratedRDD(poisson, size, numPartitions, poissonMean, math.sqrt(poissonMean), 0.1)
    }

    // mock distribution to check that partitions have unique seeds
    val random = RandomRDDGenerators.randomRDD(sc, new MockDistro(), 1000L, 1000, 0L)
    assert(random.collect.size === random.collect.distinct.size)
  }

  test("randomVectorRDD for different distributions") {
    val rows = 1000L
    val cols = 100
    val parts = 10
    val poissonMean = 100.0

    for (seed <- 0 until 5) {
      val uniform = RandomRDDGenerators.uniformVectorRDD(sc, rows, cols, parts, seed)
      testGeneratedVectorRDD(uniform, rows, cols, parts, 0.5, 1 / math.sqrt(12))

      val normal = RandomRDDGenerators.normalVectorRDD(sc, rows, cols, parts, seed)
      testGeneratedVectorRDD(normal, rows, cols, parts, 0.0, 1.0)

      val poisson = RandomRDDGenerators.poissonVectorRDD(sc, poissonMean, rows, cols, parts, seed)
      testGeneratedVectorRDD(poisson, rows, cols, parts, poissonMean, math.sqrt(poissonMean), 0.1)
    }
  }
}

private[random] class MockDistro extends RandomDataGenerator[Double] {

  var seed = 0L

  // This allows us to check that each partition has a different seed
  override def nextValue(): Double = seed.toDouble

  override def setSeed(seed: Long) = this.seed = seed

  override def copy(): MockDistro = new MockDistro
}
