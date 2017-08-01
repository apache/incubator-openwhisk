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

package whisk.core.controller.test

import java.time.Clock
import java.time.Instant

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.core.controller.WhiskActivationsApi
import whisk.core.database.ArtifactStoreProvider
import whisk.core.entity._
import whisk.core.entity.size._
import whisk.http.ErrorResponse
import whisk.http.Messages

/**
 * Tests Activations API.
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 *
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 * "using Specs2RouteTest DSL to chain HTTP requests for unit testing, as in ~>"
 */
@RunWith(classOf[JUnitRunner])
class ActivationsApiTests extends ControllerTestCommon with WhiskActivationsApi {

    /** Activations API tests */
    behavior of "Activations API"

    val creds = WhiskAuthHelpers.newIdentity()
    val namespace = EntityPath(creds.subject.asString)
    val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
    def aname = MakeName.next("activations_tests")

    //// GET /activations
    it should "get summary activation by namespace" in {
        implicit val tid = transid()
        // create two sets of activation records, and check that only one set is served back
        val creds1 = WhiskAuthHelpers.newAuth()
        (1 to 2).map { i =>
            WhiskActivation(EntityPath(creds1.subject.asString), aname, creds1.subject, ActivationId(), start = Instant.now, end = Instant.now)
        } foreach { put(entityStore, _) }

        val actionName = aname
        val activations = (1 to 2).map { i =>
            WhiskActivation(namespace, actionName, creds.subject, ActivationId(), start = Instant.now, end = Instant.now)
        }.toList
        activations foreach { put(activationStore, _) }
        waitOnView(activationStore, namespace, 2)
        whisk.utils.retry {
            Get(s"$collectionPath") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val rawResponse = responseAs[List[JsObject]]
                val response = responseAs[List[JsObject]]
                activations.length should be(response.length)
                activations forall { a => response contains a.summaryAsJson } should be(true)
                rawResponse forall { a => a.getFields("for") match { case Seq(JsString(n)) => n == actionName.asString case _ => false } }
            }
        }

        // it should "list activations with explicit namespace owned by subject" in {
        whisk.utils.retry {
            Get(s"/$namespace/${collection.path}") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val rawResponse = responseAs[List[JsObject]]
                val response = responseAs[List[JsObject]]
                activations.length should be(response.length)
                activations forall { a => response contains a.summaryAsJson } should be(true)
                rawResponse forall { a => a.getFields("for") match { case Seq(JsString(n)) => n == actionName.asString case _ => false } }
            }
        }

        // it should "reject list activations with explicit namespace not owned by subject" in {
        val auser = WhiskAuthHelpers.newIdentity()
        Get(s"/$namespace/${collection.path}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }
    }

    //// GET /activations?docs=true
    it should "get full activation by namespace" in {
        implicit val tid = transid()
        // create two sets of activation records, and check that only one set is served back
        val creds1 = WhiskAuthHelpers.newAuth()
        (1 to 2).map { i =>
            WhiskActivation(EntityPath(creds1.subject.asString), aname, creds1.subject, ActivationId(), start = Instant.now, end = Instant.now)
        } foreach { put(entityStore, _) }

        val actionName = aname
        val activations = (1 to 2).map { i =>
            WhiskActivation(namespace, actionName, creds.subject, ActivationId(), start = Instant.now, end = Instant.now, response = ActivationResponse.success(Some(JsNumber(5))))
        }.toList
        activations foreach { put(activationStore, _) }
        waitOnView(activationStore, namespace, 2)

        whisk.utils.retry {
            Get(s"$collectionPath?docs=true") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[List[JsObject]]
                activations.length should be(response.length)
                activations forall { a => response contains a.toExtendedJson } should be(true)
            }
        }
    }

    //// GET /activations?docs=true&since=xxx&upto=yyy
    it should "get full activation by namespace within a date range" in {
        implicit val tid = transid()
        // create two sets of activation records, and check that only one set is served back
        val creds1 = WhiskAuthHelpers.newAuth()
        (1 to 2).map { i =>
            WhiskActivation(EntityPath(creds1.subject.asString), aname, creds1.subject, ActivationId(), start = Instant.now, end = Instant.now)
        } foreach { put(activationStore, _) }

        val actionName = aname
        val now = Instant.now(Clock.systemUTC())
        val since = now.plusSeconds(10)
        val upto = now.plusSeconds(30)
        implicit val activations = Seq(
            WhiskActivation(namespace, actionName, creds.subject, ActivationId(), start = now.plusSeconds(9), end = now),
            WhiskActivation(namespace, actionName, creds.subject, ActivationId(), start = now.plusSeconds(20), end = now.plusSeconds(20)), // should match
            WhiskActivation(namespace, actionName, creds.subject, ActivationId(), start = now.plusSeconds(10), end = now.plusSeconds(20)), // should match
            WhiskActivation(namespace, actionName, creds.subject, ActivationId(), start = now.plusSeconds(31), end = now.plusSeconds(20)),
            WhiskActivation(namespace, actionName, creds.subject, ActivationId(), start = now.plusSeconds(30), end = now.plusSeconds(20))) // should match
        activations foreach { put(activationStore, _) }
        waitOnView(activationStore, namespace, activations.length)

        // get between two time stamps
        whisk.utils.retry {
            Get(s"$collectionPath?docs=true&since=${since.toEpochMilli}&upto=${upto.toEpochMilli}") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[List[JsObject]]
                val expected = activations filter {
                    case e => (e.start.equals(since) || e.start.equals(upto) || (e.start.isAfter(since) && e.start.isBefore(upto)))
                }
                expected.length should be(response.length)
                expected forall { a => response contains a.toExtendedJson } should be(true)
            }
        }

        // get 'upto' with no defined since value should return all activation 'upto'
        whisk.utils.retry {
            Get(s"$collectionPath?docs=true&upto=${upto.toEpochMilli}") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[List[JsObject]]
                val expected = activations filter {
                    case e => e.start.equals(upto) || e.start.isBefore(upto)
                }
                expected.length should be(response.length)
                expected forall { a => response contains a.toExtendedJson } should be(true)
            }
        }

        // get 'since' with no defined upto value should return all activation 'since'
        whisk.utils.retry {
            Get(s"$collectionPath?docs=true&since=${since.toEpochMilli}") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[List[JsObject]]
                val expected = activations filter {
                    case e => e.start.equals(since) || e.start.isAfter(since)
                }
                expected.length should be(response.length)
                expected forall { a => response contains a.toExtendedJson } should be(true)
            }
        }
    }

    //// GET /activations?name=xyz
    it should "get summary activation by namespace and action name" in {
        implicit val tid = transid()

        // create two sets of activation records, and check that only one set is served back
        val creds1 = WhiskAuthHelpers.newAuth()
        (1 to 2).map { i =>
            WhiskActivation(EntityPath(creds1.subject.asString), aname, creds1.subject, ActivationId(), start = Instant.now, end = Instant.now)
        } foreach { put(activationStore, _) }

        val activations = (1 to 2).map { i =>
            WhiskActivation(namespace, EntityName(s"xyz"), creds.subject, ActivationId(), start = Instant.now, end = Instant.now)
        }.toList
        activations foreach { put(activationStore, _) }
        waitOnView(activationStore, namespace, 2)

        whisk.utils.retry {
            Get(s"$collectionPath?name=xyz") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[List[JsObject]]
                activations.length should be(response.length)
                activations forall { a => response contains a.summaryAsJson } should be(true)
            }
        }
    }

    it should "reject activation list when limit is greater than maximum allowed value" in {
        implicit val tid = transid()
        val exceededMaxLimit = WhiskActivationsApi.maxActivationLimit + 1
        val response = Get(s"$collectionPath?limit=$exceededMaxLimit") ~> sealRoute(routes(creds)) ~> check {
            val response = responseAs[String]
            response should include(Messages.maxActivationLimitExceeded(exceededMaxLimit, WhiskActivationsApi.maxActivationLimit))
            status should be(BadRequest)
        }
    }

    it should "reject get activation by namespace and action name when action name is not a valid name" in {
        implicit val tid = transid()
        Get(s"$collectionPath?name=0%20") ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "reject get activation with invalid since/upto value" in {
        implicit val tid = transid()
        Get(s"$collectionPath?since=xxx") ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
        Get(s"$collectionPath?upto=yyy") ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    //// GET /activations/id
    it should "get activation by id" in {
        implicit val tid = transid()
        val activation = WhiskActivation(namespace, aname, creds.subject, ActivationId(), start = Instant.now, end = Instant.now)
        put(activationStore, activation)

        Get(s"$collectionPath/${activation.activationId.asString}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            response should be(activation.toExtendedJson)
        }

        // it should "get activation by name in explicit namespace owned by subject" in
        Get(s"/$namespace/${collection.path}/${activation.activationId.asString}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            response should be(activation.toExtendedJson)
        }

        // it should "reject get activation by name in explicit namespace not owned by subject" in
        val auser = WhiskAuthHelpers.newIdentity()
        Get(s"/$namespace/${collection.path}/${activation.activationId.asString}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }
    }

    //// GET /activations/id/result
    it should "get activation result by id" in {
        implicit val tid = transid()
        val activation = WhiskActivation(namespace, aname, creds.subject, ActivationId(), start = Instant.now, end = Instant.now)
        put(activationStore, activation)

        Get(s"$collectionPath/${activation.activationId.asString}/result") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            response should be(activation.response.toExtendedJson)
        }
    }

    //// GET /activations/id/logs
    it should "get activation logs by id" in {
        implicit val tid = transid()
        val activation = WhiskActivation(namespace, aname, creds.subject, ActivationId(), start = Instant.now, end = Instant.now)
        put(activationStore, activation)

        Get(s"$collectionPath/${activation.activationId.asString}/logs") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            response should be(activation.logs.toJsonObject)
        }
    }

    //// GET /activations/id/bogus
    it should "reject request to get invalid activation resource" in {
        implicit val tid = transid()
        val activation = WhiskActivation(namespace, aname, creds.subject, ActivationId(), start = Instant.now, end = Instant.now)
        put(entityStore, activation)

        Get(s"$collectionPath/${activation.activationId.asString}/bogus") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    it should "reject get requests with invalid activation ids" in {
        implicit val tid = transid()
        val activationId = ActivationId().toString
        val tooshort = activationId.substring(0, 31)
        val toolong = activationId + "xxx"
        val malformed = tooshort + "z"

        Get(s"$collectionPath/$tooshort") ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String] shouldBe Messages.activationIdLengthError(SizeError("Activation id", tooshort.length.B, 32.B))
        }

        Get(s"$collectionPath/$toolong") ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String] shouldBe Messages.activationIdLengthError(SizeError("Activation id", toolong.length.B, 32.B))
        }

        Get(s"$collectionPath/$malformed") ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "reject request with put" in {
        implicit val tid = transid()
        Put(s"$collectionPath/${ActivationId()}") ~> sealRoute(routes(creds)) ~> check {
            status should be(MethodNotAllowed)
        }
    }

    it should "reject request with post" in {
        implicit val tid = transid()
        Post(s"$collectionPath/${ActivationId()}") ~> sealRoute(routes(creds)) ~> check {
            status should be(MethodNotAllowed)
        }
    }

    it should "reject request with delete" in {
        implicit val tid = transid()
        Delete(s"$collectionPath/${ActivationId()}") ~> sealRoute(routes(creds)) ~> check {
            status should be(MethodNotAllowed)
        }
    }

    it should "report proper error when record is corrupted on get" in {
        val activationStore = ArtifactStoreProvider(system).makeStore[WhiskEntity](whiskConfig, _.dbActivations)(WhiskEntityJsonFormat, system, logging)
        implicit val tid = transid()
        val entity = BadEntity(namespace, EntityName(ActivationId().toString))
        put(activationStore, entity)

        Get(s"$collectionPath/${entity.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(InternalServerError)
            responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
        }
    }
}
