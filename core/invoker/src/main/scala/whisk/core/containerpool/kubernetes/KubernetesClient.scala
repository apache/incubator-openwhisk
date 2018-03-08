/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.containerpool.kubernetes

import java.io.{FileNotFoundException, IOException}
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Paths
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatterBuilder

import akka.actor.ActorSystem
import akka.event.Logging.{ErrorLevel, InfoLevel}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{Attributes, Outlet, SourceShape}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.stream.stage._
import akka.util.ByteString
import io.fabric8.kubernetes.api.model._
import pureconfig.loadConfigOrThrow
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId
import whisk.core.ConfigKeys
import whisk.core.containerpool.ContainerId
import whisk.core.containerpool.ContainerAddress
import whisk.core.containerpool.docker.ProcessRunner
import whisk.core.entity.ByteSize
import whisk.core.entity.size._

import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import spray.json._
import spray.json.DefaultJsonProtocol._
import collection.JavaConverters._

import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import okhttp3.{Call, Callback, Request, Response}
import okio.BufferedSource
import whisk.core.entity.ByteSize

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.control.NonFatal

/**
 * Configuration for kubernetes client command timeouts.
 */
case class KubernetesClientTimeoutConfig(run: Duration, rm: Duration, inspect: Duration, logs: Duration)

/**
 * Configuration for kubernetes invoker-agent
 */
case class KubernetesInvokerAgentConfig(enabled: Boolean, port: Int)

/**
 * General configuration for kubernetes client
 */
case class KubernetesClientConfig(timeouts: KubernetesClientTimeoutConfig, invokerAgent: KubernetesInvokerAgentConfig)

/**
 * Serves as interface to the kubectl CLI tool.
 *
 * Be cautious with the ExecutionContext passed to this, as the
 * calls to the CLI are blocking.
 *
 * You only need one instance (and you shouldn't get more).
 */
class KubernetesClient(
  config: KubernetesClientConfig = loadConfigOrThrow[KubernetesClientConfig](ConfigKeys.kubernetes))(
  executionContext: ExecutionContext)(implicit log: Logging, as: ActorSystem)
    extends KubernetesApi
    with ProcessRunner {
  implicit private val ec = executionContext
  implicit private val am = ActorMaterializer()
  implicit private val kubeRestClient = new DefaultKubernetesClient(
    new ConfigBuilder()
      .withConnectionTimeout(config.timeouts.logs.toMillis.toInt)
      .withRequestTimeout(config.timeouts.logs.toMillis.toInt)
      .build())

  // Determines how to run kubectl. Failure to find a kubectl binary implies
  // a failure to initialize this instance of KubernetesClient.
  protected def findKubectlCmd(): String = {
    val alternatives = List("/usr/bin/kubectl", "/usr/local/bin/kubectl")
    val kubectlBin = Try {
      alternatives.find(a => Files.isExecutable(Paths.get(a))).get
    } getOrElse {
      throw new FileNotFoundException(s"Couldn't locate kubectl binary (tried: ${alternatives.mkString(", ")}).")
    }
    kubectlBin
  }
  protected val kubectlCmd = Seq(findKubectlCmd)

  def run(name: String,
          image: String,
          memory: ByteSize = 256.MB,
          environment: Map[String, String] = Map.empty,
          labels: Map[String, String] = Map.empty)(implicit transid: TransactionId): Future[KubernetesContainer] = {

    val envVars = environment.map {
      case (key, value) => new EnvVarBuilder().withName(key).withValue(value).build()
    }.toSeq

    val pod = new PodBuilder()
      .withNewMetadata()
      .withName(name)
      .addToLabels("name", name)
      .addToLabels(labels.asJava)
      .endMetadata()
      .withNewSpec()
      .withRestartPolicy("Always")
      .addNewContainer()
      .withNewResources()
      .withLimits(Map("memory" -> new Quantity(memory.toMB + "Mi")).asJava)
      .endResources()
      .withName("user-action")
      .withImage(image)
      .withEnv(envVars.asJava)
      .addNewPort()
      .withContainerPort(8080)
      .withName("action")
      .endPort()
      .endContainer()
      .endSpec()
      .build()

    val namespace = kubeRestClient.getNamespace
    kubeRestClient.pods.inNamespace(namespace).create(pod)

    Future {
      blocking {
        val createdPod = kubeRestClient.pods
          .inNamespace(namespace)
          .withName(name)
          .waitUntilReady(config.timeouts.run.length, config.timeouts.run.unit)
        toContainer(createdPod)
      }
    }.recoverWith {
      case e =>
        log.error(this, s"Failed create pod for '$name': ${e.getClass} - ${e.getMessage}")
        Future.failed(new Exception(s"Failed to create pod '$name'"))
    }
  }

  def rm(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit] = {
    runCmd(Seq("delete", "--now", "pod", container.id.asString), config.timeouts.rm).map(_ => ())
  }

  def rm(key: String, value: String, ensureUnpaused: Boolean = false)(implicit transid: TransactionId): Future[Unit] = {
    if (ensureUnpaused && config.invokerAgent.enabled) {
      // The caller can't guarantee that every container with the label key=value is already unpaused.
      // Therefore we must enumerate them and ensure they are unpaused before we attempt to delete them.
      Future {
        blocking {
          kubeRestClient
            .inNamespace(kubeRestClient.getNamespace)
            .pods()
            .withLabel(key, value)
            .list()
            .getItems
            .asScala
            .map { pod =>
              val container = toContainer(pod)
              container
                .resume()
                .recover { case _ => () } // Ignore errors; it is possible the container was not actually suspended.
                .map(_ => rm(container))
            }
        }
      }.flatMap(futures =>
        Future
          .sequence(futures)
          .map(_ => ()))
    } else {
      runCmd(Seq("delete", "--now", "pod", "-l", s"$key=$value"), config.timeouts.rm).map(_ => ())
    }
  }

  def suspend(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit] = {
    if (config.invokerAgent.enabled) {
      agentCommand("suspend", container)
        .map { response =>
          response.discardEntityBytes()
        }
    } else {
      Future.successful({})
    }
  }

  def resume(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit] = {
    if (config.invokerAgent.enabled) {
      agentCommand("resume", container)
        .map { response =>
          response.discardEntityBytes()
        }
    } else {
      Future.successful({})
    }
  }

  def forwardLogs(container: KubernetesContainer,
                  lastOffset: Long,
                  sizeLimit: ByteSize,
                  sentinelledLogs: Boolean,
                  additionalMetadata: Map[String, JsValue],
                  augmentedActivation: JsObject)(implicit transid: TransactionId): Future[Long] = {
    if (config.invokerAgent.enabled) {
      val serializedData = Map(
        "lastOffset" -> JsNumber(lastOffset),
        "sizeLimit" -> JsNumber(sizeLimit.toBytes),
        "sentinelledLogs" -> JsBoolean(sentinelledLogs),
        "encodedLogLineMetadata" -> JsString(fieldsString(additionalMetadata)),
        "encodedActivation" -> JsString(augmentedActivation.compactPrint))
      agentCommand("logs", container, serializedData)
        .flatMap { response =>
          Unmarshal(response.entity).to[String].map(s => s.toLong)
        }
    } else {
      Future.failed(new UnsupportedOperationException("forwardLogs requires whisk.kubernetes.invokerAgent=true"))
    }
  }

  def logs(container: KubernetesContainer, sinceTime: Option[Instant], waitForSentinel: Boolean = false)(
    implicit transid: TransactionId): Source[TypedLogLine, Any] = {

    log.debug(this, "Parsing logs from Kubernetes Graph Stage…")

    Source
      .fromGraph(new KubernetesRestLogSourceStage(container.id, sinceTime, waitForSentinel))
      .log("foobar")

  }

  private def fieldsString(fields: Map[String, JsValue]) =
    fields
      .map {
        case (key, value) => s""""$key":${value.compactPrint}"""
      }
      .mkString(",")

  private def toContainer(pod: Pod): KubernetesContainer = {
    val id = ContainerId(pod.getMetadata.getName)
    val addr = ContainerAddress(pod.getStatus.getPodIP)
    val workerIP = pod.getStatus.getHostIP
    // Extract the native (docker or containerd) containerId for the container
    // By convention, kubernetes adds a docker:// prefix when using docker as the low-level container engine
    val nativeContainerId = pod.getStatus.getContainerStatuses.get(0).getContainerID.stripPrefix("docker://")
    implicit val kubernetes = this
    new KubernetesContainer(id, addr, workerIP, nativeContainerId)
  }

  // Forward a command to invoker-agent daemonset instance on container's worker node
  private def agentCommand(command: String,
                           container: KubernetesContainer,
                           payload: Map[String, JsValue] = Map.empty): Future[HttpResponse] = {
    val uri = Uri()
      .withScheme("http")
      .withHost(container.workerIP)
      .withPort(config.invokerAgent.port)
      .withPath(Path / command / container.nativeContainerId)
    Http().singleRequest(HttpRequest(uri = uri, entity = payload.toJson.compactPrint))
  }

  private def runCmd(args: Seq[String], timeout: Duration)(implicit transid: TransactionId): Future[String] = {
    val cmd = kubectlCmd ++ args
    val start = transid.started(
      this,
      LoggingMarkers.INVOKER_KUBECTL_CMD(args.head),
      s"running ${cmd.mkString(" ")} (timeout: $timeout)",
      logLevel = InfoLevel)
    executeProcess(cmd, timeout).andThen {
      case Success(_) => transid.finished(this, start)
      case Failure(t) => transid.failed(this, start, t.getMessage, ErrorLevel)
    }
  }
}

object KubernetesClient {

  // Necessary, as Kubernetes uses nanosecond precision in logs, but java.time.Instant toString uses milliseconds
  //%Y-%m-%dT%H:%M:%S.%N%z
  val K8STimestampFormat = new DateTimeFormatterBuilder()
    .parseCaseInsensitive()
    .appendPattern("u-MM-dd")
    .appendLiteral('T')
    .appendPattern("HH:mm:ss[.n]")
    .appendLiteral('Z')
    .toFormatter()
    .withZone(ZoneId.of("UTC"))

  def parseK8STimestamp(ts: String): Try[Instant] =
    Try(Instant.from(K8STimestampFormat.parse(ts)))

  def formatK8STimestamp(ts: Instant): Try[String] =
    Try(K8STimestampFormat.format(ts))
}

trait KubernetesApi {
  def run(name: String,
          image: String,
          memory: ByteSize,
          environment: Map[String, String] = Map.empty,
          labels: Map[String, String] = Map.empty)(implicit transid: TransactionId): Future[KubernetesContainer]

  def rm(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit]

  def rm(key: String, value: String, ensureUnpaused: Boolean)(implicit transid: TransactionId): Future[Unit]

  def suspend(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit]

  def resume(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit]

  def forwardLogs(container: KubernetesContainer,
                  lastOffset: Long,
                  sizeLimit: ByteSize,
                  sentinelledLogs: Boolean,
                  additionalMetadata: Map[String, JsValue],
                  augmentedActivation: JsObject)(implicit transid: TransactionId): Future[Long]

  def logs(container: KubernetesContainer, sinceTime: Option[Instant], waitForSentinel: Boolean = false)(
    implicit transid: TransactionId): Source[TypedLogLine, Any]
}

object KubernetesRestLogSourceStage {

  import KubernetesClient.{formatK8STimestamp, parseK8STimestamp}

  def constructPath(namespace: String, containerId: String): Path =
    Path / "api" / "v1" / "namespaces" / namespace / "pods" / containerId / "log"

  def constructQuery(sinceTime: Option[Instant], waitForSentinel: Boolean): Query = {

    val sinceTimestamp = sinceTime.flatMap(time => formatK8STimestamp(time).toOption)

    Query(Map("timestamps" -> "true") ++ sinceTimestamp.map(time => "sinceTime" -> time))

  }

  @tailrec
  def readLines(src: BufferedSource,
                lastTimestamp: Option[Instant],
                lines: Seq[TypedLogLine] = Seq.empty[TypedLogLine]): Seq[TypedLogLine] = {

    if (!src.exhausted()) {
      (for {
        line <- Option(src.readUtf8Line()) if !line.isEmpty
        timestampDelimiter = line.indexOf(" ")
        // Kubernetes is ignoring nanoseconds in sinceTime, so we have to filter additionally here
        rawTimestamp = line.substring(0, timestampDelimiter)
        timestamp <- parseK8STimestamp(rawTimestamp).toOption if isRelevantLogLine(lastTimestamp, timestamp)
        msg = line.substring(timestampDelimiter + 1)
        stream = "stdout" // TODO - when we can distinguish stderr: https://github.com/kubernetes/kubernetes/issues/28167
      } yield {
        TypedLogLine(timestamp, stream, msg)
      }) match {
        case Some(logLine) =>
          readLines(src, Option(logLine.time), lines :+ logLine)
        case None =>
          // we may have skipped a line for filtering conditions only; keep going
          readLines(src, lastTimestamp, lines)
      }
    } else {
      lines
    }

  }

  def isRelevantLogLine(lastTimestamp: Option[Instant], newTimestamp: Instant): Boolean =
    lastTimestamp match {
      case Some(last) =>
        newTimestamp.isAfter(last)
      case None =>
        true
    }

}

final class KubernetesRestLogSourceStage(id: ContainerId, sinceTime: Option[Instant], waitForSentinel: Boolean)(
  implicit val kubeRestClient: DefaultKubernetesClient)
    extends GraphStage[SourceShape[TypedLogLine]] { stage =>

  import KubernetesRestLogSourceStage._

  val out = Outlet[TypedLogLine]("K8SHttpLogging.out")

  override val shape: SourceShape[TypedLogLine] = SourceShape.of(out)

  override protected def initialAttributes: Attributes = Attributes.name("KubernetesHttpLogSource")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogicWithLogging(shape) { logic =>

      private val queue = mutable.Queue.empty[TypedLogLine]
      private var lastTimestamp = sinceTime

      def fetchLogs(): Unit =
        try {
          val path = constructPath(kubeRestClient.getNamespace, id.asString)
          val query = constructQuery(lastTimestamp, waitForSentinel)

          log.debug("* Fetching K8S HTTP Logs w/ Path: {} Query: {}", path, query)

          val url = Uri(kubeRestClient.getMasterUrl.toString)
            .withPath(path)
            .withQuery(query)

          val request = new Request.Builder().get().url(url.toString).build

          kubeRestClient.getHttpClient.newCall(request).enqueue(new LogFetchCallback())
        } catch {
          case NonFatal(e) =>
            onFailure(e)
            throw e
        }

      def onFailure(e: Throwable): Unit = e match {
        case _: SocketTimeoutException =>
          log.warning("* Logging socket to Kubernetes timed out.") // this should only happen with follow behavior
        case _ =>
          log.error(e, "* Retrieving the logs from Kubernetes failed.")
      }

      val emitCallback: AsyncCallback[Seq[TypedLogLine]] = getAsyncCallback[Seq[TypedLogLine]] {
        case firstLine +: restOfLines if isAvailable(out) =>
          pushLine(firstLine)
          queue ++= restOfLines
        case lines =>
          queue ++= lines
      }

      class LogFetchCallback extends Callback {

        override def onFailure(call: Call, e: IOException): Unit = logic.onFailure(e)

        override def onResponse(call: Call, response: Response): Unit =
          try {
            val lines = readLines(response.body.source, lastTimestamp)

            response.body.source.close()

            lines.lastOption.foreach { line =>
              lastTimestamp = Option(line.time)
            }

            emitCallback.invoke(lines)
          } catch {
            case NonFatal(e) =>
              log.error(e, "* Reading Kubernetes HTTP Response failed.")
              logic.onFailure(e)
              throw e
          }
      }

      def pushLine(line: TypedLogLine): Unit = {
        log.debug("* Pushing a chunk of kubernetes logging: {}", line)
        push(out, line)
      }

      setHandler(
        out,
        new OutHandler {
          override def onPull(): Unit = {
            // if we still have lines queued up, return those; else make a new HTTP read.
            if (queue.nonEmpty)
              pushLine(queue.dequeue())
            else
              fetchLogs()
          }
        })
    }
}

protected[core] final case class TypedLogLine(time: Instant, stream: String, log: String) {
  import KubernetesClient.formatK8STimestamp

  lazy val toJson: JsObject =
    JsObject("time" -> formatK8STimestamp(time).getOrElse("").toJson, "stream" -> stream.toJson, "log" -> log.toJson)

  lazy val jsonPrinted: String = toJson.compactPrint
  lazy val jsonSize: Int = jsonPrinted.length

  /**
   * Returns a ByteString representation of the json for this Log Line
   */
  val toByteString = ByteString(jsonPrinted)

  override def toString = s"${formatK8STimestamp(time).get} $stream: ${log.trim}"
}

protected[core] object TypedLogLine {

  import KubernetesClient.{parseK8STimestamp, K8STimestampFormat}

  def readInstant(json: JsValue): Instant = json match {
    case JsString(str) =>
      parseK8STimestamp(str) match {
        case Success(time) =>
          time
        case Failure(e) =>
          deserializationError(
            s"Could not parse a java.time.Instant from $str (Expected in format: $K8STimestampFormat: $e")
      }
    case _ =>
      deserializationError(s"Could not parse a java.time.Instant from $json (Expected in format: $K8STimestampFormat)")
  }

  implicit val typedLogLineFormat = new RootJsonFormat[TypedLogLine] {
    override def write(obj: TypedLogLine): JsValue = obj.toJson

    override def read(json: JsValue): TypedLogLine = {
      val obj = json.asJsObject
      val fields = obj.fields
      TypedLogLine(readInstant(fields("time")), fields("stream").convertTo[String], fields("log").convertTo[String])
    }
  }

}
