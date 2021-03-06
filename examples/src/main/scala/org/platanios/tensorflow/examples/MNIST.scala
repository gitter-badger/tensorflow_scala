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

package org.platanios.tensorflow.examples

import org.platanios.tensorflow.api._
import org.platanios.tensorflow.data.loaders.MNISTLoader
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Paths

import org.platanios.tensorflow.api.learn.Estimator

/**
  * @author Emmanouil Antonios Platanios
  */
object MNIST {
  private[this] val logger = Logger(LoggerFactory.getLogger("Examples / MNIST"))

  //  def mlpPredictionModel(architecture: Seq[Int]): tf.learn.Layer[tf.Output, tf.Output] = {
  //    architecture.zipWithIndex
  //        .map(a => tf.learn.withVariableScope(s"Layer_${a._2}")(tf.learn.linear(a._1) >> tf.learn.ReLU(0.1f)))
  //        .foldLeft(tf.learn.Flatten() >> tf.learn.cast(FLOAT32))(_ >> _) >>
  //        tf.learn.withVariableScope("OutputLayer")(tf.learn.linear(10)) // >> tf.learn.logSoftmax())
  //  }

  def main(args: Array[String]): Unit = {
    val dataSet = MNISTLoader.load(Paths.get("/Users/Anthony/Downloads/MNIST"))
    val trainImages = tf.learn.DatasetFromSlices(dataSet.trainImages)
    val trainLabels = tf.learn.DatasetFromSlices(dataSet.trainLabels)
    val trainData =
      trainImages.zip(trainLabels)
          .repeat()
          .shuffle(10000)
          .batch(256)
          .prefetch(10)

    logger.info("Building the logistic regression model.")
    val input = tf.learn.Input(UINT8, Shape(-1, dataSet.trainImages.shape(1), dataSet.trainImages.shape(2)))
    val trainInput = tf.learn.Input(UINT8, Shape(-1))
    //    val layer = tf.learn.flatten() >>
    //        tf.learn.cast(FLOAT32) >>
    //        tf.learn.linear(10) // >> tf.learn.logSoftmax()
    val layer = tf.learn.Flatten() >>
        tf.learn.Cast(FLOAT32) >>
        tf.learn.Linear(128, name = "Layer_0") >> tf.learn.ReLU(0.1f) >>
        tf.learn.Linear(64, name = "Layer_1") >> tf.learn.ReLU(0.1f) >>
        tf.learn.Linear(32, name = "Layer_2") >> tf.learn.ReLU(0.1f) >>
        tf.learn.Linear(10, name = "OutputLayer")
    val trainingInputLayer = tf.learn.Cast(INT64)
    val loss = tf.learn.SparseSoftmaxCrossEntropy() >> tf.learn.Mean() >> tf.learn.ScalarSummary("Loss")
    val optimizer = tf.learn.AdaGrad(0.1)
    val model = tf.learn.Model(input, layer, trainInput, trainingInputLayer, loss, optimizer)

    logger.info("Training the linear regression model.")
    val summariesDir = Paths.get("/Users/Anthony/Downloads/temp/mlp-1024-256-64")
    val estimator = tf.learn.Estimator(model, tf.learn.Configuration(Some(summariesDir)))
    estimator.train(
      trainData,
      tf.learn.StopCriteria(maxSteps = Some(100000)),
      Seq(
        tf.learn.StepRateHook(log = false, summaryDirectory = summariesDir, trigger = tf.learn.StepHookTrigger(100)),
        tf.learn.SummarySaverHook(summariesDir, tf.learn.StepHookTrigger(100)),
        tf.learn.CheckpointSaverHook(summariesDir, tf.learn.StepHookTrigger(1000))),
      tensorBoardConfig = tf.learn.TensorBoardConfig(summariesDir, reloadInterval = 1))

    def accuracy(images: Tensor, labels: Tensor): Float = {
      val predictions = estimator.infer(images)
      predictions.argmax(1).cast(UINT8).equal(labels).cast(FLOAT32).mean().scalar.asInstanceOf[Float]
    }

    logger.info(s"Train accuracy = ${accuracy(dataSet.trainImages, dataSet.trainLabels)}")
    logger.info(s"Test accuracy = ${accuracy(dataSet.testImages, dataSet.testLabels)}")
    logger.info("haha Christoph")

    // val inputs = tf.placeholder(tf.UINT8, tf.shape(-1, numberOfRows, numberOfColumns))
    // val labels = tf.placeholder(tf.UINT8, tf.shape(-1))

    // // val predictions = mlpPredictionModel(Seq(128))(inputs)
    // val predictions = linearPredictionModel(inputs)
    // val supervision = supervisionModel(labels)

    // val loss = lossFunction(predictions, supervision)
    // val trainOp = tfl.adaGrad().minimize(loss)

    // val correctPredictions = tf.equal(tf.argmax(predictions, 1), supervision) // tf.argmax(supervision, 1))
    // val accuracy = tf.mean(tf.cast(correctPredictions, tf.FLOAT32))

    // logger.info("Training the linear regression model.")
    // logger.info(f"${"Iteration"}%10s | ${"Train Loss"}%13s | ${"Test Accuracy"}%13s")
    // val session = tf.session()
    // session.run(targets = tf.globalVariablesInitializer())
    // for (i <- 0 to 1000) {
    //   val feeds = Map(inputs -> dataSet.trainImages, labels -> dataSet.trainLabels)
    //   val trainLoss = session.run(feeds = feeds, fetches = loss, targets = trainOp)
    //   if (i % 1 == 0) {
    //     val testFeeds = Map(inputs -> dataSet.testImages, labels -> dataSet.testLabels)
    //     val testAccuracy = session.run(feeds = testFeeds, fetches = accuracy)
    //     logger.info(f"$i%10d | " +
    //                     f"${trainLoss.scalar.asInstanceOf[Float]}%13.4e | " +
    //                     f"${testAccuracy.scalar.asInstanceOf[Float]}%13.4e")
    //   }
    // }
  }
}
