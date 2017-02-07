/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.cli.test

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import common.TestHelpers
import common.TestUtils._
import common.TestUtils
import common.Wsk
import common.WskAdmin
import common.WskProps
import common.WskTestHelpers

/**
 * Tests for testing the CLI "api" subcommand.  Most of these tests require a deployed backend.
 */
@RunWith(classOf[JUnitRunner])
class ApiGwTests
    extends TestHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val (cliuser, clinamespace) = WskAdmin.getUser(wskprops.authKey)

    behavior of "Wsk api"

    it should "reject an api commands with an invalid path parameter" in {
        val badpath = "badpath"

        var rr = wsk.api.create(basepath = Some("/basepath"), relpath = Some(badpath), operation = Some("GET"), action = Some("action"), expectedExitCode = ANY_ERROR_EXIT)
        rr.stderr should include (s"'${badpath}' must begin with '/'")

        rr = wsk.api.delete(basepathOrApiName = "/basepath", relpath = Some(badpath), operation = Some("GET"), expectedExitCode = ANY_ERROR_EXIT)
        rr.stderr should include (s"'${badpath}' must begin with '/'")

        rr = wsk.api.list(basepathOrApiName = Some("/basepath"), relpath = Some(badpath), operation = Some("GET"), expectedExitCode = ANY_ERROR_EXIT)
        rr.stderr should include (s"'${badpath}' must begin with '/'")
    }

    it should "verify full list output" in {
      val testName = "CLI_APIGWTEST_RO1"
      val testbasepath = "/" + testName + "_bp"
      val testrelpath = "/path"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName + " API Name"
      val actionName = testName + "_action"
      try {
        println("cli user: " + cliuser + "; cli namespace: " + clinamespace)

        var rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        println("api create: " + rr.stdout)
        rr.stdout should include("ok: created API")
        rr = wsk.api.list(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), full = Some(true))
        println("api list: " + rr.stdout)
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"Action:\\s+/${clinamespace}/${actionName}\n")
        rr.stdout should include regex (s"Verb:\\s+${testurlop}\n")
        rr.stdout should include regex (s"Base path:\\s+${testbasepath}\n")
        rr.stdout should include regex (s"Path:\\s+${testrelpath}\n")
        rr.stdout should include regex (s"API Name:\\s+${testapiname}\n")
        rr.stdout should include regex (s"URL:\\s+")
        rr.stdout should include(testbasepath + testrelpath)
      }
      finally {
        val deleteresult = wsk.api.delete(basepathOrApiName = testbasepath)
        Thread.sleep(5000)  // Test suite will overrun the local dev throttling limit
      }
    }

    it should "verify successful creation and deletion of a new API" in {
        val testName = "CLI_APIGWTEST1"
        val testbasepath = "/"+testName+"_bp"
        val testrelpath = "/path"
        val testnewrelpath = "/path_new"
        val testurlop = "get"
        val testapiname = testName+" API Name"
        val actionName = testName+"_action"
        try {
            println("cli user: "+cliuser+"; cli namespace: "+clinamespace)

            var rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
            rr.stdout should include("ok: created API")
            rr = wsk.api.list(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
            rr.stdout should include("ok: APIs")
            rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
            rr.stdout should include(testbasepath + testrelpath)
            val deleteresult = wsk.api.delete(basepathOrApiName = testbasepath)
            deleteresult.stdout should include("ok: deleted API")
        }
        finally {
            val deleteresult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
            Thread.sleep(5000)  // Test suite will overrun the local dev throttling limit
        }
    }

    it should "verify get API name " in {
        val testName = "CLI_APIGWTEST3"
        val testbasepath = "/"+testName+"_bp"
        val testrelpath = "/path"
        val testnewrelpath = "/path_new"
        val testurlop = "get"
        val testapiname = testName+" API Name"
        val actionName = testName+"_action"
        try {
            var rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
            rr.stdout should include("ok: created API")
            rr = wsk.api.get(basepathOrApiName = Some(testapiname))
            rr.stdout should include(testbasepath)
            rr.stdout should include(s"${actionName}")
        }
        finally {
            val deleteresult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
            Thread.sleep(5000)  // Test suite will overrun the local dev throttling limit
        }
    }

    it should "verify delete API name " in {
      val testName = "CLI_APIGWTEST4"
      val testbasepath = "/"+testName+"_bp"
      val testrelpath = "/path"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName+" API Name"
      val actionName = testName+"_action"
      try {
        var rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = wsk.api.delete(basepathOrApiName = testapiname)
        rr.stdout should include("ok: deleted API")
      }
      finally {
        val deleteresult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        Thread.sleep(5000)  // Test suite will overrun the local dev throttling limit
      }
    }

    it should "verify delete API basepath " in {
      val testName = "CLI_APIGWTEST5"
      val testbasepath = "/"+testName+"_bp"
      val testrelpath = "/path"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName+" API Name"
      val actionName = testName+"_action"
      try {
        var rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = wsk.api.delete(basepathOrApiName = testbasepath)
        rr.stdout should include("ok: deleted API")
      }
      finally {
        val deleteresult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        Thread.sleep(5000)  // Test suite will overrun the local dev throttling limit
      }
    }

    it should "verify adding endpoints to existing api" in {
      val testName = "CLI_APIGWTEST6"
      val testbasepath = "/"+testName+"_bp"
      val testrelpath = "/path2"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName+" API Name"
      val actionName = testName+"_action"
      val newEndpoint = "/newEndpoint"
      try {
        var rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(newEndpoint), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = wsk.api.list(basepathOrApiName = Some(testbasepath))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
        rr.stdout should include(testbasepath + newEndpoint)
      }
      finally {
        val deleteresult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        Thread.sleep(5000)  // Test suite will overrun the local dev throttling limit
      }
    }

    it should "verify successful creation with swagger doc as input" in {
      // NOTE: These values must match the swagger file contents
      val testName = "CLI_APIGWTEST7"
      val testbasepath = "/"+testName+"_bp"
      val testrelpath = "/path"
      val testurlop = "get"
      val testapiname = testName+" API Name"
      val actionName = testName+"_action"
      val swaggerPath = TestUtils.getTestApiGwFilename("testswaggerdoc1")
      try {
        var rr = wsk.api.create(swagger = Some(swaggerPath))
        rr.stdout should include("ok: created API")
        rr = wsk.api.list(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
        println("list stdout: "+rr.stdout)
        println("list stderr: "+rr.stderr)
        rr.stdout should include("ok: APIs")
        // Actual CLI namespace will vary from local dev to automated test environments, so don't check
        rr.stdout should include regex (s"/[@\\w._\\-]+/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
      }
      finally {
        val deleteresult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        Thread.sleep(5000)  // Test suite will overrun the local dev throttling limit
      }
    }

    it should "verify adding endpoints to two existing apis" in {
      val testName = "CLI_APIGWTEST8"
      val testbasepath = "/"+testName+"_bp"
      val testbasepath2 = "/"+testName+"_bp2"
      val testrelpath = "/path2"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName+" API Name"
      val actionName = testName+"_action"
      val newEndpoint = "/newEndpoint"
      try {
        var rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = wsk.api.create(basepath = Some(testbasepath2), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")

        // Update both APIs - each with a new endpoint
        rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(newEndpoint), operation = Some(testurlop), action = Some(actionName))
        rr.stdout should include("ok: created API")
        rr = wsk.api.create(basepath = Some(testbasepath2), relpath = Some(newEndpoint), operation = Some(testurlop), action = Some(actionName))
        rr.stdout should include("ok: created API")

        rr = wsk.api.list(basepathOrApiName = Some(testbasepath))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
        rr.stdout should include(testbasepath + newEndpoint)

        rr = wsk.api.list(basepathOrApiName = Some(testbasepath2))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath2 + testrelpath)
        rr.stdout should include(testbasepath2 + newEndpoint)
      }
      finally {
        var deleteresult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        deleteresult = wsk.api.delete(basepathOrApiName = testbasepath2, expectedExitCode = DONTCARE_EXIT)
        Thread.sleep(5000)  // Test suite will overrun the local dev throttling limit
      }
    }

    it should "verify successful creation of a new API using an action name using all allowed characters" in {
      // Be aware: full action name is close to being truncated by the 'list' command
      // e.g. /lime@us.ibm.com/CLI_APIGWTEST9a-c@t ion  is currently at the 40 char 'list' display max
      val testName = "CLI_APIGWTEST9"
      val testbasepath = "/" + testName + "_bp"
      val testrelpath = "/path"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName+" API Name"
      val actionName = testName+"a-c@t ion"
      try {
        println("cli user: "+cliuser+"; cli namespace: "+clinamespace)

        var rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = wsk.api.list(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
        val deleteresult = wsk.api.delete(basepathOrApiName = testbasepath)
        deleteresult.stdout should include("ok: deleted API")
      }
      finally {
        val deleteresult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        Thread.sleep(5000)  // Test suite will overrun the local dev throttling limit
      }
    }

    it should "verify failed creation with invalid swagger doc as input" in {
      val testName = "CLI_APIGWTEST10"
      val testbasepath = "/" + testName + "_bp"
      val testrelpath = "/path"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName + " API Name"
      val actionName = testName + "_action"
      val swaggerPath = TestUtils.getTestApiGwFilename(s"testswaggerdocinvalid")
      try {
        var rr = wsk.api.create(swagger = Some(swaggerPath), expectedExitCode = ANY_ERROR_EXIT)
        println("api create stdout: " + rr.stdout)
        println("api create stderr: " + rr.stderr)
        rr.stderr should include(s"Swagger file is invalid")
      } finally {
        val deleteresult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        Thread.sleep(5000)  // Test suite will overrun the local dev throttling limit
      }
    }

    it should "verify delete basepath/path " in {
      val testName = "CLI_APIGWTEST11"
      val testbasepath = "/" + testName + "_bp"
      val testrelpath = "/path"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName + " API Name"
      val actionName = testName + "_action"
      try {
        var rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        var rr2 = wsk.api.create(basepath = Some(testbasepath), relpath = Some(testnewrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr2.stdout should include("ok: created API")
        rr = wsk.api.delete(basepathOrApiName = testbasepath, relpath = Some(testrelpath))
        rr.stdout should include("ok: deleted " + testrelpath +" from "+ testbasepath)
        rr2 = wsk.api.list(basepathOrApiName = Some(testbasepath), relpath = Some(testnewrelpath))
        rr2.stdout should include("ok: APIs")
        rr2.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr2.stdout should include(testbasepath + testnewrelpath)
      } finally {
        val deleteresult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        Thread.sleep(5000)  // Test suite will overrun the local dev throttling limit
      }
    }

    it should "verify delete single operation from existing API basepath/path/operation(s) " in {
      val testName = "CLI_APIGWTEST12"
      val testbasepath = "/" + testName + "_bp"
      val testrelpath = "/path2"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testurlop2 = "post"
      val testapiname = testName + " API Name"
      val actionName = testName + "_action"
      try {
        var rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop2), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = wsk.api.list(basepathOrApiName = Some(testbasepath))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
        rr = wsk.api.delete(basepathOrApiName = testbasepath,relpath = Some(testrelpath), operation = Some(testurlop2))
        rr.stdout should include("ok: deleted " + testrelpath + " " + "POST" +" from "+ testbasepath)
        rr = wsk.api.list(basepathOrApiName = Some(testbasepath))
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
      } finally {
        val deleteresult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        Thread.sleep(5000)  // Test suite will overrun the local dev throttling limit
      }
    }

    it should "verify successful creation with complex swagger doc as input" in {
      val testName = "CLI_APIGWTEST13"
      val testbasepath = "/test1/v1"
      val testrelpath = "/whisk.system/utils/echo"
      val testrelpath2 = "/whisk.system/utils/split"
      val testurlop = "get"
      val testapiname = "/test1/v1"
      val actionName = "test1a"
      val swaggerPath = TestUtils.getTestApiGwFilename(s"testswaggerdoc2")
      try {
        var rr = wsk.api.create(swagger = Some(swaggerPath))
        println("api create stdout: " + rr.stdout)
        println("api create stderror: " + rr.stderr)
        rr.stdout should include("ok: created API")
        rr = wsk.api.list(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
        rr.stdout should include("ok: APIs")
        // Actual CLI namespace will vary from local dev to automated test environments, so don't check
        rr.stdout should include regex (s"/[@\\w._\\-]+/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
        rr.stdout should include(testbasepath + testrelpath2)
      } finally {
        val deleteresult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        Thread.sleep(5000)  // Test suite will overrun the local dev throttling limit
      }
    }

    it should "verify successful creation and deletion with multiple base paths" in {
      val testName = "CLI_APIGWTEST14"
      val testbasepath = "/" + testName + "_bp"
      val testbasepath2 = "/" + testName + "_bp2"
      val testrelpath = "/path"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName + " API Name"
      val actionName = testName + "_action"
      try {
        var rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = wsk.api.list(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
        rr = wsk.api.create(basepath = Some(testbasepath2), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = wsk.api.list(basepathOrApiName = Some(testbasepath2), relpath = Some(testrelpath), operation = Some(testurlop))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath2 + testrelpath)
        rr = wsk.api.delete(basepathOrApiName = testbasepath2)
        rr.stdout should include("ok: deleted API")
        rr = wsk.api.list(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
        rr = wsk.api.delete(basepathOrApiName = testbasepath)
        rr.stdout should include("ok: deleted API")
      } finally {
        var deleteresult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        deleteresult = wsk.api.delete(basepathOrApiName = testbasepath2, expectedExitCode = DONTCARE_EXIT)
        Thread.sleep(5000)  // Test suite will overrun the local dev throttling limit
      }
    }
}
