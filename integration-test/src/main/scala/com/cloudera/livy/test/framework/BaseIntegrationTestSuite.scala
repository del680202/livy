/*
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudera.livy.test.framework

import javax.servlet.http.HttpServletResponse

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.ning.http.client.AsyncHttpClient
import org.scalatest._

import com.cloudera.livy.server.interactive.CreateInteractiveRequest
import com.cloudera.livy.sessions.{SessionKindModule, Spark}

abstract class BaseIntegrationTestSuite extends FunSuite with Matchers {
  var cluster: Cluster = _
  var httpClient: AsyncHttpClient = _
  var livyClient: LivyClient = _

  protected def livyEndpoint: String = cluster.livyEndpoint

  test("initialize test cluster") {
    cluster = ClusterPool.get.lease()
    httpClient = new AsyncHttpClient()
    livyClient = new LivyClient(httpClient, livyEndpoint)
  }

  class LivyClient(httpClient: AsyncHttpClient, livyEndpoint: String) {

    private val mapper = new ObjectMapper()
      .registerModule(DefaultScalaModule)
      .registerModule(new SessionKindModule())

    def startInteractiveSession(): Int = {
      withClue(cluster.getLivyLog()) {
        val requestBody = new CreateInteractiveRequest()
        requestBody.kind = Spark()

        val rep = httpClient.preparePost(s"$livyEndpoint/sessions")
          .setBody(mapper.writeValueAsString(requestBody))
          .execute()
          .get()

        info("Interactive session submitted")

        val sessionId: Int = withClue(rep.getResponseBody) {
          rep.getStatusCode should equal(HttpServletResponse.SC_CREATED)
          val newSession = mapper.readValue(rep.getResponseBodyAsStream, classOf[Map[String, Any]])
          newSession should contain key ("id")

          newSession("id").asInstanceOf[Int]
        }
        info(s"Session id $sessionId")

        sessionId
      }
    }

    def getInteractivelStatus(sessionId: Int): String = {
      val rep = httpClient.prepareGet(s"$livyEndpoint/sessions/$sessionId").execute().get()
      withClue(rep.getResponseBody) {
        rep.getStatusCode should equal(HttpServletResponse.SC_OK)

        val sessionState =
          mapper.readValue(rep.getResponseBodyAsStream, classOf[Map[String, Any]])

        sessionState should contain key ("state")

        sessionState("state").asInstanceOf[String]
      }
    }

    def runStatementInSession(sessionId: Int, stmt: String): Int = {
      withClue(cluster.getLivyLog()) {
        val requestBody = "{ \"code\": \"" + stmt + "\" }"
        info(requestBody)
        val rep = httpClient.preparePost(s"$livyEndpoint/sessions/$sessionId/statements")
          .setBody(requestBody)
          .execute()
          .get()

        val stmtId: Int = withClue(rep.getResponseBody) {
          rep.getStatusCode should equal(HttpServletResponse.SC_CREATED)
          val newStmt = mapper.readValue(rep.getResponseBodyAsStream, classOf[Map[String, Any]])
          newStmt should contain key ("id")

          newStmt("id").asInstanceOf[Int]
        }
        stmtId
      }
    }

    def getStatementResult(sessionId: Int, stmtId: Int): String = {
      withClue(cluster.getLivyLog()) {
        val rep = httpClient.prepareGet(s"$livyEndpoint/sessions/$sessionId/statements/$stmtId")
          .execute()
          .get()

        val stmtResult = withClue(rep.getResponseBody) {
          rep.getStatusCode should equal(HttpServletResponse.SC_OK)
          val newStmt = mapper.readValue(rep.getResponseBodyAsStream, classOf[Map[String, Any]])
          newStmt should contain key ("output")
          val output = newStmt("output").asInstanceOf[Map[String, Any]]
          output should contain key ("data")
          val data = output("data").asInstanceOf[Map[String, Any]]
          data should contain key ("text/plain")
          data("text/plain").asInstanceOf[String]
        }

        stmtResult
      }
    }
  }
}