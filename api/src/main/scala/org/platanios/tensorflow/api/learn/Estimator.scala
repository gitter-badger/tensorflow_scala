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

import org.platanios.tensorflow.api.config._
import org.platanios.tensorflow.api.core.Graph
import org.platanios.tensorflow.api.core.client.{Fetchable, SessionConfig}
import org.platanios.tensorflow.api.core.exception.{CheckpointNotFoundException, InvalidArgumentException}
import org.platanios.tensorflow.api.io.CheckpointReader
import org.platanios.tensorflow.api.learn.Estimator.ModelFunction
import org.platanios.tensorflow.api.learn.hooks._
import org.platanios.tensorflow.api.learn.utilities.ReplicaDevicePlacer
import org.platanios.tensorflow.api.ops.{Op, OpSpecification}
import org.platanios.tensorflow.api.ops.control_flow.ControlFlow
import org.platanios.tensorflow.api.ops.io.{Data, Dataset}
import org.platanios.tensorflow.api.ops.variables.Saver

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Path}

import scala.collection.immutable.TreeMap
import scala.collection.mutable

/** Estimator class to train and evaluate TensorFlow models.
  *
  * The [[Estimator]] class wraps a model which is specified by a `modelFunction`, which, given inputs and a number of
  * other parameters, creates the ops necessary to perform training, evaluation, or predictions, and provides an
  * interface for doing so.
  *
  * All outputs (checkpoints, event files, etc.) are written to a working directory, provided by `configurationBase`, or
  * a subdirectory thereof. If a working directory is not set in `configurationBase`, a temporary directory is used.
  *
  * The `configurationBase` argument can be passed a [[Configuration]] object containing information about the execution
  * environment. It is passed on to the `modelFunction`, if the `modelFunction` has an argument with [[Configuration]]
  * type (and input functions in the same manner). If the `configurationBase` argument is not passed, it is instantiated
  * by the [[Estimator]]. Not passing a configuration means that defaults useful for local execution are used. The
  * [[Estimator]] class makes the configuration available to the model (for instance, to allow specialization based on
  * the number of workers available), and also uses some of its fields to control internals, especially regarding saving
  * checkpoints while training.
  *
  * For models that have hyper-parameters it is recommended to incorporate them in `modelFunction` before instantiating
  * an estimator. This is in contrast to the TensorFlow Python API, but the reason behind the divergence is that the
  * estimator class never uses the provided hyper-parameters. The recommended way to deal with hyper-parameters in the
  * Scala API is to create a model function with two parameter lists, the first one being the hyper-parameters and the
  * second one being those supported by [[ModelFunction]] (i.e., optionally a [[Mode]] and a [[Configuration]]).
  *
  * None of the [[Estimator]] class's methods can be overridden in subclasses. Subclasses should use `modelFunction` to
  * configure the base class, and may add methods implementing specialized functionality.
  *
  * @param  modelFunction     Model-generating function that can optionally have a [[Configuration]] argument which will
  *                           be used to pass the estimator's configuration to the model and allows customizing the
  *                           model based on the execution environment.
  * @param  configurationBase Configuration base for this estimator. This allows for setting up distributed training
  *                           environments, for example. Note that this is a *base* for a configuration because the
  *                           estimator might modify it and set some missing fields to appropriate default values, in
  *                           order to obtain its final configuration that can be obtain through its `configuration`
  *                           field.
  *
  * @author Emmanouil Antonios Platanios
  */
class Estimator[IT, IO, ID, IS, I, TT, TO, TD, TS, T] private[learn] (
    val modelFunction: Estimator.ModelFunction[IT, IO, ID, IS, I, TT, TO, TD, TS, T],
    private[this] val configurationBase: Configuration = null) {
  /** Run configuration used for this estimator. */
  val configuration: Configuration = {
    // Process provided run configuration.
    val configuration = {
      if (configurationBase == null) {
        Estimator.logger.info("Using the default run configuration.")
        Configuration()
      } else {
        configurationBase.copy()
      }
    }

    // Process working directory.
    val configurationWithWorkingDir = {
      if (configuration.workingDir == null) {
        val workingDir = Files.createTempDirectory("estimator_working_dir")
        Estimator.logger.info(s"Using a temporary folder as working directory: $workingDir")
        configuration.copy(workingDir = Some(workingDir))
      } else {
        configuration
      }
    }

    // Process session configuration.
    val configurationWithSession = {
      if (configuration.sessionConfig == null) {
        Estimator.logger.info("Using the default session configuration with allowed soft placements.")
        configurationWithWorkingDir.copy(sessionConfig = Some(SessionConfig(allowSoftPlacement = Some(true))))
      } else {
        configurationWithWorkingDir
      }
    }

    configurationWithSession
  }

  /** Device function used by this estimator for managing replica device placement when using distributed training. */
  val deviceFunction: Option[(OpSpecification) => String] = Estimator.getReplicaDeviceSetter(configuration).map(_.apply)

  /** Working directory used by this estimator, used to save model parameters, graph, etc. It can also be used to load
    * checkpoints for a previously saved model. */
  def workingDir: Option[Path] = configuration.workingDir

  /** Session configuration used by this estimator. */
  def sessionConfig: Option[SessionConfig] = configuration.sessionConfig

  /** Checkpoint configuration used by this estimator. */
  def checkpointConfig: CheckpointConfig = configuration.checkpointConfig

  /** Summary configuration used by this estimator. */
  def summaryConfig: SummaryConfig = configuration.summaryConfig

  /** Frequency, in number of steps, that this estimator will log the global step / sec rate during training. */
  def globalStepRateLoggingFrequency: Int = configuration.globalStepRateLoggingFrequency

  /** Random seed value to be used by the TensorFlow initializers in this estimator. */
  def randomSeed: Int = configuration.randomSeed

  /** Trains the model managed by this estimator.
    *
    * '''NOTE:''' If you provide any summary saver or checkpoint saver hooks in `hooks` or `chiefOnlyHooks`, then the
    * checkpoint configuration in this estimator's `configuration` will be ignored for the chief and those hooks will be
    * used instead.
    *
    * @param  data                Training dataset. Each element is a tuple over input and training inputs (i.e.,
    *                             supervision labels).
    * @param  terminationCriteria Termination criteria to use for stopping the training iteration. For the default
    *                             criteria please refer to the documentation of [[StopCriteria]].
    * @param  hooks               Hooks to use while training (e.g., logging for the loss function value, etc.).
    * @param  chiefOnlyHooks      Hooks to use while training for the chief node only. This argument is only useful for
    *                             a distributed training setting.
    * @param  tensorBoardConfig   If provided, a TensorBoard server is launched using the provided configuration. In
    *                             that case, it is required that TensorBoard is installed for the default Python
    *                             environment in the system. If training in a distributed setting, the TensorBoard
    *                             server is launched on the chief node.
    */
  @throws[InvalidArgumentException]
  def train(
      data: Dataset[(IT, TT), (IO, TO), (ID, TD), (IS, TS)],
      terminationCriteria: StopCriteria = StopCriteria(),
      hooks: Seq[Hook] = Seq.empty,
      chiefOnlyHooks: Seq[Hook] = Seq.empty,
      tensorBoardConfig: TensorBoardConfig = null): Unit = {
    val needsToTrain = {
      if (!terminationCriteria.restartCounting) {
        workingDir.flatMap(dir => Saver.latestCheckpoint(dir).flatMap(latestPath => {
          CheckpointReader(latestPath).getTensor(Graph.Keys.GLOBAL_STEP.name)
        })).map(_.scalar.asInstanceOf[Long]).flatMap(s => terminationCriteria.maxSteps.map(_ <= s)).getOrElse(true)
      } else {
        true
      }
    }
    if (!needsToTrain) {
      Estimator.logger.info(
        "Skipping training because no restarting is allowed in the termination criteria and the maximum number of " +
            "steps have already been executed in the past (i.e., saved checkpoint).")
    } else {
      val allHooks = mutable.ListBuffer(hooks: _*)
      val allChiefOnlyHooks = mutable.ListBuffer(chiefOnlyHooks: _*)
      allHooks += StopHook(terminationCriteria)
      val model = modelFunction(configuration)
      val graph = Graph()
      Op.createWith(graph = graph, deviceFunction = deviceFunction.getOrElse(_.device)) {
        graph.setRandomSeed(randomSeed)
        Counter.getOrCreate(Graph.Keys.GLOBAL_EPOCH, graph)
        val step = Counter.getOrCreate(Graph.Keys.GLOBAL_STEP, graph)
        val trainingOps = Op.createWithNameScope("Model")(model.buildTrainOps())
        val inputInitializer = trainingOps.inputIterator.createInitializer(data)
        graph.addToCollection(trainingOps.loss, Graph.Keys.LOSSES)
        allHooks += TensorNaNHook(Set(trainingOps.loss.name))
        allHooks += TensorLoggingHook(TreeMap(
          "Step" -> step.value.name,
          "Loss" -> trainingOps.loss.name
        ), StepHookTrigger(100))
        if (tensorBoardConfig != null)
          allChiefOnlyHooks += TensorBoardHook(tensorBoardConfig)
        val saver = getOrCreateSaver()
        val session = Estimator.monitoredTrainingSession(
          configuration = configuration,
          hooks = allHooks,
          chiefOnlyHooks = allChiefOnlyHooks,
          sessionScaffold = SessionScaffold(
            initOp = Some(graph.globalVariablesInitializer()),
            localInitOp = Some(ControlFlow.group(Set(inputInitializer, graph.localVariablesInitializer()))),
            saver = Some(saver)))
        try {
          while (!session.shouldStop)
            session.run(targets = trainingOps.trainOp)
        } catch {
          case e if RECOVERABLE_EXCEPTIONS.contains(e.getClass) => session.close()
          case e: Throwable =>
            session.closeWithoutHookEnd()
            throw e
        } finally {
          if (!session.closed)
            session.close()
        }
      }
    }
  }

  /** Infers output (i.e., computes predictions) for `input` using the model managed by this estimator.
    *
    * This method requires that a checkpoint can be found in either `checkpointPath`, if provided, or in this
    * estimator's working directory. It first loads the trained parameter values from the checkpoint specified by
    * `checkpointPath` or from the latest checkpoint found in the working directory, and it then computes predictions
    * for `input`.
    *
    * `input` can be of one of the following types:
    *
    *   - A [[Dataset]], in which case this method returns an iterator over `(input, output)` tuples corresponding to
    *     each element in the dataset. Note that the predictions are computed lazily in this case, whenever an element
    *     is requested from the returned iterator.
    *   - A single input of type `IT`, in which case this method returns a prediction of type `I`.
    *
    * Note that, `ModelInferenceOutput` refers to the tensor type that corresponds to the symbolic type `I`. For
    * example, if `I` is `(Output, Output)`, then `ModelInferenceOutput` will be `(Tensor, Tensor)`.
    *
    * @param  input          Input for the predictions.
    * @param  hooks          Hooks to use while making predictions (e.g., logging for the loss function value, etc.).
    * @param  checkpointPath Path to a checkpoint file to use. If `null`, then the latest checkpoint found in this
    *                        estimator's working directory will be used.
    * @return Either an iterator over `(IT, ModelInferenceOutput)` tuples, or a single element of type `I`, depending on
    *         the type of `input`.
    * @throws CheckpointNotFoundException If no checkpoint could be found. This can happen if `checkpointPath` is `null`
    *                                     and no checkpoint could be found in this estimator's working directory.
    */
  // TODO: !!! [ESTIMATORS] Add an "infer" method that doesn't need to load a checkpoint (i.e., in-memory).
  @throws[CheckpointNotFoundException]
  def infer[InferInput, InferOutput, ModelInferenceOutput](
      input: InferInput,
      hooks: Seq[Hook] = Seq.empty,
      checkpointPath: Path = null)(implicit
      evFetchableI: Fetchable.Aux[I, ModelInferenceOutput],
      evFetchableIO: Fetchable.Aux[IO, IT],
      ev: Estimator.SupportedInferInput[InferInput, InferOutput, IT, IO, ID, IS, ModelInferenceOutput]
  ): InferOutput = {
    val _checkpointPath = Option(checkpointPath).orElse(Saver.latestCheckpoint(workingDir.get))
    if (_checkpointPath.isEmpty)
      throw CheckpointNotFoundException(
        "No checkpoint was found. Please provide a valid 'workingDir' the estimator configuration, or a path to a " +
            "valid checkpoint file through the 'checkpointPath' argument.")
    val model = modelFunction(configuration)
    val graph = Graph()
    Op.createWith(graph) {
      graph.setRandomSeed(randomSeed)
      Counter.getOrCreate(Graph.Keys.GLOBAL_EPOCH, graph)
      Counter.getOrCreate(Graph.Keys.GLOBAL_STEP, graph)
      val predictionOps = Op.createWithNameScope("Model")(model.buildPredictionOps())
      val inputInitializer = predictionOps.inputIterator.createInitializer(ev.toDataset(input))
      val saver = getOrCreateSaver()
      val session = MonitoredSession(
        ChiefSessionCreator(
          sessionScaffold = SessionScaffold(
            initOp = Some(graph.globalVariablesInitializer()),
            localInitOp = Some(ControlFlow.group(Set(inputInitializer, graph.localVariablesInitializer()))),
            saver = Some(saver)),
          sessionConfig = configuration.sessionConfig,
          checkpointPath = workingDir),
        hooks, shouldRecover = true)
      val output = ev.convertFetched(new Iterator[(IT, ModelInferenceOutput)] {
        override def hasNext: Boolean = session.shouldStop
        override def next(): (IT, ModelInferenceOutput) = {
          try {
            session.run(fetches = (predictionOps.input, predictionOps.output))
          } catch {
            case e: Throwable =>
              session.closeWithoutHookEnd()
              throw e
          }
        }
      })
      if (!session.closed)
        session.close()
      output
    }
  }

  /** Gets an existing saver from the current graph, or creates a new one if none exists. */
  private[this] def getOrCreateSaver(): Saver = {
    val graph = Op.currentGraph
    val savers = graph.getCollection(Graph.Keys.SAVERS)
    if (savers.isEmpty) {
      val saver = Saver(
        sharded = true,
        maxToKeep = configuration.checkpointConfig.maxCheckpointsToKeep,
        keepCheckpointEveryNHours = configuration.checkpointConfig.keepCheckpointEveryNHours,
        saveRelativePaths = true)
      graph.addToCollection(saver, Graph.Keys.SAVERS)
      saver
    } else {
      if (savers.size > 1)
        throw InvalidArgumentException("The graph should only contain one saver in the 'SAVERS' collection.")
      savers.head
    }
  }
}

object Estimator {
  private[Estimator] val logger = Logger(LoggerFactory.getLogger("Learn / Estimator"))

  def apply[IT, IO, ID, IS, I, TT, TO, TD, TS, T](
      modelFunction: Estimator.ModelFunction[IT, IO, ID, IS, I, TT, TO, TD, TS, T],
      configurationBase: Configuration = null): Estimator[IT, IO, ID, IS, I, TT, TO, TD, TS, T] = {
    new Estimator(modelFunction, configurationBase)
  }

  case class ModelFunction[IT, IO, ID, IS, I, TT, TO, TD, TS, T](
      function: (Configuration) => TrainableModel[IT, IO, ID, IS, I, TT, TO, TD, TS, T]) {
    def apply(configuration: Configuration): TrainableModel[IT, IO, ID, IS, I, TT, TO, TD, TS, T] = {
      function(configuration)
    }
  }

  trait Implicits {
    implicit def modelToModelFunction[IT, IO, ID, IS, I, TT, TO, TD, TS, T](
        model: TrainableModel[IT, IO, ID, IS, I, TT, TO, TD, TS, T]
    ): ModelFunction[IT, IO, ID, IS, I, TT, TO, TD, TS, T] = {
      ModelFunction((_: Configuration) => model)
    }

    implicit def unitFunctionToModelFunction[IT, IO, ID, IS, I, TT, TO, TD, TS, T](
        function: () => TrainableModel[IT, IO, ID, IS, I, TT, TO, TD, TS, T]
    ): ModelFunction[IT, IO, ID, IS, I, TT, TO, TD, TS, T] = {
      ModelFunction((_: Configuration) => function())
    }

    implicit def unaryRunConfigFunctionToModelFunction[IT, IO, ID, IS, I, TT, TO, TD, TS, T](
        function: (Configuration) => TrainableModel[IT, IO, ID, IS, I, TT, TO, TD, TS, T]
    ): ModelFunction[IT, IO, ID, IS, I, TT, TO, TD, TS, T] = {
      ModelFunction(function)
    }
  }

  /** Creates a replica device setter, if required, to be used as a default device function.
    *
    * Estimators use a [[ReplicaDevicePlacer]] as a default device placer. It sets the distributed related arguments
    * such as the number of parameter server replicas based on the provided run configuration.
    *
    * @param  configuration Configuration.
    * @return Constructed replica device placer.
    */
  private[Estimator] def getReplicaDeviceSetter(configuration: Configuration): Option[ReplicaDevicePlacer] = {
    if (configuration.numParameterServers > 0) {
      Some(ReplicaDevicePlacer(
        psNumTasks = configuration.numParameterServers,
        workerDevice = s"/job:${configuration.taskType}/task:${configuration.taskIndex}",
        clusterConfig = configuration.clusterConfig.orNull,
        psOpTypes = Set(
          "Variable", "VariableV2", "AutoReloadVariable", "MutableHashTable", "MutableHashTableV2",
          "MutableHashTableOfTensors", "MutableHashTableOfTensorsV2", "MutableDenseHashTable",
          "MutableDenseHashTableV2")))
    } else {
      None
    }
  }

  /** Creates a [[MonitoredSession]] to be used for training.
    *
    * For a chief, this utility sets proper session initializers, savers, and restorers. It also creates hooks related
    * to checkpoint and summary saving. For workers, this utility method sets the proper session creator which waits for
    * the chief to initialize or restore the session. Please refer to [[MonitoredSession]] for more information.
    *
    * '''NOTE:''' If you provide any summary saver or checkpoint saver hooks in `hooks` or `chiefOnlyHooks`, then the
    * checkpoint configuration in `configuration` will be ignored for the chief and those hooks will be used instead.
    *
    * @param  configuration   Configuration to use for this session. Contains information related to the session
    *                         configuration, the cluster configuration, etc.
    * @param  hooks           Hooks to use while training.
    * @param  chiefOnlyHooks  Hooks to use for the chief. These will only be used if `isChief` is `true`.
    * @param  sessionScaffold Session scaffold used for gathering and/or building supportive ops. If not specified, a
    *                         default one is created. The session scaffold is used to finalize the graph.
    * @return Created monitored session.
    */
  private[Estimator] def monitoredTrainingSession(
      configuration: Configuration = Configuration(),
      hooks: Seq[Hook] = Seq.empty,
      chiefOnlyHooks: Seq[Hook] = Seq.empty,
      sessionScaffold: SessionScaffold = SessionScaffold()): MonitoredSession = {
    if (!configuration.isChief) {
      val sessionCreator = WorkerSessionCreator(configuration.master, sessionScaffold, configuration.sessionConfig)
      MonitoredSession(sessionCreator, hooks)
    } else {
      val sessionCreator = ChiefSessionCreator(
        configuration.master, sessionScaffold, configuration.sessionConfig, configuration.workingDir)
      val chiefHooks = mutable.ListBuffer(hooks ++ chiefOnlyHooks: _*)
      configuration.workingDir.foreach(workingDir => {
        chiefHooks += StepRateHook(log = false, summaryDirectory = workingDir)
        if (!chiefHooks.exists(_.isInstanceOf[SummarySaverHook])) {
          configuration.summaryConfig match {
            case NoSummaries => ()
            case StepBasedSummaries(steps) => chiefHooks += SummarySaverHook(workingDir, StepHookTrigger(steps))
            case TimeBasedSummaries(seconds) => chiefHooks += SummarySaverHook(workingDir, TimeHookTrigger(seconds))
          }
        }
        if (!chiefHooks.exists(_.isInstanceOf[CheckpointSaverHook])) {
          configuration.checkpointConfig match {
            case NoCheckpoints => ()
            case StepBasedCheckpoints(steps, _, _) =>
              chiefHooks += CheckpointSaverHook(workingDir, StepHookTrigger(steps))
            case TimeBasedCheckpoints(seconds, _, _) =>
              chiefHooks += CheckpointSaverHook(workingDir, TimeHookTrigger(seconds))
          }
        }
      })
      MonitoredSession(sessionCreator, chiefHooks)
    }
  }

  trait SupportedInferInput[InferInput, InferOutput, T, O, D, S, ModelInferenceOutput] {
    def toDataset(value: InferInput): Dataset[T, O, D, S]
    def convertFetched(iterator: Iterator[(T, ModelInferenceOutput)]): InferOutput
  }

  object SupportedInferInput {
    implicit def datasetInferInput[T, O, D, S, I](implicit
        ev: Data.Aux[T, O, D, S]
    ): SupportedInferInput[Dataset[T, O, D, S], Iterator[(T, I)], T, O, D, S, I] = {
      new SupportedInferInput[Dataset[T, O, D, S], Iterator[(T, I)], T, O, D, S, I] {
        override def toDataset(value: Dataset[T, O, D, S]): Dataset[T, O, D, S] = value
        override def convertFetched(iterator: Iterator[(T, I)]): Iterator[(T, I)] = iterator
      }
    }

    implicit def singleValueInferInput[T, O, D, S, I](implicit
        ev: Data.Aux[T, O, D, S]
    ): SupportedInferInput[T, I, T, O, D, S, I] = new SupportedInferInput[T, I, T, O, D, S, I] {
      override def toDataset(value: T): Dataset[T, O, D, S] = Dataset.from[T, O, D, S](value)
      override def convertFetched(iterator: Iterator[(T, I)]): I = iterator.next()._2
    }
  }
}
