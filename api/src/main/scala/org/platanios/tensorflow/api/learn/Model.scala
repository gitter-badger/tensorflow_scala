/* Copyright 2017, Emmanouil Antonios Platanios. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.platanios.tensorflow.api.learn

import org.platanios.tensorflow.api.core.Graph
import org.platanios.tensorflow.api.learn.layers.{Input, Layer}
import org.platanios.tensorflow.api.ops.{Math, Op, Output}
import org.platanios.tensorflow.api.ops.training.optimizers.Optimizer
import org.platanios.tensorflow.api.ops.io.Iterator
import org.platanios.tensorflow.api.types.FLOAT32

/**
  * @author Emmanouil Antonios Platanios
  */
sealed trait Model

object Model {
  case class PredictionOps[IT, IO, ID, IS, I](inputIterator: Iterator[IT, IO, ID, IS], input: IO, output: I)

  case class TrainOps[IT, IO, ID, IS, I, TT, TO, TD, TS, T](
      inputIterator: Iterator[(IT, TT), (IO, TO), (ID, TD), (IS, TS)],
      input: (IO, TO),
      output: I,
      trainOutput: T,
      loss: Output,
      trainOp: Op)

  object TrainOps {
    def apply[IT, IO, ID, IS, I](
        inputIterator: Iterator[(IT, IT), (IO, IO), (ID, ID), (IS, IS)],
        input: (IO, IO),
        output: I,
        loss: Output,
        trainOp: Op): TrainOps[IT, IO, ID, IS, I, IT, IO, ID, IS, I] = {
      TrainOps(inputIterator, input, output, output, loss, trainOp)
    }
  }

  trait API {
    def Model[IT, IO, ID, IS, I, TT, TO, TD, TS, T](
        input: Input[IT, IO, ID, IS],
        layer: Layer[IO, I],
        trainingInput: Input[TT, TO, TD, TS],
        trainingInputLayer: Layer[TO, T],
        loss: Layer[(I, T), Output],
        optimizer: Optimizer): TrainableModel[IT, IO, ID, IS, I, TT, TO, TD, TS, T] = {
      new TrainableModel(input, layer, trainingInput, trainingInputLayer, loss, optimizer)
    }
  }

  object API extends API
}

class PredictionModel[IT, IO, ID, IS, I] private[learn](
    val input: Input[IT, IO, ID, IS],
    val layer: Layer[IO, I]
) extends Model {
  def buildPredictionOps(graph: Graph = Op.currentGraph): Model.PredictionOps[IT, IO, ID, IS, I] = {
    Op.createWith(graph) {
      val tfInputIterator = input()
      val tfInput = tfInputIterator.next()
      val tfOutput = layer(tfInput)
      Model.PredictionOps(tfInputIterator, tfInput, tfOutput)
    }
  }
}

class TrainableModel[IT, IO, ID, IS, I, TT, TO, TD, TS, T] private[learn](
    override val input: Input[IT, IO, ID, IS],
    override val layer: Layer[IO, I],
    val trainInput: Input[TT, TO, TD, TS],
    val trainLayer: Layer[TO, T],
    val loss: Layer[(I, T), Output],
    val optimizer: Optimizer
) extends PredictionModel[IT, IO, ID, IS, I](input, layer) {
  def buildTrainOps(graph: Graph = Op.currentGraph): Model.TrainOps[IT, IO, ID, IS, I, TT, TO, TD, TS, T] = {
    Op.createWith(graph = graph) {
      val tfInputIterator = input.zip(trainInput).apply()
      val tfInput = tfInputIterator.next()
      val tfOutput = layer(tfInput._1)
      val tfTrainingOutput = trainLayer(tfInput._2)
      // TODO: [LEARN] !!! Remove this cast.
      val tfLoss = Math.cast(loss((tfOutput, tfTrainingOutput)), FLOAT32, name = "LearnLossCast")
      val tfIteration = Counter.getOrCreate(Graph.Keys.GLOBAL_STEP, graph)
      val tfTrainOp = optimizer.minimize(tfLoss, iteration = Some(tfIteration))
      Model.TrainOps(tfInputIterator, tfInput, tfOutput, tfTrainingOutput, tfLoss, tfTrainOp)
    }
  }
}
