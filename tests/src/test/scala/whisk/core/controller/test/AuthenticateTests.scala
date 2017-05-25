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

import scala.concurrent.Await

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import spray.http.BasicHttpCredentials
import spray.http.StatusCodes._
import spray.routing.authentication.UserPass
import spray.routing.directives.AuthMagnet.fromContextAuthenticator
import whisk.common.TransactionCounter
import whisk.core.controller.Authenticate
import whisk.core.controller.AuthenticatedRoute
import whisk.core.entity._
import whisk.http.BasicHttpService
import whisk.core.entitlement.Privilege

/**
 * Tests authentication handler which guards API.
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
class AuthenticateTests extends ControllerTestCommon with Authenticate {
    behavior of "Authenticate"

    it should "authorize a known user using different namespaces and cache key, and reject invalid secret" in {
        implicit val tid = transid()
        val subject = Subject()

        val namespaces = Set(
            WhiskNamespace(MakeName.next("authenticatev_tests"), AuthKey()),
            WhiskNamespace(MakeName.next("authenticatev_tests"), AuthKey()))
        val entry = WhiskAuth(subject, namespaces)
        put(authStore, entry) // this test entry is reclaimed when the test completes

        // Try to login with each specific namespace
        namespaces.foreach { ns =>
            println(s"Trying to login to $ns")
            val pass = UserPass(ns.authkey.uuid.asString, ns.authkey.key.asString)
            val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
            user.get shouldBe Identity(subject, ns.name, ns.authkey, Privilege.ALL)

            // first lookup should have been from datastore
            stream.toString should include regex (s"serving from datastore: ${ns.authkey.uuid.asString}")
            stream.reset()

            // repeat query, now should be served from cache
            val cachedUser = Await.result(validateCredentials(Some(pass))(transid()), dbOpTimeout)
            cachedUser.get shouldBe Identity(subject, ns.name, ns.authkey, Privilege.ALL)

            stream.toString should include regex (s"serving from cache: ${ns.authkey.uuid.asString}")
            stream.reset()
        }

        // check that invalid keys are rejected
        val ns = namespaces.head
        val key = ns.authkey.key.asString
        Seq(key.drop(1), key.dropRight(1), key + "x", AuthKey().key.asString).foreach { k =>
            val pass = UserPass(ns.authkey.uuid.asString, k)
            val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
            user shouldBe empty
        }
    }

    it should "not log key during validation" in {
        implicit val tid = transid()
        val creds = WhiskAuthHelpers.newIdentity()
        val pass = UserPass(creds.authkey.uuid.asString, creds.authkey.key.asString)
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
        stream.toString should not include creds.authkey.key.asString
    }

    it should "not authorize an unknown user" in {
        implicit val tid = transid()
        val creds = WhiskAuthHelpers.newIdentity()
        val pass = UserPass(creds.authkey.uuid.asString, creds.authkey.key.asString)
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize when no user creds are provided" in {
        implicit val tid = transid()
        val user = Await.result(validateCredentials(None), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize when malformed user is provided" in {
        implicit val tid = transid()
        val pass = UserPass("x", Secret().asString)
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize when malformed secret is provided" in {
        implicit val tid = transid()
        val pass = UserPass(UUID().asString, "x")
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize when malformed creds are provided" in {
        implicit val tid = transid()
        val pass = UserPass("x", "y")
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }
}

class AuthenticatedRouteTests
    extends ControllerTestCommon
    with Authenticate
    with AuthenticatedRoute
    with TransactionCounter {

    behavior of "Authenticated Route"

    val route = sealRoute {
        implicit val tid = transid()
        handleRejections(BasicHttpService.customRejectionHandler) {
            path("secured") {
                authenticate(basicauth) {
                    user => complete("ok")
                }
            }

        }
    }

    it should "authorize a known user" in {
        implicit val tid = transid()
        val entry = WhiskAuthHelpers.newAuth()
        put(authStore, entry) // this test entry is reclaimed when the test completes

        val validCredentials = BasicHttpCredentials(entry.namespaces.head.authkey.uuid.asString, entry.namespaces.head.authkey.key.asString)
        Get("/secured") ~> addCredentials(validCredentials) ~> route ~> check {
            status should be(OK)
        }
    }

    it should "not authorize an unknown user" in {
        val invalidCredentials = BasicHttpCredentials(UUID().asString, Secret().asString)
        Get("/secured") ~> addCredentials(invalidCredentials) ~> route ~> check {
            status should be(Unauthorized)
        }
    }
}
