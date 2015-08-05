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

package org.apache.spark.mllib.optimization.tfocs

import org.apache.spark.mllib.linalg.{ DenseVector, Vectors, Vector }
import org.apache.spark.mllib.optimization.tfocs.DVectorFunctions._
import org.apache.spark.mllib.optimization.tfocs.VectorSpace._
import org.apache.spark.rdd.RDD

/**
 * Trait for smooth functions.
 *
 * @tparam X Type representing a vector on which to evaluate the function.
 */
trait SmoothFunction[X] {
  /**
   * Evaluates this function at x and returns the function value and its gradient based on the mode
   * specified.
   */
  def apply(x: X, mode: Mode): Value[X]

  /**
   * Evaluates this function at x.
   */
  def apply(x: X): Double = apply(x, Mode(f = true, g = false)).f.get
}

/** The squared error function applied to DVectors, with a constant factor of 0.5. */
class SmoothQuadDVector(x0: DVector) extends SmoothFunction[DVector] {

  x0.cache()

  override def apply(x: DVector, mode: Mode): Value[DVector] = {
    val g = x.diff(x0)
    if (mode.f && mode.g) g.cache()
    val f = if (mode.f) {
      Some(g.treeAggregate(0.0)((sum, y) => sum + Math.pow(Vectors.norm(y, 2), 2), _ + _) / 2.0)
    } else {
      None
    }
    Value(f, Some(g))
  }
}

/**
 * The huber loss function applied to DVectors.
 *
 * @param x0 The vector against which loss should be computed.
 * @param tau The huber loss parameter.
 */
class SmoothHuberDVector(x0: DVector, tau: Double)
    extends SmoothFunction[DVector] with Serializable {

  x0.cache()

  override def apply(x: DVector, mode: Mode): Value[DVector] = {

    val diff = x.diff(x0)
    if (mode.f && mode.g) diff.cache()

    val f = if (mode.f) {
      Some(diff.aggregateElements(0.0)(
        seqOp = (sum, y) => {
          val huberValue = if (math.abs(y) <= tau) 0.5 * y * y / tau else math.abs(y) - tau / 2.0
          sum + huberValue
        },
        combOp = _ + _))
    } else {
      None
    }

    val g = if (mode.g) {
      Some(diff.mapElements(y => y / math.max(math.abs(y), tau)))
    } else {
      None
    }

    Value(f, g)
  }
}

/**
 * The log likelihood logistic loss function applied to DVectors.
 *
 * @param y The observed values.
 * @param mu The variable values.
 */
class SmoothLogLLogisticDVector(y: DVector)
    extends SmoothFunction[DVector] with Serializable {

  y.cache()

  override def apply(mu: DVector, mode: Mode): Value[DVector] = {

    val f = if (mode.f) {
      Some(y.zip(mu).treeAggregate(0.0)(
        seqOp = (sum, vectors) => {
          if (vectors._1.size != vectors._2.size) {
            throw new IllegalArgumentException("Can only zip Vectors with the same number of " +
              "elements")
          }
          vectors._1.toArray.zip(vectors._2.toArray).map(elements => {
            val (y_i, mu_i) = elements
            val yFactor = if (mu_i > 0.0) y_i - 1.0 else if (mu_i < 0.0) y_i else 0.0
            yFactor * mu_i - math.log1p(math.exp(-math.abs(mu_i)))
          }).sum + sum
        },
        combOp = _ + _))
    } else {
      None
    }

    val g = if (mode.g) {
      Some(y.zipElements(mu, (y_i, mu_i) => {
        val muFactor = if (mu_i > 0.0) 1.0 else math.exp(mu_i)
        y_i - muFactor / (1.0 + math.exp(-math.abs(mu_i)))
      }))
    } else {
      None
    }

    Value(f, g)
  }
}
