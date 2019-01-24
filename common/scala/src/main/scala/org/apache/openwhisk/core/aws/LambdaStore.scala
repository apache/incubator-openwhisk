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

package org.apache.openwhisk.core.aws
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64
import java.util.zip.{ZipEntry, ZipOutputStream}

import akka.Done
import akka.http.scaladsl.model.StatusCodes.{InternalServerError, OK}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.openwhisk.common.{CausedBy, Logging, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.containerpool.{Interval, RunResult}
import org.apache.openwhisk.core.entity.ActivationResponse.{ConnectionError, ContainerResponse}
import org.apache.openwhisk.core.entity.Attachments.Inline
import org.apache.openwhisk.core.entity.{CodeExecAsAttachment, WhiskAction}
import pureconfig._
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambda.LambdaAsyncClient
import software.amazon.awssdk.services.lambda.model.{
  FunctionCode,
  InvocationType,
  InvokeRequest,
  ResourceNotFoundException,
  UpdateFunctionCodeRequest,
  Runtime => LambdaRuntime
}
import spray.json.JsObject

import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

object LambdaStoreProvider {

  def makeStore(config: Config = ConfigFactory.defaultApplication())(implicit ec: ExecutionContext,
                                                                     logging: Logging): LambdaStore = {
    val awsConfig = config.atPath(ConfigKeys.aws)
    val region = RegionProvider(awsConfig).getRegion
    val client = LambdaAsyncClient
      .builder()
      .credentialsProvider(CredentialProvider(awsConfig))
      .region(region)
      .build()
    val lambdaConfig = loadConfigOrThrow[LambdaConfig](config, ConfigKeys.lambda)
    new LambdaStore(client, lambdaConfig, region)
  }
}

case class LambdaConfig(layerMappings: Map[String, String], accountId: String, commonRoleName: String)

case class LambdaAction(arn: String)

class LambdaStore(client: LambdaAsyncClient, config: LambdaConfig, region: Region)(implicit ec: ExecutionContext,
                                                                                   logging: Logging) {
  import LambdaStore._
  def invoke(name: String, body: JsObject)(implicit transid: TransactionId): Future[RunResult] = {
    val started = Instant.now()
    val request = InvokeRequest
      .builder()
      .functionName(name)
      .invocationType(InvocationType.REQUEST_RESPONSE)
      .payload(SdkBytes.fromUtf8String(body.toString()))
      .build()

    //TODO Need to make use of lambda versioning to ensure that action being invoked is same as the
    //lambda function revision

    //TODO Response provides the revisionId. So would be good to assert against that

    //TODO Cache the whisk action rev to lambda revision mapping

    //TODO Apply timeout. Looks like timeout is only used in case retry = true
    //for /run case retry is false. So need not bother about timeout
    client
      .invoke(request)
      .toScala
      .map { invokeResponse =>
        // Lambda has 6 MB limit on request and response. So we may need to truncate the response
        val entity = invokeResponse.payload().asUtf8String()
        val res = invokeResponse.functionError() match {
          //TODO Map error code - Default nodejs runtime seems to return 502 for some of the handled error messages
          //TODO Honor MaxResponse
          //TODO Need to stuff requestId someway with activationResponse to allow corelating the logs
          case "Handled"   => ContainerResponse(502, entity, None)
          case "Unhandled" => ContainerResponse(InternalServerError.intValue, entity, None)
          case _           => ContainerResponse(OK.intValue, entity, None)
        }
        Right(res)
      }
      .recover {
        case NonFatal(CausedBy(t)) => Left(ConnectionError(t))
      }
      .map { response =>
        val finished = Instant.now()
        RunResult(Interval(started, finished), response)
      }
  }

  def createOrUpdate(action: WhiskAction)(implicit transid: TransactionId): Future[Option[LambdaAction]] = {
    require(!action.rev.empty, s"WhiskAction [$action] needs to have revision specified")

    val r = for {
      layer <- getMatchingLayer(action)
      handlerName <- getHandlerName(action)
      code <- getFunctionCode(action)
    } yield (layer, handlerName, code)

    r.map {
        case (layer, handlerName, code) =>
          val funcName = getFunctionName(action)
          val arn = functionARN(funcName)
          val actionRev = action.rev.asString
          getFunctionWhiskRevision(arn).flatMap {
            case Some(`actionRev`) =>
              //Function exists and uptodate. No change needed
              Future.successful(Some(LambdaAction(arn)))
            case Some(x) =>
              logging.info(
                this,
                s"Lambda function revision [$x] does not match action revision [${action.rev}]. Would update the action")
              updateFunction(arn, action, layer, handlerName, code)
            case _ =>
              createFunction(action, layer, handlerName, code, funcName)
          }
      }
      .getOrElse(Future.successful(None))
  }

  def delete(action: WhiskAction)(implicit transid: TransactionId): Future[Done] = {
    val funcName = getFunctionName(action)
    client
      .deleteFunction(r => r.functionName(funcName))
      .toScala
      .map { _ =>
        logging.info(
          this,
          s"Deleted lambda function [$funcName] which mapped to action [${action.fullyQualifiedName(false)}]")
        Done
      }
      .recover {
        case CausedBy(_: ResourceNotFoundException) =>
          logging.warn(this, s"No lambda function found for [${action.fullyQualifiedName(false)}]")
          Done
      }
  }

  private def createFunction(action: WhiskAction,
                             layer: String,
                             handlerName: String,
                             code: FunctionCode,
                             funcName: String)(implicit transid: TransactionId): Future[Option[LambdaAction]] = {
    for {
      role <- getOrCreateRole(funcName)
      lambda <- createFunction(action, layer, handlerName, code, funcName, role)
    } yield lambda
  }

  private def createFunction(action: WhiskAction,
                             layer: String,
                             handlerName: String,
                             code: FunctionCode,
                             funcName: String,
                             role: String)(implicit transid: TransactionId): Future[Option[LambdaAction]] = {
    client
      .createFunction(
        r =>
          r.code(code)
            .handler(handlerName)
            .layers(layer)
            .runtime(LambdaRuntime.PROVIDED)
            .functionName(funcName)
            .memorySize(getFunctionMemory(action))
            .timeout(getFunctionTimeout(action))
            .role(role)
            .tags(getTags(action).asJava))
      .toScala
      .map(response => Some(LambdaAction(response.functionArn())))
  }

  //TODO Create a AnyVal for ARN
  def functionARN(funcName: String): String = s"arn:aws:lambda:${region.id()}:${config.accountId}:function:$funcName"

  def getFunctionWhiskRevision(arn: String)(implicit transid: TransactionId): Future[Option[String]] = {
    client
      .listTags(r => r.resource(arn))
      .toScala
      .map(r => r.tags().asScala.get(whiskRevision))
      .recover {
        case CausedBy(_: ResourceNotFoundException) => None
      }
  }

  private def updateFunction(arn: String, action: WhiskAction, layer: String, handlerName: String, code: FunctionCode)(
    implicit transid: TransactionId): Future[Option[LambdaAction]] = {
    //By design it should be update by only single process. So update the tag at end
    //Which would ensure that code is matching the action revision in OW

    //TODO Looks like tags are not part of revision. So we may need to add rev to configuration (say description or env)
    //And then publish it
    for {
      _ <- updateFunctionConfiguration(arn, action, layer, handlerName)
      _ <- updateFunctionCode(arn, code)
      _ <- updateFunctionTag(arn, getTags(action))
    } yield Some(LambdaAction(arn))
  }

  private def updateFunctionConfiguration(arn: String, action: WhiskAction, layer: String, handlerName: String) = {
    client
      .updateFunctionConfiguration(
        r =>
          r.handler(handlerName)
            .functionName(arn)
            .layers(layer)
            .memorySize(getFunctionMemory(action))
            .timeout(getFunctionTimeout(action)))
      .toScala
  }

  private def updateFunctionTag(arn: String, tags: Map[String, String]) = {
    client.tagResource(r => r.resource(arn).tags(tags.asJava)).toScala
  }

  private def updateFunctionCode(arn: String, code: FunctionCode) = {
    val builder = UpdateFunctionCodeRequest.builder()
    if (code.zipFile() != null) {
      builder.zipFile(code.zipFile())
    } else {
      builder.s3Key(code.s3Key())
      builder.s3Bucket(code.s3Bucket())
    }
    client.updateFunctionCode(builder.build()).toScala
  }

  def getMatchingLayer(action: WhiskAction): Option[String] = {
    config.layerMappings.get(action.exec.kind)
  }

  def getOrCreateRole(functionName: String): Future[String] = {
    //TODO Temp usage of a generic role. Need to create per function role
    Future.successful(config.commonRoleName)
  }

  def getHandlerName(action: WhiskAction): Option[String] = {
    action.exec match {
      case exec @ CodeExecAsAttachment(_, _, entryPoint, _) =>
        if (isNodeJs(exec.kind)) Some(s"index.${entryPoint.getOrElse("main")}") else None
      case _ => None
    }
  }

  //TODO Other attributes to add
  //TODO Remove revision from tags
  def getTags(action: WhiskAction): Map[String, String] =
    Map(
      "fqn" -> action.fullyQualifiedName(true).asString,
      whiskRevision -> action.rev.asString,
      "namespace" -> action.namespace.asString,
      "name" -> action.name.asString)

  def getFunctionCode(action: WhiskAction): Option[FunctionCode] = {
    action.exec match {
      case exec @ CodeExecAsAttachment(_, Inline(code), entryPoint, binary) =>
        Some(createFunctionCode(code, binary, entryPoint, exec.kind))
      //TODO handle attachments
      case _ => None
    }
  }
}

object LambdaStore {
  val whiskRevision = "ow_rev"

  def getFunctionName(action: WhiskAction): String = {
    //TODO Lambda places a 64 char limit and OW allows much larger names
    //So need a way to encode name say via `<functionName{0,40}>_<10 letters from hash>`
    val name = action.fullyQualifiedName(false).asString.replace("/", "_")
    s"ow_$name"
  }

  def getFunctionMemory(action: WhiskAction): Int = {
    val mb = action.limits.memory.megabytes
    val delta = mb % 64

    //Memory needs to be in chunk of 64 MB
    mb + delta
  }

  def getFunctionTimeout(action: WhiskAction): Int = {
    //Lambda imposes max limit of 900 secs
    action.limits.timeout.duration.toSeconds.toInt
  }

  def createFunctionCode(code: String, binary: Boolean, entryPoint: Option[String], kind: String): FunctionCode = {
    val bytes = if (binary) {
      Base64.getDecoder.decode(code)
    } else {
      createZip(adaptCode(code, entryPoint, kind), getFileName(kind))
    }
    FunctionCode.builder().zipFile(SdkBytes.fromByteArray(bytes)).build()
  }

  private def adaptCode(code: String, entryPoint: Option[String], kind: String): Array[Byte] = {
    val adaptedCode = if (isNodeJs(kind)) {
      val main = entryPoint.getOrElse("main")
      code + s"\nexports.$main = $main;"
    } else {
      code
    }
    adaptedCode.getBytes(UTF_8)
  }

  private def getFileName(kind: String): String =
    if (isNodeJs(kind)) "index.js" else throw new IllegalArgumentException(s"Unsupported kind [$kind]")

  private def isNodeJs(kind: String) = kind.startsWith("node")

  private def createZip(bytes: Array[Byte], fileName: String): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val zos = new ZipOutputStream(baos)
    val zipEntry = new ZipEntry(fileName)
    zos.putNextEntry(zipEntry)
    zos.write(bytes, 0, bytes.length)
    zos.closeEntry()
    zos.close()
    baos.toByteArray
  }
}
