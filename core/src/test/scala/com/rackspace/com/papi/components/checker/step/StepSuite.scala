/***
 *   Copyright 2014 Rackspace US, Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.rackspace.com.papi.components.checker.step

import javax.servlet.FilterChain
import javax.xml.namespace.QName

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.github.fge.jsonschema.exceptions.ProcessingException
import com.rackspace.com.papi.components.checker.LogAssertions
import com.rackspace.com.papi.components.checker.Validator
import com.rackspace.com.papi.components.checker.handler.ResultHandler
import com.rackspace.com.papi.components.checker.servlet.{CheckerServletRequest, CheckerServletResponse, ParsedXML,
                                                          ParsedJSON, ParsedNIL}
import com.rackspace.com.papi.components.checker.step.base.{ConnectedStep, Step, StepContext, RepresentationException}
import com.rackspace.com.papi.components.checker.step.results._
import com.rackspace.com.papi.components.checker.step.startend._
import com.rackspace.com.papi.components.checker.util.{HeaderMap, ImmutableNamespaceContext, ObjectMapperPool, XMLParserPool}
import org.junit.runner.RunWith
import org.mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import org.w3c.dom.Document
import org.xml.sax.SAXParseException

import org.apache.logging.log4j.Level

import scala.collection.JavaConversions._
import scala.language.implicitConversions
import scala.xml._

import java.io.IOException
import java.io.InputStreamReader
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Paths

@RunWith(classOf[JUnitRunner])
class StepSuite extends BaseStepSuite with MockitoSugar
                                      with LogAssertions {
  class GenericStep(id: String, label: String, next: Array[Step], extraHeaders: Option[HeaderMap] = None) extends ConnectedStep(id, label, next) {
    override def checkStep(req : CheckerServletRequest, resp : CheckerServletResponse, chain : FilterChain, context : StepContext) : Option[StepContext] =
      Some(extraHeaders.map({headers => context.copy(requestHeaders = context.requestHeaders.addHeaders(headers))}).getOrElse(context))
  }

  test("steps should call the handlers before continuing to the next step") {
    val handler = Mockito.mock(classOf[ResultHandler])
    val stepContext = new StepContext(handler = Some(handler), uriLevel = 100)
    val step = new GenericStep("newGenericStep", "newGenericLabel", Array(new Method("GET", "Foo", "GET".r, Array(new Accept("Accept", "Test Accept", 1)))))
    val request: CheckerServletRequest = Mockito.mock(classOf[CheckerServletRequest])
    Mockito.when(request.getMethod).thenReturn("GET")
    Mockito.when(request.URISegment).thenReturn(Array[String]())
    val response: CheckerServletResponse = Mockito.mock(classOf[CheckerServletResponse])

    val headerMap = new HeaderMap().addHeaders("foo", List("bar", "baz"))
    Mockito.when(handler.inStep(Matchers.any(), Matchers.any(), Matchers.any(), Matchers.any())).thenAnswer(new Answer[StepContext](){
      override def answer(invocationOnMock: InvocationOnMock): StepContext = {
        val arguments: Array[AnyRef] = invocationOnMock.getArguments
        if(arguments(0) == step && arguments(1) == request && arguments(2) == response && arguments(3) == stepContext) {
          stepContext.copy(requestHeaders = stepContext.requestHeaders.addHeaders(headerMap))
        } else {
          arguments(3).asInstanceOf[StepContext]
        }
      }
    })
    val result = step.check(request, response, chain, stepContext)
    assert(result.get.stepIDs == List("newGenericStep", "GET", "Accept"))
    Mockito.verify(request).addHeaders(Matchers.eq(headerMap))
  }

  test("steps should pass through the new context with no handlers set") {
    val stepContext = new StepContext(handler = None)
    val headerMap = new HeaderMap().addHeaders("foo", List("bar", "baz"))
    val step = new GenericStep("newGenericStep", "newGenericLabel", Array(new Accept("Accept", "Test Accept", 1)), Some(headerMap))
    val request: CheckerServletRequest = Mockito.mock(classOf[CheckerServletRequest])

    val response: CheckerServletResponse = Mockito.mock(classOf[CheckerServletResponse])
    val result = step.check(request, response, chain, stepContext)
    result.get.stepIDs == List("newGenericStep")
    Mockito.verify(request).addHeaders(Matchers.eq(headerMap))
  }

  test("handlers should pass through the context if there isnt an inStep method defined") {
    class GenericHandler extends ResultHandler {
      def init(validator : Validator, checker : Option[Document]) : Unit = {}
      def handle (req : CheckerServletRequest, resp : CheckerServletResponse, chain : FilterChain, result : Result) : Unit = {}
    }

    val stepContext = new StepContext(handler = Some(new GenericHandler()))
    val headerMap = new HeaderMap().addHeaders("foo", List("bar", "baz"))
    val step = new GenericStep("newGenericStep", "newGenericLabel", Array(new Accept("Accept", "Test Accept", 1)), Some(headerMap))
    val request: CheckerServletRequest = Mockito.mock(classOf[CheckerServletRequest])

    val response: CheckerServletResponse = Mockito.mock(classOf[CheckerServletResponse])
    val result = step.check(request, response, chain, stepContext)
    result.get.stepIDs == List("newGenericStep")
    Mockito.verify(request).addHeaders(Matchers.eq(headerMap))
  }

  test("Regardless of input, Accept step should always return AcceptResult") {
    val accept = new Accept("a","a", 10)
    val res2 = accept.check(request("GET", "/a/b"), response,chain, 2)
    assert(res2.isDefined)
    assert(res2.get.isInstanceOf[AcceptResult])
    val res3 = accept.check(request("XGET", "/a/b"), response,chain, 0)
    assert(res3.isDefined)
    assert(res3.get.isInstanceOf[AcceptResult])
    val res = accept.check (request("DELETE", "/a/a/a/a/a/a"), response, chain, -1)
    assert(res.isDefined)
    assert(res.get.isInstanceOf[AcceptResult])
  }

  test ("Accept step should set step context request headers") {
    val accept = new Accept("a", "a", 10)
    val req1 = request("GET", "/a/b", "", "", false, Map().asInstanceOf[Map[String,List[String]]])
    val req2 = request("GET", "/b/c", "", "", false, Map().asInstanceOf[Map[String,List[String]]])
    val req3 = request("GET", "/c/d", "", "", false, Map().asInstanceOf[Map[String,List[String]]])
    val req4 = request("GET", "/e/e", "", "", false, Map().asInstanceOf[Map[String,List[String]]])

    accept.check (req1, response, chain, StepContext(1, (new HeaderMap()).addHeader("Foo", "Bar")))
    accept.check (req2, response, chain, StepContext(2, (new HeaderMap()).addHeaders("Foo", List("Bar", "Baz", "Biz"))))
    accept.check (req3, response, chain, StepContext(3, (new HeaderMap()).addHeader("Foo", "Baz").addHeaders("Bar",List("Fe","Fi","Fo")))) 
    accept.check (req4, response, chain, StepContext(1, (new HeaderMap()).addHeader("Foo", "Bar,Baz,Biz")))

    assert (req1.getHeaders("Foo").toList == List("Bar"))
    assert (req2.getHeaders("Foo").toList == List("Bar", "Baz", "Biz"))
    assert (req3.getHeaders("Foo").toList == List("Baz"))
    assert (req3.getHeaders("Bar").toList == List("Fe", "Fi", "Fo"))
    assert (req4.getHeaders("Foo").toList == List("Bar,Baz,Biz"))
  }

  test("Start should not change URI level") {
    val start = new Start("s", "s", Array[Step]())
    assert(start.checkStep(null, null, null, -1) == -1)
    assert(start.checkStep(request("GET", "/a/b"), response,chain, 2) == 2)
    assert(start.checkStep(request("",""), response,chain, 1000) == 1000)
  }

  test("MethodFail should return method fail result if the URI level has been exceeded") {
    val mf  = new MethodFail("mf", "mf", 10)
    val res = mf.check (request("GET", "/a/b"), response,chain, 2)
    assert (res.isDefined)
    assert (res.get.isInstanceOf[MethodFailResult])
    val res2 = mf.check (request("GET", "/a/b"), response,chain, 3)
    assert (res2.isDefined)
    assert (res2.get.isInstanceOf[MethodFailResult])
  }

  test("MethodFail should return method fail result with empty allow header  if the URI level has been exceeded") {
    val mf  = new MethodFail("mf", "mf", 10)
    val res = mf.check (request("GET", "/a/b"), response,chain, 2)
    assert (res.isDefined)
    val headers = res.get.asInstanceOf[MethodFailResult].headers
    assert (headers.containsKey("Allow"))
    assert (headers.get("Allow").equals(""))
    val res2 = mf.check (request("GET", "/a/b"), response,chain, 3)
    assert (res2.isDefined)
    val headers2 = res.get.asInstanceOf[MethodFailResult].headers
    assert (headers2.containsKey("Allow"))
    assert (headers2.get("Allow").equals(""))
  }

  test("MethodFail should return None when URI level is not exceeded") {
    val mf  = new MethodFail("mf", "mf", 10)
    val res = mf.check (request("GET", "/a/b"), response,chain, 0)
    assert (res.isEmpty)
    val res2 = mf.check (request("GET", "/a/b"), response,chain, 1)
    assert (res2.isEmpty)
  }

  test("MethodFailMatch should return method fail result if the URI level has been exceeded and the method regex does not match") {
    val mf  = new MethodFailMatch("mf", "mf", "POST".r, 10)
    val res = mf.check (request("GET", "/a/b"), response,chain, 2)
    assert (res.isDefined)
    assert (res.get.isInstanceOf[MethodFailResult])
    val res2 = mf.check (request("GET", "/a/b"), response,chain, 3)
    assert (res2.isDefined)
    assert (res2.get.isInstanceOf[MethodFailResult])
  }

  test("MethodFailMatch should return method fail result with appropriate Allow header if the URI level has been exceeded and the method regex does not match") {
    val mf  = new MethodFailMatch("mf", "mf", "POST".r, 10)
    val res = mf.check (request("GET", "/a/b"), response,chain, 2)
    assert (res.isDefined)
    val headers = res.get.asInstanceOf[MethodFailResult].headers
    assert (headers.containsKey("Allow"))
    assert (headers.get("Allow").equals("POST"))
    val res2 = mf.check (request("GET", "/a/b"), response,chain, 3)
    assert (res2.isDefined)
    val headers2 = res.get.asInstanceOf[MethodFailResult].headers
    assert (headers2.containsKey("Allow"))
    assert (headers2.get("Allow").equals("POST"))
  }

  test("MethodFailMatch should return method fail result with appropriate Allow header if the URI level has been exceeded and the method regex does not match (multiple method match)") {
    val mf  = new MethodFailMatch("mf", "mf", "POST|PUT".r, 10)
    val res = mf.check (request("GET", "/a/b"), response,chain, 2)
    assert (res.isDefined)
    val headers = res.get.asInstanceOf[MethodFailResult].headers
    assert (headers.containsKey("Allow"))
    assert (headers.get("Allow").equals("POST, PUT"))
    val res2 = mf.check (request("GET", "/a/b"), response,chain, 3)
    assert (res2.isDefined)
    val headers2 = res.get.asInstanceOf[MethodFailResult].headers
    assert (headers2.containsKey("Allow"))
    assert (headers2.get("Allow").equals("POST, PUT"))
  }

  test("MethodFailMatch should return None if the URI level has been exceeded and the method regex matches") {
    val mf  = new MethodFailMatch("mf", "mf", "GET".r, 10)
    val res = mf.check (request("GET", "/a/b"), response,chain, 2)
    assert (res.isEmpty)
    val res2 = mf.check (request("GET", "/a/b"), response,chain, 3)
    assert (res2.isEmpty)
  }

  test("MethodFailMatch should return None when URI level is not exceeded") {
    val mf  = new MethodFailMatch("mf", "mf", "GET".r, 10)
    val res = mf.check (request("GET", "/a/b"), response,chain, 0)
    assert (res.isEmpty)
    val res2 = mf.check (request("GET", "/a/b"), response,chain, 1)
    assert (res2.isEmpty)
  }

  test("URLFail should return URL fail result if URI level has not been exceeded") {
    val uf = new URLFail("uf", "uf", 10)
    val res = uf.check (request("GET", "/a/b"), response,chain, 0)
    assert (res.isDefined)
    assert (res.get.isInstanceOf[URLFailResult])
    val res2 = uf.check (request("GET", "/a/b"), response,chain, 1)
    assert (res2.isDefined)
    assert (res2.get.isInstanceOf[URLFailResult])
  }


  test("URLFailXSD should return None if URI level has been exceeded : StepType, uuid, evenIntType") {
    val ufx = new URLFailXSD("ufx", "ufx", Array[QName](stepType, uuidType, evenIntType), testSchema, 10)
    val res = ufx.check (request("GET", "/START/b"), response,chain, 2)
    assert (res.isEmpty)
    val res2 = ufx.check (request("GET", "/ACCEPT/b"), response,chain, 3)
    assert (res2.isEmpty)
  }

  test("URLFailXSD should return URL fail result if URI level has not been exceeded and the uri type does not match : StepType, uuid, evenIntType") {
    val ufx = new URLFailXSD ("ufmx", "ufmx", Array[QName](stepType, uuidType, evenIntType), testSchema, 10)
    val res = ufx.check (request("GET", "/a/b"), response,chain, 0)
    assert (res.isDefined)
    assert (res.get.isInstanceOf[URLFailResult])
    val res2 = ufx.check (request("GET", "/a/b"), response,chain, 1)
    assert (res2.isDefined)
    assert (res2.get.isInstanceOf[URLFailResult])
  }


  test("URLFailXSD should return URL None if URI level has not been exceeded and the uri type matches : StepType, uuid, evenIntType") {
    val ufx = new URLFailXSD ("ufmx", "ufmx", Array[QName](stepType, uuidType, evenIntType), testSchema, 10)
    val res = ufx.check (request("GET", "/START/b"), response,chain, 0)
    assert (res.isEmpty)
    val res2 = ufx.check (request("GET", "/eb507026-6463-11e1-b7aa-8b7b918a1623/b"), response,chain, 0)
    assert (res2.isEmpty)
    val res3 = ufx.check (request("GET", "/90/b"), response,chain, 0)
    assert (res3.isEmpty)
  }

  test("URLFailXSDMatch should return None if URI level has been exceeded : StepType, uuid, evenIntType") {
    val ufx = new URLFailXSDMatch("ufx", "ufx", "c".r, Array[QName](stepType, uuidType, evenIntType), testSchema, 10)
    val res = ufx.check (request("GET", "/START/b"), response,chain, 2)
    assert (res.isEmpty)
    val res2 = ufx.check (request("GET", "/ACCEPT/b"), response,chain, 3)
    assert (res2.isEmpty)
    val res3 = ufx.check (request("GET", "/c/b"), response,chain, 4)
    assert (res3.isEmpty)
  }

  test("URLFailXSDMatch should return URL fail result if URI level has not been exceeded and the uri type does not match : StepType, uuid, evenIntType") {
    val ufx = new URLFailXSDMatch ("ufmx", "ufmx", "c".r, Array[QName](stepType, uuidType, evenIntType), testSchema, 10)
    val res = ufx.check (request("GET", "/a/b"), response,chain, 0)
    assert (res.isDefined)
    assert (res.get.isInstanceOf[URLFailResult])
    val res2 = ufx.check (request("GET", "/a/b"), response,chain, 1)
    assert (res2.isDefined)
    assert (res2.get.isInstanceOf[URLFailResult])
  }

  test("URLFailXSDMatch should return URL None if URI level has not been exceeded and the uri type matches : StepType, uuid, evenIntType") {
    val ufx = new URLFailXSDMatch ("ufmx", "ufmx", "c".r, Array[QName](stepType, uuidType, evenIntType), testSchema, 10)
    val res = ufx.check (request("GET", "/START/b"), response,chain, 0)
    assert (res.isEmpty)
    val res2 = ufx.check (request("GET", "/eb507026-6463-11e1-b7aa-8b7b918a1623/b"), response,chain, 0)
    assert (res2.isEmpty)
    val res3 = ufx.check (request("GET", "/90/b"), response,chain, 0)
    assert (res3.isEmpty)
    val res4 = ufx.check (request("GET", "/c/b"), response,chain, 0)
    assert (res4.isEmpty)
  }

  test("URLFail should return None if URI level has been exceeded") {
    val uf = new URLFail("uf", "uf", 10)
    val res = uf.check (request("GET", "/a/b"), response,chain, 2)
    assert (res.isEmpty)
    val res2 = uf.check (request("GET", "/a/b"), response,chain, 3)
    assert (res2.isEmpty)
  }

  test("URLFailMatch should return URL fail result if URI level has not been exceeded and the uri regex does not match") {
    val uf = new URLFailMatch ("ufm", "ufm", "c".r, 10)
    val res = uf.check (request("GET", "/a/b"), response,chain, 0)
    assert (res.isDefined)
    assert (res.get.isInstanceOf[URLFailResult])
    val res2 = uf.check (request("GET", "/a/b"), response,chain, 1)
    assert (res2.isDefined)
    assert (res2.get.isInstanceOf[URLFailResult])
  }

  test("URLFailMatch should return None if URI level has not been exceeded and the uri regex matches") {
    val uf = new URLFailMatch ("ufm", "ufm", "a".r, 10)
    val res = uf.check (request("GET", "/a/b"), response,chain, 0)
    assert (res.isEmpty)
  }

  test("URLFailMatch should return None if URI level has been exceeded") {
    val uf = new URLFailMatch("uf", "uf", "a".r, 10)
    val res = uf.check (request("GET", "/a/b"), response,chain, 2)
    assert (res.isEmpty)
    val res2 = uf.check (request("GET", "/a/b"), response,chain, 3)
    assert (res2.isEmpty)
  }

  test("URIXSD mismatch message should be the same as the QName") {
    val urixsd = new URIXSD("uxd", "uxd", stepType, testSchema, Array[Step]())
    assert (urixsd.mismatchMessage == stepType.toString)
  }

  test("In a URIXSD step, if there is a URI match, the step should proceed to the next step : StepType") {
    val urixsd = new URIXSD("uxd", "uxd", stepType, testSchema, Array[Step]())
    assert (urixsd.check (request("GET", "/START/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/URL_FAIL/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/METHOD_FAIL/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/ACCEPT/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/URL/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/METHOD/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/URLXSD/b"), response,chain, 0).isEmpty)
  }

  test("In a URIXSD step, if there is a mismatch, a MismatchResult should be returned: StepType") {
    val urixsd = new URIXSD("uxd", "uxd", stepType, testSchema, Array[Step]())
    assertMismatchResult (urixsd.check (request("GET", "/ATART/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/URL_FAI2/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/METHO4_FAIL/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/ACCCPT/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/URLL/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/METH0D/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/UR7XSD/b"), response,chain, 0))
  }

  test("In a URIXSD step, if there is a URI match, but the level has been exceeded a MismatchResult should be returned: StepType") {
    val urixsd = new URIXSD("uxd", "uxd", stepType, testSchema, Array[Step]())
    assertMismatchResult (urixsd.check (request("GET", "/START/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/URL_FAIL/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/METHOD_FAIL/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/ACCEPT/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/URL/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/METHOD/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/URLXSD/b"), response,chain, 2))
  }

  test("In a URIXSD step, if there is a URI match, the step should proceed to the next step : UUID") {
    val urixsd = new URIXSD("uxd", "uxd", uuidType, testSchema, Array[Step]())
    assert (urixsd.check (request("GET", "/55b76e92-6450-11e1-9012-37afadb5ff61/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/56d7a1fc-6450-11e1-b360-8fe15f519bf2/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/5731bb7e-6450-11e1-9b88-6ff2691237cd/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/578952c6-6450-11e1-892b-8bae86031338/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/57e75268-6450-11e1-892e-abc2baf50960/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/58415556-6450-11e1-96f9-17b1db29daf7/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/58a0ff60-6450-11e1-95bd-77590a8a0a53/b"), response,chain, 0).isEmpty)
  }

  test("In a URIXSD step, if there is a mismatch, a MismatchResult should be returned: UUID") {
    val urixsd = new URIXSD("uxd", "uxd", uuidType, testSchema, Array[Step]())
    assertMismatchResult (urixsd.check (request("GET", "/55b76e92-6450-11e1-9012-37afadbgff61/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/55/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/aoeee..x/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/09cgff.dehbj/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/55b76e92-6450-11e1-901237afadb5ff61/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/58415556-6450-11e1-96f9:17b1db29daf7/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/+58a0ff60-6450-11e1-95bd-77590a8a0a53/b"), response,chain, 0))
  }

  test("In a URIXSD step, if there is a URI match, but the level has been exceeded a MismatchResult should be returned: UUID") {
    val urixsd = new URIXSD("uxd", "uxd", uuidType, testSchema, Array[Step]())
    assertMismatchResult (urixsd.check (request("GET", "/55b76e92-6450-11e1-9012-37afadb5ff61/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/56d7a1fc-6450-11e1-b360-8fe15f519bf2/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/5731bb7e-6450-11e1-9b88-6ff2691237cd/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/578952c6-6450-11e1-892b-8bae86031338/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/57e75268-6450-11e1-892e-abc2baf50960/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/58415556-6450-11e1-96f9-17b1db29daf7/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/58a0ff60-6450-11e1-95bd-77590a8a0a53/b"), response,chain, 2))
  }

  test("In a URIXSD step, if there is a URI match, the step should proceed to the next step : EvenInt100") {
    val urixsd = new URIXSD("uxd", "uxd", evenIntType, testSchema, Array[Step]())
    assert (urixsd.check (request("GET", "/54/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/0/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/32/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/2/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/12/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/100/b"), response,chain, 0).isEmpty)
    assert (urixsd.check (request("GET", "/84/b"), response,chain, 0).isEmpty)
  }

  test("In a URIXSD step, if there is a URI match, and a captureHeader, the header should be set") {
    val accept = new Accept("accept", "Accept", 1000)
    val urixsd = new URIXSD("uxd", "uxd", evenIntType, testSchema, Some("foo"), Array[Step](accept))
    val req = request("GET", "/54/b")
    assert (urixsd.check (req, response,chain, 0).isDefined)
    assert (req.getHeader("FOO") == "54")
  }


  test("In a URIXSD step, if there is a mismatch, a MismatchResult should be returned: EvenInt100, assert") {
    val urixsd = new URIXSD("uxd", "uxd", evenIntType, testSchema, Array[Step]())
    assertMismatchResult (urixsd.check (request("GET", "/55/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/1/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/33/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/3/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/15/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/101/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/85/b"), response,chain, 0))
  }

  test("In a URIXSD step, if there is a mismatch, a MismatchResult should be returned: EvenInt100") {
    val urixsd = new URIXSD("uxd", "uxd", evenIntType, testSchema, Array[Step]())
    assertMismatchResult (urixsd.check (request("GET", "/101/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/555/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/hello/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/09cgff.dehbj/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/-99/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/3tecr/b"), response,chain, 0))
    assertMismatchResult (urixsd.check (request("GET", "/58a0ff60-6450-11e1-95bd-77590a8a0a53/b"), response,chain, 0))
  }

  test("In a URIXSD step, if there is a URI match, but the level has been exceeded a MismatchResult should be returned: EvenInt100") {
    val urixsd = new URIXSD("uxd", "uxd", evenIntType, testSchema, Array[Step]())
    assertMismatchResult (urixsd.check (request("GET", "/54/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/0/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/32/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/2/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/12/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/100/b"), response,chain, 2))
    assertMismatchResult (urixsd.check (request("GET", "/84/b"), response,chain, 2))
  }

  test("URI mismatch message should be the same of the uri regex") {
    val uri = new URI("u", "u", "u".r, Array[Step]())
    assert (uri.mismatchMessage == "u".r.toString)
  }

  test("In a URI step, if there is a URI match, the uriLevel should increase by 1") {
    val uri = new URI("a", "a", "a".r, Array[Step]())
    assert (uri.checkStep (request("GET", "/a/b"), response,chain, 0) == 1)

    val uri2 = new URI("ab", "a or b", "[a-b]".r, Array[Step]())
    assert (uri2.checkStep (request("GET", "/a/b"), response,chain, 0) == 1)
    assert (uri2.checkStep (request("GET", "/a/b"), response,chain, 1) == 2)
  }

  test("In a URI step, if there is a URI mismatch, the uriLevel should be -1") {
    val uri = new URI("a", "a", "a".r, Array[Step]())
    assert (uri.checkStep (request("GET", "/c/b"), response,chain, 0) == -1)

    val uri2 = new URI("ab", "a or b", "[a-b]".r, Array[Step]())
    assert (uri2.checkStep (request("GET", "/c/d"), response,chain, 0) == -1)
    assert (uri2.checkStep (request("GET", "/c/d"), response,chain, 1) == -1)
  }

  test("In a URI step, if there is a URI match, the context should have a uriLevel increase by 1"){
    val uri = new URI("a", "a", "a".r, Array[Step]())
    val ctx1 = StepContext()
    assert (uri.checkStep (request("GET", "/a/b"), response,chain, ctx1).get.uriLevel == 1)
  }

  test("In a URI step, if there is a URI match, the context should have a uriLevel increase by 1, if a captureHeader is set it should contain the header"){
    val uri = new URI("a", "a", "a".r, Some("URI"), Array[Step]())
    val nctx = uri.checkStep (request("GET", "/a/b"), response,chain, StepContext())
    assert(nctx.get.uriLevel == 1)
    assert(nctx.get.requestHeaders("URI") == List("a"))
  }

  test("In a URI step, if there is a URI mismatch, the returned context should be None") {
    val uri = new URI("a", "a", "a".r, Array[Step]())
    val ctx1 = StepContext()
    assert (uri.checkStep (request("GET", "/c/b"), response,chain, ctx1).isEmpty)
  }

  test("In a URI step, if there is a URI match, but the URI level has been exceeded the new URI level should be -1") {
    val uri = new URI("a", "a", "a".r, Array[Step]())
    assert (uri.checkStep (request("GET", "/a/b"), response,chain, 2) == -1)

    val uri2 = new URI("ab", "a or b", "[a-b]".r, Array[Step]())
    assert (uri2.checkStep (request("GET", "/a/b"), response,chain, 3) == -1)
    assert (uri2.checkStep (request("GET", "/a/b"), response,chain, 4) == -1)
  }

  test("Method mismatch message should be the same of the uri regex") {
    val method = new Method("GET", "GET", "GET".r, Array[Step]())
    assert (method.mismatchMessage == "GET".r.toString)
  }

  test("In a Method step, if the uriLevel has not been exceeded, the returned URI level should be -1") {
    val method = new Method("GET", "GET", "GET".r, Array[Step]())
    assert (method.checkStep (request("GET", "/a/b"), response,chain, 0) == -1)
    assert (method.checkStep (request("GET", "/a/b"), response,chain, 1) == -1)
  }

  test("In a Method step, if the uriLevel has been exceeded, and the method matches, the URI level should stay the same") {
    val method = new Method("GET", "GET", "GET".r, Array[Step]())
    assert (method.checkStep (request("GET", "/a/b"), response,chain, 2) == 2)
    assert (method.checkStep (request("GET", "/a/b"), response,chain, 3) == 3)
    val method2 = new Method("GETPOST", "GET or POST", "GET|POST".r, Array[Step]())
    assert (method2.checkStep (request("GET", "/a/b"), response,chain, 2) == 2)
    assert (method2.checkStep (request("POST", "/a/b"), response,chain, 3) == 3)
  }

  test("In a Method step, if the uriLevel has been exceeded, and the method does not match, the URI level should be set to -1") {
    val method = new Method("GET", "GET", "GET".r, Array[Step]())
    assert (method.checkStep (request("POST", "/a/b"), response,chain, 2) == -1)
    assert (method.checkStep (request("GTB", "/a/b"), response,chain, 3) == -1)
    val method2 = new Method("GETPOST", "GET or POST", "GET|POST".r, Array[Step]())
    assert (method2.checkStep (request("PUT", "/a/b"), response,chain, 2) == -1)
    assert (method2.checkStep (request("DELETE", "/a/b"), response,chain, 3) == -1)
  }

  test("A ReqTestFail step should fail if the content type does not match with a BadMediaTypeResult") {
    val rtf = new ReqTypeFail ("XML", "XML", "application/xml|application/json".r, 10)
    assertBadMediaType (rtf.check (request("PUT", "/a/b", "*.*"), response, chain, 1))
    assertBadMediaType (rtf.check (request("POST", "/index.html", "application/XMLA"), response, chain, 0))
  }

  test("A ReqTestFail step should return None if the content type matchs") {
    val rtf = new ReqTypeFail ("XML", "XML", "application/xml|application/json".r, 10)
    assert (rtf.check (request("PUT", "/a/b", "application/json"), response, chain, 1).isEmpty)
    assert (rtf.check (request("POST", "/index.html", "application/xml"), response, chain, 0).isEmpty)
  }

  test("ReqType mismatch message should be the same of the type regex") {
    val rt = new ReqType ("XML", "XML", "(application/xml|application/json)()".r, Array[Step]())
    assert (rt.mismatchMessage == "(application/xml|application/json)()".r.toString)
  }

  test("In a ReqType step, if the content type does not match, the returned URI level should be -1") {
    val rt = new ReqType ("XML", "XML", "(application/xml|application/json)()".r, Array[Step]())
    assert (rt.checkStep (request("PUT", "/a/b","*.*"), response,chain, 0) == -1)
    assert (rt.checkStep (request("POST", "/a/b","application/junk"), response,chain, 1) == -1)
    val rt2 = new ReqType ("XML", "XML", "text/html".r, Array[Step]())
    assert (rt2.checkStep (request("PUT", "/a/b","*.*"), response,chain, 0) == -1)
    assert (rt2.checkStep (request("POST", "/a/b","application/junk"), response,chain, 2) == -1)
  }

  test("In a ReqType step, if the content type is null, the returned URI level should be -1") {
    val rt = new ReqType ("XML", "XML", "(application/xml|application/json)()".r, Array[Step]())
    assert (rt.checkStep (request("PUT", "/a/b", null), response,chain, 0) == -1)
  }

  test("In a ReqType step, if the content matches, the URI level should stay the same") {
    val rt = new ReqType ("XML", "XML", "(application/xml|application/json)()".r, Array[Step]())
    assert (rt.checkStep (request("PUT", "/a/b","application/xml"), response,chain, 0) == 0)
    assert (rt.checkStep (request("POST", "/a/b","application/json"), response,chain, 1) == 1)
    val rt2 = new ReqType ("XML", "XML", "(text/html)()".r, Array[Step]())
    assert (rt2.checkStep (request("GET", "/a/b/c","text/html"), response,chain, 2) == 2)
  }

  test("In a WellFormedXML step, if the content contains well formed XML, the uriLevel should stay the same") {
    val wfx = new WellFormedXML("WFXML", "WFXML", 10, Array[Step]())
    assert (wfx.checkStep (request("PUT", "/a/b", "application/xml", <validXML xmlns="http://valid"/>), response, chain, 0) == 0)
    assert (wfx.checkStep (request("PUT", "/a/b", "application/xml", <validXML xmlns="http://valid"><more/></validXML>), response, chain, 1) == 1)
  }

  test("In a WellFormedXML step, the parsed DOM should be stored in the request") {
    val wfx = new WellFormedXML("WFXML", "WFXML", 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml", <validXML xmlns="http://valid"/>)
    wfx.checkStep (req1, response, chain, 0)
    assert (req1.parsedXML != null)
    assert (req1.parsedXML.isInstanceOf[Document])

    val req2 = request("PUT", "/a/b", "application/xml", <validXML xmlns="http://valid"><more/></validXML>)
    assert (wfx.checkStep (req2, response, chain, 1) == 1)
    assert (req2.parsedXML != null)
    assert (req2.parsedXML.isInstanceOf[Document])
  }


  test("In a WellFormedXML step, one should be able to reparse the XML by calling getInputStream") {
    val wfx = new WellFormedXML("WFXML", "WFXML", 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml", <validXML xmlns="http://valid"/>)
    wfx.checkStep (req1, response, chain, 0)
    val xml1 = XML.load(req1.getInputStream)
    assert (xml1 != null)

    val req2 = request("PUT", "/a/b", "application/xml", <validXML xmlns="http://valid"><more/></validXML>)
    wfx.checkStep (req2, response, chain, 1)
    val xml2 = XML.load(req2.getInputStream)
    assert (xml2 != null)
    assert ((xml2 \ "more") != null)
  }

  test("In a WellFormedXML step, if the content is not well formed XML, the uriLevel should be -1") {
    val wfx = new WellFormedXML("WFXML", "WFXML", 10, Array[Step]())
    assert (wfx.checkStep (request("PUT", "/a/b", "application/xml", """<validXML xmlns='http://valid'>"""), response, chain, 0) == -1)
    assert (wfx.checkStep (request("PUT", "/a/b", "application/xml", """{ \"bla\" : 55 }"""), response, chain, 1) == -1)
  }

  test("In a WellFormedXML step, if the content is not well formed XML, the request should contian a SAXException") {
    val wfx = new WellFormedXML("WFXML", "WFXML", 150, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml", """<validXML xmlns='http://valid'>""")

    wfx.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentErrorPriority == 150)

    val req2 = request("PUT", "/a/b", "application/xml", """{ \"bla\" : 55 }""")

    wfx.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentErrorPriority == 150)
  }

  test("In a WellFormedXML step, if the content is not well formed XML, you should still be able to open the input stream") {
    val wfx = new WellFormedXML("WFXML", "WFXML", 150, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml", """<validXML xmlns='http://valid'>""")

    wfx.checkStep (req1, response, chain, 0)
    assert (req1.getInputStream.available == 0)
    assert (req1.getInputStream.read == -1)

    val req2 = request("PUT", "/a/b", "application/xml", """{ \"bla\" : 55 }""")

    wfx.checkStep (req2, response, chain, 1)
    assert (req2.getInputStream.available == 0)
    assert (req2.getInputStream.read == -1)
  }

  test("In a WellFormedXML step, XML in the same request should not be parsed twice") {
    val wfx = new WellFormedXML("WFXML", "WFXML", 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml", <validXML xmlns="http://valid"/>)
    wfx.checkStep (req1, response, chain, 0)
    assert (req1.parsedXML != null)
    assert (req1.parsedXML.isInstanceOf[Document])
    assert (req1.contentErrorPriority == -1)

    val doc = req1.parsedXML
    wfx.checkStep (req1, response, chain, 0)
    //
    //  Assert that the same document is being returned.
    //
    assert (doc == req1.parsedXML)
  }

  test("In a WellFormedXML step, on two completely different requests the XML sholud be parsed each time"){
    val wfx = new WellFormedXML("WFXML", "WFXML", 100, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml", <validXML xmlns="http://valid"/>)
    val req2 = request("PUT", "/a/b", "application/xml", <validXML xmlns="http://valid"/>)

    wfx.checkStep (req1, response, chain, 0)
    assert (req1.parsedXML != null)
    assert (req1.parsedXML.isInstanceOf[Document])
    assert (req1.contentErrorPriority == -1)
    wfx.checkStep (req2, response, chain, 0)

    assert (req1.parsedXML != req2.parsedXML)
  }

  test("In a WellFormedXML step, parsed XML should be preserved and contian comments and process instructions") {
    val wfx = new WellFormedXML("WFXML","WFXML", 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
      new String(Files.readAllBytes(Paths.get("src/test/resources/xml/sample.xml")), "UTF-8"))

    wfx.checkStep(req1, response, chain, 0)

    assert (req1.parsedXML != null)
    assert (req1.parsedXML.isInstanceOf[Document])

    val builder = new StringBuilder
    val reader = new BufferedReader( new InputStreamReader(req1.getInputStream()))
    var read : String = null
    do {
      read = reader.readLine()
      if (read != null) {
        builder.append(read)
      }
    } while (read != null)
    reader.close()

    //
    //  Assert that the parser preserved comments, processing
    //  instructions, and relevent whitespace.
    //
    val pxml = builder.toString
    assert(pxml.contains("<!-- He we have a comment -->"))
    assert(pxml.contains("<?pr?>"))
    assert(pxml.contains("Here     we have a processing instruction"))
  }

  ignore ("Since WellFormedXML steps are synchornous, the parser pool should contain only a single idle parser") {
    assert (XMLParserPool.numActive == 0)
    assert (XMLParserPool.numIdle == 1)
  }

  test ("If there is no content error ContentFail should return NONE") {
    val cf  = new ContentFail ("CF", "CF", 10)
    val wfx = new WellFormedXML("WFXML", "WFXML", 10, Array[Step](cf))
    val req1 = request("PUT", "/a/b", "application/xml", <validXML xmlns="http://valid"/>)
    wfx.checkStep (req1, response, chain, 0)
    assert (cf.check(req1, response, chain, 0).isEmpty)
    assert (req1.contentErrorPriority == -1)
  }

  test ("If there is an error ContentFail should return BadContentResult") {
    val cf  = new ContentFail ("CF", "CF", 10)
    val wfx = new WellFormedXML("WFXML", "WFXML", 100, Array[Step](cf))
    val req1 = request("PUT", "/a/b", "application/xml", """<validXML xmlns='http://valid'>""")
    wfx.checkStep (req1, response, chain, 0)
    val result = cf.check(req1, response, chain, 0)
    assert (result.isDefined)
    assert (result.get.isInstanceOf[BadContentResult])
    assert (req1.contentErrorPriority == 100)
  }

  test("In a WellFormedJSON step, if the content contains well formed JSON, the uriLevel should stay the same") {
    val wfj = new WellFormedJSON("WFJSON", "WFJSON", 10, Array[Step]())
    assert (wfj.checkStep (request("PUT", "/a/b", "application/json", """ { "valid" : true } """), response, chain, 0) == 0)
    assert (wfj.checkStep (request("PUT", "/a/b", "application/json", """ { "valid" : [true, true, true] }"""), response, chain, 1) == 1)
  }

  test("In a WellFormedJSON step, if the content contains well formed JSON, the request should contain a JsonNode") {
    val wfj = new WellFormedJSON("WFJSON", "WFJSON", 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json", """ { "valid" : true } """)
    wfj.checkStep (req1, response, chain, 0)
    assert(req1.parsedJSON != null)
    assert(req1.parsedJSON.isInstanceOf[JsonNode])

    val req2 = request("PUT", "/a/b", "application/json", """ { "valid" : [true, true, true] }""")
    wfj.checkStep (req2, response, chain, 1)
    assert(req2.parsedJSON != null)
    assert(req2.parsedJSON.isInstanceOf[JsonNode])
  }

  test("In a WellFormedJSON step, if the content contains well formed JSON, contentErrorPriority should be -1") {
    val wfj = new WellFormedJSON("WFJSON", "WFJSON", 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json", """ { "valid" : true } """)
    wfj.checkStep (req1, response, chain, 0)
    assert (req1.contentErrorPriority == -1)

    val req2 = request("PUT", "/a/b", "application/json", """ { "valid" : [true, true, true] }""")
    wfj.checkStep (req2, response, chain, 1)
    assert (req2.contentErrorPriority == -1)
  }

  test("In a WellFormedJSON step, if the content contains well formed JSON, you should be able to reparse the JSON by calling getInputStream") {
    val wfj = new WellFormedJSON("WFJSON", "WFJSON", 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json", """ { "valid" : true } """)
    val req2 = request("PUT", "/a/b", "application/json", """ { "valid" : [true, true, true] }""")
    var jparser : ObjectMapper = null

    wfj.checkStep (req1, response, chain, 0)
    wfj.checkStep (req2, response, chain, 1)

    try {
      jparser = ObjectMapperPool.borrowParser
      val j1 = jparser.readValue(req1.getInputStream, classOf[java.util.Map[Object, Object]])
      val j2 = jparser.readValue(req2.getInputStream, classOf[java.util.Map[Object, Object]])

      assert (j1 != null)
      assert (j2 != null)
      assert (j2.asInstanceOf[java.util.Map[Object,Object]].get("valid") != null)
    } finally {
      if (jparser != null) ObjectMapperPool.returnParser(jparser)
    }
  }

  test("In a WellFormedJSON step, if the content contains JSON that is not well-formed, the uriLevel shoud be -1") {
    val wfj = new WellFormedJSON("WFJSON", "WFJSON", 10, Array[Step]())
    assert (wfj.checkStep (request("PUT", "/a/b", "application/json", """ { "valid" : ture } """), response, chain, 0) == -1)
    assert (wfj.checkStep (request("PUT", "/a/b", "application/json", """ <json /> """), response, chain, 1) == -1)
  }

  test("In a WellFormedJSON step, if the content contains JSON that is not well-formed, then the request should contain a ParseException") {
    val wfj = new WellFormedJSON("WFJSON", "WFJSON", 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json", """ { "valid" : ture } """)
    wfj.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[JsonParseException])

    val req2 = request("PUT", "/a/b", "application/json", """ <json /> """)
    wfj.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[JsonParseException])
  }

  test("In a WellFormedJSON step, if the content contains JSON that is not well-formed, you should still be able to access the input stream") {
    val wfj = new WellFormedJSON("WFJSON", "WFJSON", 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json", """ { "valid" : ture } """)
    wfj.checkStep (req1, response, chain, 0)
    assert (req1.getInputStream.available == 0)
    assert (req1.getInputStream.read == -1)

    val req2 = request("PUT", "/a/b", "application/json", """ <json /> """)
    wfj.checkStep (req2, response, chain, 1)
    assert (req2.getInputStream.available == 0)
    assert (req2.getInputStream.read == -1)
  }


  test("In a WellFormedJSON step, if the content contains JSON that is not well-formed, then the request should contain the correct contentErrorPriority") {
    val wfj = new WellFormedJSON("WFJSON", "WFJSON", 1000, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json", """ { "valid" : ture } """)
    wfj.checkStep (req1, response, chain, 0)
    assert (req1.contentErrorPriority == 1000)

    val req2 = request("PUT", "/a/b", "application/json", """ <json /> """)
    wfj.checkStep (req2, response, chain, 1)
    assert (req2.contentErrorPriority == 1000)
  }

  test("In a WellFormedJSON step, if the content contains well formed JSON, the same request should not be parsed twice") {
    val wfj = new WellFormedJSON("WFJSON", "WFJSON", 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json", """ { "valid" : true } """)
    wfj.checkStep (req1, response, chain, 0)
    assert(req1.parsedJSON != null)
    assert(req1.parsedJSON.isInstanceOf[JsonNode])

    val obj = req1.parsedJSON
    wfj.checkStep (req1, response, chain, 0)
    assert (obj == req1.parsedJSON)
  }

  test("In a WellFormedJSON step, on two completly differet requests the JSON should be parsed each time") {
    val wfj = new WellFormedJSON("WFJSON", "WFJSON", 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json", """ { "valid" : true } """)
    val req2 = request("PUT", "/a/b", "application/json", """ { "valid" : true } """)
    var jparser : ObjectMapper = null

    wfj.checkStep (req1, response, chain, 0)
    assert(req1.parsedJSON != null)
    assert(req1.parsedJSON.isInstanceOf[JsonNode])
    wfj.checkStep (req2, response, chain, 0)

    try {
      jparser = ObjectMapperPool.borrowParser
      val s1 = jparser.writeValueAsString(req1.parsedJSON)
      val s2 = jparser.writeValueAsString(req2.parsedJSON)

      assert (s1 == s2)
    } finally {
      if (jparser != null) ObjectMapperPool.returnParser(jparser)
    }
  }

  test ("In an XSD test, if the content contains valid XML, the uriLevel should stay the same") {
    val xsd = new XSD("XSD", "XSD", testSchema, false, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>f76d5638-bb4f-11e1-abb0-539c4b93e64a</id>
                          <stepType>START</stepType>
                          <even>22</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                           id="f76d5638-bb4f-11e1-abb0-539c4b93e64a"
                           stepType="START"
                           even="22"/>, true)

    assert (xsd.checkStep (req1, response, chain, 0) == 0)
    assert (xsd.checkStep (req2, response, chain, 1) == 1)
  }


  test ("In an XSD test, if the content contains valid XML, the contentErrorPriority should be -1") {
    val xsd = new XSD("XSD", "XSD", testSchema, false, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>f76d5638-bb4f-11e1-abb0-539c4b93e64a</id>
                          <stepType>START</stepType>
                          <even>22</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                           id="f76d5638-bb4f-11e1-abb0-539c4b93e64a"
                           stepType="START"
                           even="22"/>, true)

    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In an XSD test, if the content contains invalid XML, the uriLevel should be -1") {
    val xsd = new XSD("XSD", "XSD", testSchema, false, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>f76d5638-bb4f-11e1-abb0-539c4b93e64aaa</id>
                          <stepType>START</stepType>
                          <even>22</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                           id="f76d5638-bb4f-11e1-abb0-539c4b93e64aaaa"
                           stepType="START"
                           even="22"/>, true)

    assert (xsd.checkStep (req1, response, chain, 0) == -1)
    assert (xsd.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In an XSD test, if the content contains invalid XML, the errorContentPriorityShould be set") {
    val xsd = new XSD("XSD", "XSD", testSchema, false, 1001, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>f76d5638-bb4f-11e1-abb0-539c4b93e64aaa</id>
                          <stepType>START</stepType>
                          <even>22</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                           id="f76d5638-bb4f-11e1-abb0-539c4b93e64aaaa"
                           stepType="START"
                           even="22"/>, true)

    xsd.checkStep (req1, response, chain, 0)
    xsd.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 1001)
    assert (req2.contentErrorPriority == 1001)
  }


  test ("In an XSD test, if the content contains invalid XML, the request should contain a SAXException") {
    val xsd = new XSD("XSD", "XSD", testSchema, false, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>f76d5638-bb4f-11e1-abb0-539c4b93e64aaa</id>
                          <stepType>START</stepType>
                          <even>22</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                           id="f76d5638-bb4f-11e1-abb0-539c4b93e64aaaa"
                           stepType="START"
                           even="22"/>, true)

    xsd.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])

    xsd.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
  }

  test ("In an XSD test, if the content contains invalid XML, the uriLevel should be -1 (XSD 1.1 assert)") {
    val xsd = new XSD("XSD", "XSD", testSchema, false, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>309a8f1e-bb52-11e1-b9d9-b7652ca2118a</id>
                          <stepType>START</stepType>
                          <even>23</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                         id="309a8f1e-bb52-11e1-b9d9-b7652ca2118a"
                           stepType="START"
                           even="23"/>, true)

    assert (xsd.checkStep (req1, response, chain, 0) == -1)
    assert (xsd.checkStep (req2, response, chain, 1) == -1)
  }


  test ("In an XSD test, if the content contains invalid XML, the errorContentPriorityShould be set (XSD 1.1 assert)") {
    val xsd = new XSD("XSD", "XSD", testSchema, false, 1001, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>309a8f1e-bb52-11e1-b9d9-b7652ca2118a</id>
                          <stepType>START</stepType>
                          <even>23</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                         id="309a8f1e-bb52-11e1-b9d9-b7652ca2118a"
                           stepType="START"
                           even="23"/>, true)

    xsd.checkStep (req1, response, chain, 0)
    xsd.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 1001)
    assert (req2.contentErrorPriority == 1001)
  }

  test ("In an XSD test, if the content contains invalid XML, the request should contain a SAXException (XSD 1.1 assert)") {
    val xsd = new XSD("XSD", "XSD", testSchema, false, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>309a8f1e-bb52-11e1-b9d9-b7652ca2118a</id>
                          <stepType>START</stepType>
                          <even>23</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                           id="309a8f1e-bb52-11e1-b9d9-b7652ca2118a"
                           stepType="START"
                           even="23"/>, true)

    xsd.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])

    xsd.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
  }


  test ("In an XSD test, if the content contains valid XML, the uriLevel should stay the same (transform == true)") {
    val xsd = new XSD("XSD", "XSD", testSchema, true, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>f76d5638-bb4f-11e1-abb0-539c4b93e64a</id>
                          <stepType>START</stepType>
                          <even>22</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                           id="f76d5638-bb4f-11e1-abb0-539c4b93e64a"
                           stepType="START"
                           even="22"/>, true)

    assert (xsd.checkStep (req1, response, chain, 0) == 0)
    assert (xsd.checkStep (req2, response, chain, 1) == 1)
  }


  test ("In an XSD test, if the content contains valid XML, the contentErrorPriority should be -1 (transform == true)") {
    val xsd = new XSD("XSD", "XSD", testSchema, true, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>f76d5638-bb4f-11e1-abb0-539c4b93e64a</id>
                          <stepType>START</stepType>
                          <even>22</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                           id="f76d5638-bb4f-11e1-abb0-539c4b93e64a"
                           stepType="START"
                           even="22"/>, true)

    xsd.checkStep (req1, response, chain, 0)
    xsd.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In an XSD test, if the content contains valid XML1, with transform == true, then default values should be filled in") {
    val xsd = new XSD("XSD", "XSD", testSchema, true, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                         <id>21f1fcf6-bf38-11e1-878e-133ab65fcec3</id>
                        <stepType/>
                        <even/>
                      </e>, true)

    xsd.checkStep (req1, response, chain, 0)

    val updatedRequest = XML.load(req1.getInputStream)
    assert ((updatedRequest \ "stepType").text == "START")
    assert ((updatedRequest \ "even").text == "50")
  }

  test ("In an XSD test, if the content contains valid XML2, with transform == true, then default values should be filled in") {
    val xsd = new XSD("XSD", "XSD", testSchema, true, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                           id="f76d5638-bb4f-11e1-abb0-539c4b93e64a"/>, true)

    xsd.checkStep (req1, response, chain, 0)

    val updatedRequest = XML.load(req1.getInputStream)
    assert ((updatedRequest \ "@stepType").text == "START")
    assert ((updatedRequest \ "@even").text == "50")
  }

  test ("In an XSD test, if the content contains invalid XML, the uriLevel should be -1 (transform == true)") {
    val xsd = new XSD("XSD", "XSD", testSchema, true, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>f76d5638-bb4f-11e1-abb0-539c4b93e64aaa</id>
                          <stepType>START</stepType>
                          <even>22</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                           id="f76d5638-bb4f-11e1-abb0-539c4b93e64aaaa"
                           stepType="START"
                           even="22"/>, true)

    assert (xsd.checkStep (req1, response, chain, 0) == -1)
    assert (xsd.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In an XSD test, if the content contains invalid XML, the contentErrorPriority should be net (transform == true)") {
    val xsd = new XSD("XSD", "XSD", testSchema, true, 100, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>f76d5638-bb4f-11e1-abb0-539c4b93e64aaa</id>
                          <stepType>START</stepType>
                          <even>22</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                           id="f76d5638-bb4f-11e1-abb0-539c4b93e64aaaa"
                           stepType="START"
                           even="22"/>, true)

    xsd.checkStep (req1, response, chain, 0)
    xsd.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 100)
    assert (req2.contentErrorPriority == 100)
  }


  test ("In an XSD test, if the content contains invalid XML, the request should contain a SAXException (transform == true)") {
    val xsd = new XSD("XSD", "XSD", testSchema, true, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>f76d5638-bb4f-11e1-abb0-539c4b93e64aaa</id>
                          <stepType>START</stepType>
                          <even>22</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                           id="f76d5638-bb4f-11e1-abb0-539c4b93e64aaaa"
                           stepType="START"
                           even="22"/>, true)

    xsd.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])

    xsd.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
  }

  test ("In an XSD test, if the content contains invalid XML, the uriLevel should be -1 (XSD 1.1 assert, transform == true)") {
    val xsd = new XSD("XSD", "XSD", testSchema, true, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>309a8f1e-bb52-11e1-b9d9-b7652ca2118a</id>
                          <stepType>START</stepType>
                          <even>23</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                         id="309a8f1e-bb52-11e1-b9d9-b7652ca2118a"
                           stepType="START"
                           even="23"/>, true)

    assert (xsd.checkStep (req1, response, chain, 0) == -1)
    assert (xsd.checkStep (req2, response, chain, 1) == -1)
  }


  test ("In an XSD test, if the content contains invalid XML, the contentErrorPriority should be set (XSD 1.1 assert, transform == true)") {
    val xsd = new XSD("XSD", "XSD", testSchema, true, 1002, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>309a8f1e-bb52-11e1-b9d9-b7652ca2118a</id>
                          <stepType>START</stepType>
                          <even>23</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                         id="309a8f1e-bb52-11e1-b9d9-b7652ca2118a"
                           stepType="START"
                           even="23"/>, true)

    xsd.checkStep (req1, response, chain, 0)
    xsd.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 1002)
    assert (req2.contentErrorPriority == 1002)
  }

  test ("In an XSD test, if the content contains invalid XML, the request should contain a SAXException (XSD 1.1 assert, transform == true)") {
    val xsd = new XSD("XSD", "XSD", testSchema, true, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/xml",
                        <e xmlns="http://www.rackspace.com/repose/wadl/checker/step/test">
                          <id>309a8f1e-bb52-11e1-b9d9-b7652ca2118a</id>
                          <stepType>START</stepType>
                          <even>23</even>
                        </e>, true)
    val req2 = request ("PUT", "/a/b", "application/xml",
                        <a xmlns="http://www.rackspace.com/repose/wadl/checker/step/test"
                           id="309a8f1e-bb52-11e1-b9d9-b7652ca2118a"
                           stepType="START"
                           even="23"/>, true)

    xsd.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])

    xsd.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
  }

  test("In an XPath test, if the XPath resolves to true the uriLevel should stay the same") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "/tst:root", None, None, context, 10, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <root xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </root>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:root xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:root>, true)
    assert (xpath.checkStep (req1, response, chain, 0) == 0)
    assert (xpath.checkStep (req2, response, chain, 1) == 1)
  }

  test("In an XPath test, if the XPath resolves to true the uriLevel should stay the same in the context") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "/tst:root", None, None, context, 10, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <root xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </root>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:root xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:root>, true)
    assert (xpath.checkStep (req1, response, chain, StepContext()).get.uriLevel == 0)
    assert (xpath.checkStep (req2, response, chain, StepContext(1)).get.uriLevel == 1)
  }


  test("In an XPath test, if the XPath resolves to true and there is a capture header, the capture header should be set") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "/tst:root/@bar", None, None, context, 10, Some("Foo"), 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <root xmlns="http://test.org/test" bar="yum">
                         <child attribute="value"/>
                       </root>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:root xmlns:tst="http://test.org/test" bar="jaz">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:root>, true)
    val ctx1 = xpath.checkStep (req1, response, chain, StepContext()).get
    val ctx2 = xpath.checkStep (req2, response, chain, StepContext(1)).get

    assert (ctx1.requestHeaders("Foo") == List("yum"))
    assert (ctx2.requestHeaders("Foo") == List("jaz"))
  }

  test("In an XPath test, if the XPath resolves to true and there is a capture header, the capture header should be set (multi-value)") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "//@bar", None, None, context, 10, Some("Foo"), 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <root xmlns="http://test.org/test" bar="yum">
                         <child attribute="value" bar="yum2"/>
                       </root>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:root xmlns:tst="http://test.org/test" bar="jaz">
                         <tst:child attribute="value" bar="jaz2"/>
                         <tst:child attribute="value2" bar="jaz3"/>
                       </tst:root>, true)
    val ctx1 = xpath.checkStep (req1, response, chain, StepContext()).get
    val ctx2 = xpath.checkStep (req2, response, chain, StepContext(1)).get

    //
    //  Since we are coercing into a string, we should just get the
    //  first value.
    //
    assert (ctx1.requestHeaders("Foo") == List("yum"))
    assert (ctx2.requestHeaders("Foo") == List("jaz"))
  }

  test("In an XPath test, if the XPath resolves to true and there is a capture header, the capture header should be set (multi-value, version 2)") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "string-join((//@bar), ', ')", None, None, context, 20, Some("Foo"), 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <root xmlns="http://test.org/test" bar="yum">
                         <child attribute="value" bar="yum2"/>
                       </root>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:root xmlns:tst="http://test.org/test" bar="jaz">
                         <tst:child attribute="value" bar="jaz2"/>
                         <tst:child attribute="value2" bar="jaz3"/>
                       </tst:root>, true)
    val ctx1 = xpath.checkStep (req1, response, chain, StepContext()).get
    val ctx2 = xpath.checkStep (req2, response, chain, StepContext(1)).get

    //
    //  With XPath2 we can actually get separate values -- within a single header.
    //
    assert (ctx1.requestHeaders("Foo") == List("yum, yum2"))
    assert (ctx2.requestHeaders("Foo") == List("jaz, jaz2, jaz3"))
  }

  test("In an XPath test, if the XPath resolves to true and there is a capture header, the capture header should be set (version 2)") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "/tst:root/@bar", None, None, context, 20, Some("Foo"), 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <root xmlns="http://test.org/test" bar="yum">
                         <child attribute="value"/>
                       </root>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:root xmlns:tst="http://test.org/test" bar="jaz">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:root>, true)
    val ctx1 = xpath.checkStep (req1, response, chain, StepContext()).get
    val ctx2 = xpath.checkStep (req2, response, chain, StepContext(1)).get

    assert (ctx1.requestHeaders("Foo") == List("yum"))
    assert (ctx2.requestHeaders("Foo") == List("jaz"))
  }

  test("In an XPath test, if the XPath does not resolve context should be None.") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "/tst:root/@bar", None, None, context, 10, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <root xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </root>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:root xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:root>, true)
    assert (xpath.checkStep (req1, response, chain, StepContext()).isEmpty)
    assert (xpath.checkStep (req2, response, chain, StepContext(1)).isEmpty)
  }

  test("In an XPath test, if the XPath resolves to true the contentErrorPriority should be -1") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "/tst:root", None, None, context, 10, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <root xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </root>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:root xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:root>, true)
    xpath.checkStep (req1, response, chain, 0)
    xpath.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }


  test("In an XPath test, if the XPath resolves to false the uriLevel should be -1") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "/tst:root", None, None, context, 10, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    assert (xpath.checkStep (req1, response, chain, 0) == -1)
    assert (xpath.checkStep (req2, response, chain, 1) == -1)
  }


  test("In an XPath test, if the XPath resolves to false the contentErrorPriority should be set") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "/tst:root", None, None, context, 10, 1000, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    xpath.checkStep (req1, response, chain, 0)
    xpath.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 1000)
    assert (req2.contentErrorPriority == 1000)
  }


  test("In an XPath test, if the XPath resolves to false the request should contain a SAXParseException") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "/tst:root", None, None, context, 10, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    xpath.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])

    xpath.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
  }

  test("In an XPath test, if the XPath resolves to false the request should contain a SAXParseException, with a message of 'Expecting '+XPATH") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "/tst:root", None, None, context, 10, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    xpath.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("Expecting /tst:root"))
    assert (req1.contentErrorCode == 400)

    xpath.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("Expecting /tst:root"))
    assert (req2.contentErrorCode == 400)
  }

  test("In an XPath test, if the XPath resolves to false the request should contain a SAXParseException, with setErrorCode") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "/tst:root", None, Some(401), context, 10, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    xpath.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("Expecting /tst:root"))
    assert (req1.contentErrorCode == 401)

    xpath.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("Expecting /tst:root"))
    assert (req2.contentErrorCode == 401)
  }

  test("In an XPath test, if the XPath resolves to false the request should contain a SAXParseException, with set message") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "/tst:root", Some("/tst:root was expected"), None, context, 10, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    xpath.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("/tst:root was expected"))
    assert (req1.contentErrorCode == 400)

    xpath.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("/tst:root was expected"))
    assert (req2.contentErrorCode == 400)
  }

  test("In an XPath test, if the XPath resolves to false the request should contain a SAXParseException, with set message and set code") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "/tst:root", Some("/tst:root was expected"), Some(401), context, 10, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    xpath.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("/tst:root was expected"))
    assert (req1.contentErrorCode == 401)

    xpath.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("/tst:root was expected"))
    assert (req2.contentErrorCode == 401)
  }

  test("In an XPath test, if the XPath resolves to false the request should contain a SAXParseException, with set message, set code, and errorPriority") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "/tst:root", Some("/tst:root was expected"), Some(401), context, 10, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    xpath.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("/tst:root was expected"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 10)

    xpath.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("/tst:root was expected"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 10)
  }


  test("In an XPath test, if the XPath resolves to true the uriLevel should stay the same (XPath 2)") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "if (/tst:root) then true() else false()", None, None, context, 20, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <root xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </root>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:root xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:root>, true)
    assert (xpath.checkStep (req1, response, chain, 0) == 0)
    assert (xpath.checkStep (req2, response, chain, 1) == 1)
  }


  test("In an XPath test, if the XPath resolves to true the contentErrorPriority should be -1 (XPath 2)") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "if (/tst:root) then true() else false()", None, None, context, 20, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <root xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </root>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:root xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:root>, true)
    xpath.checkStep (req1, response, chain, 0)
    xpath.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }


  test("In an XPath test, if the XPath resolves to false the uriLevel should be -1 (XPath 2)") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "if (/tst:root) then true() else false()", None, None, context, 20, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    assert (xpath.checkStep (req1, response, chain, 0) == -1)
    assert (xpath.checkStep (req2, response, chain, 1) == -1)
  }

  test("In an XPath test, if the XPath resolves to false the contentErrorPriority should be set (XPath 2)") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "if (/tst:root) then true() else false()", None, None, context, 20, 101, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    xpath.checkStep (req1, response, chain, 0)
    xpath.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 101)
    assert (req2.contentErrorPriority == 101)
  }


  test("In an XPath test, if the XPath resolves to false the request should contain a SAXParseException (XPath 2)") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "if (/tst:root) then true() else false()", None, None, context, 20, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    xpath.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentErrorCode == 400)

    xpath.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentErrorCode == 400)
  }

  test("In an XPath test, if the XPath resolves to false the request should contain a SAXParseException, with a message of 'Expecting '+XPATH (XPath 2)") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "if (/tst:root) then true() else false()", None, None, context, 20, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    xpath.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("Expecting if (/tst:root) then true() else false()"))
    assert (req1.contentErrorCode == 400)

    xpath.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("Expecting if (/tst:root) then true() else false()"))
    assert (req1.contentErrorCode == 400)
  }

  test("In an XPath test, if the XPath resolves to false the request should contain a SAXParseException, with a message of 'Expecting '+XPATH (XPath 2) with set code") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "if (/tst:root) then true() else false()", None, Some(401), context, 20, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    xpath.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("Expecting if (/tst:root) then true() else false()"))
    assert (req1.contentErrorCode == 401)

    xpath.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("Expecting if (/tst:root) then true() else false()"))
    assert (req1.contentErrorCode == 401)
  }

  test("In an XPath test, if the XPath resolves to false the request should contain a SAXParseException, with set message(XPath 2)") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "if (/tst:root) then true() else false()", Some("/tst:root was expected"), None, context, 20, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    xpath.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("/tst:root was expected"))
    assert (req1.contentErrorCode == 400)

    xpath.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("/tst:root was expected"))
    assert (req2.contentErrorCode == 400)
  }

  test("In an XPath test, if the XPath resolves to false the request should contain a SAXParseException, with set message(XPath 2) and setCode") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "if (/tst:root) then true() else false()", Some("/tst:root was expected"), Some(401), context, 20, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    xpath.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("/tst:root was expected"))
    assert (req1.contentErrorCode == 401)

    xpath.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("/tst:root was expected"))
    assert (req2.contentErrorCode == 401)
  }

  test("In an XPath test, if the XPath resolves to false the request should contain a SAXParseException, with set message(XPath 2), setCode, and setPriorityAssertion") {
    val context = ImmutableNamespaceContext(Map("tst"->"http://test.org/test"))
    val xpath = new XPath("XPath", "XPath", "if (/tst:root) then true() else false()", Some("/tst:root was expected"), Some(401), context, 20, 101, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/xml",
                       <foot xmlns="http://test.org/test">
                         <child attribute="value"/>
                       </foot>, true)
    val req2 = request("PUT", "/a/b", "application/xml",
                       <tst:foot xmlns:tst="http://test.org/test">
                         <tst:child attribute="value"/>
                         <tst:child attribute="value2"/>
                       </tst:foot>, true)
    xpath.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("/tst:root was expected"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 101)

    xpath.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("/tst:root was expected"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 101)
  }

  test ("In a JSONXPath test, if the XPath resolves to ture the uriLevel should stay the same") {
    val context = ImmutableNamespaceContext(Map[String,String]())
    val xpath = new JSONXPath("JSONXPath", "JSONXPath", "exists($_?root)", None, None, context, 31, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json","""
          {
            "root" : false
          }
                       """, true)
    val req2 = request("PUT", "/a/b", "application/json","""
          {
            "root" : true
          }
                       """, true)
    assert (xpath.checkStep (req1, response, chain, StepContext()).get.uriLevel == 0)
    assert (xpath.checkStep (req2, response, chain, StepContext(1)).get.uriLevel == 1)
  }


  test ("In a JSONXPath test, if the XPath resolves to ture the content error priority should be -1") {
    val context = ImmutableNamespaceContext(Map[String,String]())
    val xpath = new JSONXPath("JSONXPath", "JSONXPath", "exists($_?root)", None, None, context, 31, 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json","""
          {
            "root" : false
          }
                       """, true)
    val req2 = request("PUT", "/a/b", "application/json","""
          {
            "root" : true
          }
                       """, true)
    xpath.checkStep (req1, response, chain, StepContext())
    xpath.checkStep (req2, response, chain, StepContext(1))

    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }


  test ("In a JSONXPath test, if the XPath resolves to ture and there is a capture head then it should be set") {
    val context = ImmutableNamespaceContext(Map[String,String]())
    val xpath = new JSONXPath("JSONXPath", "JSONXPath", "string($_?root)", None, None, context, 31, Some("FOO"), 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json","""
          {
            "root" : false
          }
                       """, true)
    val req2 = request("PUT", "/a/b", "application/json","""
          {
            "root" : true
          }
                       """, true)
    val ctx1 = xpath.checkStep (req1, response, chain, StepContext()).get
    val ctx2 = xpath.checkStep (req2, response, chain, StepContext(1)).get

    assert (ctx1.requestHeaders("FOO") == List("false"))
    assert (ctx2.requestHeaders("FOO") == List("true"))
  }


  test ("In a JSONXPath test, if the XPath resolves to ture and there is a capture head then it should be set (multi-value)") {
    val context = ImmutableNamespaceContext(Map[String,String]())
    val xpath = new JSONXPath("JSONXPath", "JSONXPath", "string-join($_?root?*,', ')", None, None, context, 31, Some("FOO"), 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [3, 2, 1]
          }
                       """, true)
    val req2 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [5, 4, 3]
          }
                       """, true)
    val ctx1 = xpath.checkStep (req1, response, chain, StepContext()).get
    val ctx2 = xpath.checkStep (req2, response, chain, StepContext(1)).get

    assert (ctx1.requestHeaders("FOO") == List("3, 2, 1"))
    assert (ctx2.requestHeaders("FOO") == List("5, 4, 3"))
  }

  test ("In a JSONXPath test, if the XPath resolves to false the context should be None") {
    val context = ImmutableNamespaceContext(Map[String,String]())
    val xpath = new JSONXPath("JSONXPath", "JSONXPath", "exists($_?booga)", None, None, context, 31, Some("FOO"), 10, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [3, 2, 1]
          }
                       """, true)
    val req2 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [5, 4, 3]
          }
                       """, true)
    assert (xpath.checkStep (req1, response, chain, StepContext()).isEmpty)
    assert (xpath.checkStep (req2, response, chain, StepContext(1)).isEmpty)
  }

  test ("In a JSONXPath test, if the XPath resolves to false the content error priority should be set") {
    val context = ImmutableNamespaceContext(Map[String,String]())
    val xpath = new JSONXPath("JSONXPath", "JSONXPath", "exists($_?booga)", None, None, context, 31, 100, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [3, 2, 1]
          }
                       """, true)
    val req2 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [5, 4, 3]
          }
                       """, true)
    xpath.checkStep (req1, response, chain, StepContext())
    xpath.checkStep (req2, response, chain, StepContext(1))

    assert (req1.contentErrorPriority == 100)
    assert (req2.contentErrorPriority == 100)
  }


  test ("In a JSONXPath test, if the XPath resolves to false the request should contain a SAXParseException") {
    val context = ImmutableNamespaceContext(Map[String,String]())
    val xpath = new JSONXPath("JSONXPath", "JSONXPath", "exists($_?booga)", None, None, context, 31, 100, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [3, 2, 1]
          }
                       """, true)
    val req2 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [5, 4, 3]
          }
                       """, true)
    xpath.checkStep (req1, response, chain, StepContext())
    xpath.checkStep (req2, response, chain, StepContext(1))

    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
  }


  test ("In a JSONXPath test, if the XPath resolves to false the request should contain a SAXParseException, with a message of 'Expecting '+XPATH") {
    val context = ImmutableNamespaceContext(Map[String,String]())
    val xpath = new JSONXPath("JSONXPath", "JSONXPath", "exists($_?booga)", None, None, context, 31, 100, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [3, 2, 1]
          }
                       """, true)
    val req2 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [5, 4, 3]
          }
                       """, true)
    xpath.checkStep (req1, response, chain, StepContext())
    xpath.checkStep (req2, response, chain, StepContext(1))

    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("Expecting exists($_?booga)"))

    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("Expecting exists($_?booga)"))
  }


  test ("In a JSONXPath test, if the XPath resolves to false the request should contain a SAXParseException, with set error code") {
    val context = ImmutableNamespaceContext(Map[String,String]())
    val xpath = new JSONXPath("JSONXPath", "JSONXPath", "exists($_?booga)", None, Some(401), context, 31, 100, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [3, 2, 1]
          }
                       """, true)
    val req2 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [5, 4, 3]
          }
                       """, true)
    xpath.checkStep (req1, response, chain, StepContext())
    xpath.checkStep (req2, response, chain, StepContext(1))

    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("Expecting exists($_?booga)"))
    assert (req1.contentErrorCode == 401)

    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("Expecting exists($_?booga)"))
    assert (req1.contentErrorCode == 401)
  }


  test ("In a JSONXPath test, if the XPath resolves to false the request should contain a SAXParseException, set message") {
    val context = ImmutableNamespaceContext(Map[String,String]())
    val xpath = new JSONXPath("JSONXPath", "JSONXPath", "exists($_?booga)", Some("booga was expected"), None, context, 31, 100, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [3, 2, 1]
          }
                       """, true)
    val req2 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [5, 4, 3]
          }
                       """, true)
    xpath.checkStep (req1, response, chain, StepContext())
    xpath.checkStep (req2, response, chain, StepContext(1))

    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("booga was expected"))
    assert (req1.contentErrorCode == 400)

    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("booga was expected"))
    assert (req1.contentErrorCode == 400)
  }

  test ("In a JSONXPath test, if the XPath resolves to false the request should contain a SAXParseException, set message, set code") {
    val context = ImmutableNamespaceContext(Map[String,String]())
    val xpath = new JSONXPath("JSONXPath", "JSONXPath", "exists($_?booga)", Some("booga was expected"), Some(401), context, 31, 100, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [3, 2, 1]
          }
                       """, true)
    val req2 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [5, 4, 3]
          }
                       """, true)
    xpath.checkStep (req1, response, chain, StepContext())
    xpath.checkStep (req2, response, chain, StepContext(1))

    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("booga was expected"))
    assert (req1.contentErrorCode == 401)

    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("booga was expected"))
    assert (req2.contentErrorCode == 401)
  }


  test ("In a JSONXPath test, if the XPath resolves to false the request should contain a SAXParseException, set message, set code, set error priority") {
    val context = ImmutableNamespaceContext(Map[String,String]())
    val xpath = new JSONXPath("JSONXPath", "JSONXPath", "exists($_?booga)", Some("booga was expected"), Some(401), context, 31, 100, Array[Step]())
    val req1 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [3, 2, 1]
          }
                       """, true)
    val req2 = request("PUT", "/a/b", "application/json","""
          {
            "root" : [5, 4, 3]
          }
                       """, true)
    xpath.checkStep (req1, response, chain, StepContext())
    xpath.checkStep (req2, response, chain, StepContext(1))

    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("booga was expected"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 100)

    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("booga was expected"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 100)
  }


  test ("An XSL should correctly transfrom request XML (XSL 1.0)") {
    val xsl = new XSL("XSL", "XSL", xsl1Templates, 10, Array[Step]())
    val req = request("PUT", "/a/b", "application/xml",
                      <foot xmlns="http://test.org/test">
                      <child attribute="value"/>
                      </foot>, true)
    xsl.checkStep (req, response, chain, 0)
    val transXML = XML.load(req.getInputStream)
    assert (transXML.label == "success")
    assert (transXML.namespace == "http://www.rackspace.com/repose/wadl/checker/step/test")
    assert ((transXML \ "@didIt").text == "true")
  }

  test ("An XSL should correctly transfrom request XML (XSL 2.0)") {
    val xsl = new XSL("XSL", "XSL", xsl2Templates, 10, Array[Step]())
    val req = request("PUT", "/a/b", "application/xml",
                      <foot xmlns="http://test.org/test">
                      <child attribute="value"/>
                      </foot>, true)
    xsl.checkStep (req, response, chain, 0)
    val transXML = XML.load(req.getInputStream)
    assert (transXML.label == "success")
    assert (transXML.namespace == "http://www.rackspace.com/repose/wadl/checker/step/test")
    assert ((transXML \ "@didIt").text == "true")
    assert ((transXML \ "@currentTime").text.contains(":"))
  }

  test ("In a header step, if the header is available then the uri level should stay the same.") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat")))

    assert (header.checkStep (req1, response, chain, 0) == 0)
    assert (header.checkStep (req2, response, chain, 1) == 1)
  }

  test ("In a header step, if the header is available then the uri level should stay the same in the context.") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat")))

    assert (header.checkStep (req1, response, chain, StepContext()).get.uriLevel == 0)
    assert (header.checkStep (req2, response, chain, StepContext(1)).get.uriLevel == 1)
  }

  test ("In a header step, if the header is available and a captureHeader is set the value should be caught in the captureheader") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, None, None, Some("Foo"), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set")))

    val ctx = header.checkStep (req1, response, chain, StepContext())
    assert(ctx.get.requestHeaders("Foo") == List("Set"))
  }

  test ("In a header step, if the header is available and a captureHeader is set the value should be caught in the captureheader (multiple values)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, None, None, Some("Foo"), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Sam", "Sat")))

    val ctx = header.checkStep (req1, response, chain, StepContext())
    assert(ctx.get.requestHeaders("Foo") == List("Set", "Sam", "Sat"))
  }


  test ("In a header step, if the header is not available the context should be set to None.") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Set")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Sat")))

    assert (header.checkStep (req1, response, chain, StepContext()).isEmpty)
    assert (header.checkStep (req2, response, chain, StepContext(1)).isEmpty)
  }

  test ("In a header step, if the header is available, but does not match the value...context should be set to None") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, None, None, Some("Foo"), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Bar")))

    assert (header.checkStep (req1, response, chain, StepContext()).isEmpty)
  }

  test ("In a header step, if the header is available then the contentErrorPriority should be -1.") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In a header step, if the header is available then the uri level should stay the same. (Multiple Headers)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Suite", "Station")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat", "Sweet", "Standing")))

    assert (header.checkStep (req1, response, chain, 0) == 0)
    assert (header.checkStep (req2, response, chain, 1) == 1)
  }

  test ("In a header step, if the header is available then the contentErrorPriority should be -1. (Multiple Headers)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Suite", "Station")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat", "Sweet", "Standing")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In a header step, if the header is available then the uri level should stay the same. (Multiple Items in a single header)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Suite, Station")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat", "Sweet, Standing")))

    assert (header.checkStep (req1, response, chain, 0) == 0)
    assert (header.checkStep (req2, response, chain, 1) == 1)
  }

  test ("In a header step, if the header is available then the contentErrorPriority should be -1. (Multiple Items in a single header)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Suite, Station")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat", "Sweet, Standing")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In a header step, if a header exists, but the header does not match the regex the urilevel should be set to -1.") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In a header step, if a header exists, but the header does not match the contentErrorPriority should be set.") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 1001, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 1001)
    assert (req2.contentErrorPriority == 1001)
  }

  test ("In a header step, if a header exists, but the header does not match the regex the urilevel should be set to -1. (Multiple Headers)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat", "Rat")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In a header step, if a header exists, but the header does not match the contentErrorPriority should be set. (Multiple Headers)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 1002, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat", "Rat")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 1002)
    assert (req2.contentErrorPriority == 1002)
  }

  test ("In a header step, if a header exists, but the header does not match the the regex the urilevel should be set to -1. (Multiple Items in a single header)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set, Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat, Rat")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In a header step, if a header exists, but the header does not match the the regex the contentErrorPriority should be set. (Multiple Items in a single header)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 1003, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set, Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat, Rat")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 1003)
    assert (req2.contentErrorPriority == 1003)
  }

  test ("In a header step, if a header exists, but the header does not match the the regex the requst should conatin an Exception") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
  }

  test ("In a header step, if a header exists, but the header does not match the the regex the requst should conatin an Exception (Custom Code)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In a header step, if a header exists, but the header does not match the the regex the requst should conatin an Exception (Custom Message)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("Very Bad Header"), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Very Bad Header"))
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Very Bad Header"))
    assert (req2.contentErrorCode == 400)
  }

  test ("In a header step, if a header exists, but the header does not match the the regex the requst should conatin an Exception (Custom Code, Custom Message)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("Very Bad Header"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Very Bad Header"))
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Very Bad Header"))
    assert (req2.contentErrorCode == 401)
  }

  test ("In a header step, if a header exists, but the header does not match the the regex the requst should conatin an Exception (Custom Code, Custom Message, Error Priority)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("Very Bad Header"), Some(401), 100, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Very Bad Header"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 100)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Very Bad Header"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 100)
  }

  test ("In a header step, if a header exists, but the header does not match the the regex the requst should conatin an Exception. (Multiple Headers)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat", "Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
  }

  test ("In a header step, if a header exists, but the header does not match the the regex the requst should conatin an Exception. (Multiple Headers, Custom Code)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat", "Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In a header step, if a header exists, but the header does not match the the regex the requst should conatin an Exception. (Multiple Headers, Custom Message)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("Bad?@!"), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat", "Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Bad?@!"))
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Bad?@!"))
    assert (req2.contentErrorCode == 400)
  }

  test ("In a header step, if a header exists, but the header does not match the the regex the requst should conatin an Exception. (Multiple Headers, Custom Message, Custom Code)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("Bad?@!"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat", "Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Bad?@!"))
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Bad?@!"))
    assert (req2.contentErrorCode == 401)
  }


  test ("In a header step, if a header exists, but the header does not match the the regex the requst should conatin an Exception. (Multiple Headers, Custom Message, Custom Code, contentErrorPriority)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("Bad?@!"), Some(401), 1011, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat", "Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Bad?@!"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 1011)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Bad?@!"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 1011)
  }

  test ("In a header step, if a header exists, but the header does not match the the regex the requst should conatin an Exception. (Multiple Items in a single header)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set, Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat, Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
  }

  test ("In a header step, if a header exists, but the header does not match the the regex the requst should conatin an Exception. (Multiple Items in a single header, Custom Code)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set, Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat, Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In a header step, if a header exists, but the header does not match the the regex the requst should conatin an Exception. (Multiple Items in a single header, Custom Message)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("What?"), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set, Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat, Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    assert (req1.contentError.getMessage.contains("What?"))
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
    assert (req2.contentError.getMessage.contains("What?"))
  }

  test ("In a header step, if a header exists, but the header does not match the the regex the requst should conatin an Exception. (Multiple Items in a single header, Custom Message, Custom Code)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("What?"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set, Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat, Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    assert (req1.contentError.getMessage.contains("What?"))
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
    assert (req2.contentError.getMessage.contains("What?"))
  }


  test ("In a header step, if a header exists, but the header does not match the the regex the requst should conatin an Exception. (Multiple Items in a single header, Custom Message, Custom Code, ContentErrorPriority)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("What?"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set, Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat, Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    assert (req1.contentError.getMessage.contains("What?"))
    assert (req1.contentErrorPriority == 10)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
    assert (req2.contentError.getMessage.contains("What?"))
    assert (req2.contentErrorPriority == 10)
  }

  test ("In a header step, if a header is not found, the urilevel should be set to -1.") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Set")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Sat")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In a header step, if a header is not found, the contentErrorPriority should be set") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 25, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Set")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Sat")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 25)
    assert (req2.contentErrorPriority == 25)
  }

  test ("In a header step, if a header is not found, the request should contain an Exception.") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Set")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Sat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
  }

  test ("In a header step, if a header is not foound, the request should contain an Exception. (Custom Code)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Set")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Sat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In a header step, if a header is not foound, the request should contain an Exception. (Custom Message)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("What Header?"), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Set")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Sat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("What Header?"))
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("What Header?"))
    assert (req2.contentErrorCode == 400)
  }

  test ("In a header step, if a header is not foound, the request should contain an Exception. (Custom Code, Custom Message)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("What Header?"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Set")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Sat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("What Header?"))
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("What Header?"))
    assert (req2.contentErrorCode == 401)
  }

  test ("In a header step, if a header is not foound, the request should contain an Exception. (Custom Code, Custom Message, ContentErrorPriority)") {
    val header = new Header("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("What Header?"), Some(401), 10123, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Set")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Sat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("What Header?"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 10123)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("What Header?"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 10123)
  }

  test ("In a headerSingle step, if the header is not avaliable the result of checkStep should be None") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerSingle step, if the header is not available a correct error message should be set") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("foo"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("foo"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerSingle step, if the header is not available and a custom message is set, make sure the message is reflected") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerSingle step, if the header is not available and a custom error code is set, then the error code should be retuned") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("foo"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("foo"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerSingle step, if the header is not available and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerSingle step, if the header value does not match the result of checkStep should be None") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerSingle step, if the header value does not match a correct error message should be set") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("foo"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("foo"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerSingle step, if the header vaule does not match and a custom message is set, make sure the message is reflected") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerSingle step, if the header value does not match a custom error code is set, then the error code should be retuned") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("foo"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("foo"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerSingle step, if the header value does not match and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerSingle step, if the header value contains multiple values then checkStep should return None") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "goo")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerSingle step, if the header contains multiple values a correct error message should be set") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("1 and only 1 instance"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("1 and only 1 instance"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerSingle step, if the header contains multiple values and a custom message is set, make sure the message is reflected") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerSingle step, if the header contains multiple values and a custom error code is set, then the error code should be retuned") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("1 and only 1 instance"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("1 and only 1 instance"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerSingle step, if the header contians multiple values and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerSingle step, if the header contians a single valid value, then the return context should not be None") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("foo")))

    assert (header.checkStep(req1, response, chain, StepContext()) != None)
    assert (header.checkStep(req2, response, chain, StepContext()) != None)
  }

  test ("In a headerSingle step, if the header contians a single valid value, then the return context should not have additional headers set") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("foo")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.isEmpty)
    assert (ctx2.get.requestHeaders.isEmpty)
  }

  test("In a headerSingle step, if the header contains a valid value and a capture header is specified then it should be set"){
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "foo".r, None, None, Some("X-TEST-COPY"), 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("foo")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.get("X-TEST-COPY").get == List("foo"))
    assert (ctx2.get.requestHeaders.get("X-TEST-COPY").get == List("foo"))
  }

  test ("In a headerSingle step, if the header contians a single valid value, then the return context should not be None (comma valid)") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "f.*".r, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("f,o,o")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("f, oo, o")))

    assert (header.checkStep(req1, response, chain, StepContext()) != None)
    assert (header.checkStep(req2, response, chain, StepContext()) != None)
  }

  test ("In a headerSingle step, if the header contians a single valid value, then the return context should not have additional headers set (comma valid)") {
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "f.*".r, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("f,o,o")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("f, oo, o")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.isEmpty)
    assert (ctx2.get.requestHeaders.isEmpty)
  }

  test("In a headerSingle step, if the header contains a valid value and a capture header is specified then it should be set (comma valid)"){
    val header = new HeaderSingle("HEADER_SINGLE", "HEADER_SINGLE", "X-TEST-HEADER", "f.*".r, None, None, Some("X-TEST-COPY"), 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("f,o,o")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("f, oo, o")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.get("X-TEST-COPY").get == List("f,o,o"))
    assert (ctx2.get.requestHeaders.get("X-TEST-COPY").get == List("f, oo, o"))
  }

  test ("In a headerXSDSingle step, if the header is not avaliable the result of checkStep should be None") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerXSDSingle step, if the header is not available a correct error message should be set") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerXSDSingle step, if the header is not available and a custom message is set, make sure the message is reflected") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerXSDSingle step, if the header is not available and a custom error code is set, then the error code should be retuned") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerXSDSingle step, if the header is not available and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerXSDSingle step, if the header value does not match the result of checkStep should be None") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4bf1b3d6-e847-42d9-8562-63d84ebce74z")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerXSDSingle step, if the header value does not match a correct error message should be set") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4bf1b3d6-e847-42d9-8562-63d84ebce74z")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerXSDSingle step, if the header vaule does not match and a custom message is set, make sure the message is reflected") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4bf1b3d6-e847-42d9-8562-63d84ebce74z")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerXSDSingle step, if the header value does not match a custom error code is set, then the error code should be retuned") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4bf1b3d6-e847-42d9-8562-63d84ebce74z")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerXSDSingle step, if the header value does not match and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4bf1b3d6-e847-42d9-8562-63d84ebce74z")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerXSDSingle step, if the header value contains multiple values then checkStep should return None") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("fe7f23cb-ab31-41d1-8f0a-82c7cf09b17d", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "goo")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerXSDSingle step, if the header contains multiple values a correct error message should be set") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("fe7f23cb-ab31-41d1-8f0a-82c7cf09b17d", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("1 and only 1 instance"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("1 and only 1 instance"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerXSDSingle step, if the header contains multiple values and a custom message is set, make sure the message is reflected") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("fe7f23cb-ab31-41d1-8f0a-82c7cf09b17d", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerXSDSingle step, if the header contains multiple values and a custom error code is set, then the error code should be retuned") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("fe7f23cb-ab31-41d1-8f0a-82c7cf09b17d", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("1 and only 1 instance"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("1 and only 1 instance"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerXSDSingle step, if the header contians multiple values and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("fe7f23cb-ab31-41d1-8f0a-82c7cf09b17d", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerXSDSingle step, if the header contians a single valid value, then the return context should not be None") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("b5e4b5f7-454d-4f1e-82fb-292d110f0783")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("b5e4b5f7-454d-4f1e-82fb-292d110f0783")))

    assert (header.checkStep(req1, response, chain, StepContext()) != None)
    assert (header.checkStep(req2, response, chain, StepContext()) != None)
  }

  test ("In a headerXSDSingle step, if the header contians a single valid value, then the return context should not have additional headers set") {
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("b5e4b5f7-454d-4f1e-82fb-292d110f0783")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("b5e4b5f7-454d-4f1e-82fb-292d110f0783")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.isEmpty)
    assert (ctx2.get.requestHeaders.isEmpty)
  }

  test("In a headerXSDSingle step, if the header contains a valid value and a capture header is specified then it should be set"){
    val header = new HeaderXSDSingle("HEADER_XSD_SINGLE", "HEADER_XSD_SINGLE", "X-TEST-HEADER", uuidType, testSchema, None, None, Some("X-TEST-COPY"), 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("b5e4b5f7-454d-4f1e-82fb-292d110f0783")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("b5e4b5f7-454d-4f1e-82fb-292d110f0783")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.get("X-TEST-COPY").get == List("b5e4b5f7-454d-4f1e-82fb-292d110f0783"))
    assert (ctx2.get.requestHeaders.get("X-TEST-COPY").get == List("b5e4b5f7-454d-4f1e-82fb-292d110f0783"))
  }

  test ("In an XSD header step, if the header is available then the uri level should stay the same.") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    assert (header.checkStep (req1, response, chain, 0) == 0)
    assert (header.checkStep (req2, response, chain, 1) == 1)
  }

  test ("In an XSD header step, if the header is available then the uri level should stay the same in the context.") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    assert (header.checkStep (req1, response, chain, StepContext()).get.uriLevel == 0)
    assert (header.checkStep (req2, response, chain, StepContext(1)).get.uriLevel == 1)
  }

  test ("In an XSD header step, if the header is available and a capture header is set, the value should be set in the capture header.") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, None, None, Some("Foo"), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    val ctx1 = header.checkStep (req1, response, chain, StepContext()).get
    val ctx2 = header.checkStep (req2, response, chain, StepContext(1)).get

    assert (ctx1.requestHeaders("Foo") == List("28d42e00-e25a-11e1-9897-efbf2fa68353"))
    assert (ctx2.requestHeaders("Foo") == List("2fbf4592-e25a-11e1-bae1-93374682bd20"))
  }

  test ("In an XSD header step, if the header is available and a capture header is set, the value should be set in the capture header (multiple values).") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, None, None, Some("Foo"), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353", "716ab810-b61e-11e4-bee7-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20", "729a6aa0-b61e-11e4-a8e2-28cfe92134e7", "74272958-b61e-11e4-9e31-28cfe92134e7")))

    val ctx1 = header.checkStep (req1, response, chain, StepContext()).get
    val ctx2 = header.checkStep (req2, response, chain, StepContext(1)).get

    assert (ctx1.requestHeaders("Foo") == List("28d42e00-e25a-11e1-9897-efbf2fa68353", "716ab810-b61e-11e4-bee7-28cfe92134e7"))
    assert (ctx2.requestHeaders("Foo") == List("2fbf4592-e25a-11e1-bae1-93374682bd20", "729a6aa0-b61e-11e4-a8e2-28cfe92134e7", "74272958-b61e-11e4-9e31-28cfe92134e7"))
  }


  test ("In an XSD header step, if the header is not available then the context should be set to None") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-I"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-I"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    assert (header.checkStep (req1, response, chain, StepContext()).isEmpty)
    assert (header.checkStep (req2, response, chain, StepContext(1)).isEmpty)
  }

  test ("In an XSD header step, if the header is available but does not match the XSD the context should be set to None") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, None, None, Some("Foo"), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-FOOO-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20-998373-FFF")))

    assert(header.checkStep (req1, response, chain, StepContext()).isEmpty)
    assert(header.checkStep (req2, response, chain, StepContext(1)).isEmpty)
  }

  test ("In an XSD header step, if the header is available then the contentErrorPriority should be -1.") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In an XSD header step, if the header is available then the uri level should stay the same. (Multiple Headers)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353","ed2127f6-327b-11e2-abc3-ebcd8ddbb97b")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20","f5bc95d0-327b-11e2-9f31-e79a818b84c8")))

    assert (header.checkStep (req1, response, chain, 0) == 0)
    assert (header.checkStep (req2, response, chain, 1) == 1)
  }

  test ("In an XSD header step, if the header is available then the contentErrorPriority should be -1. (Multiple Headers)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353","ed2127f6-327b-11e2-abc3-ebcd8ddbb97b")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20","f5bc95d0-327b-11e2-9f31-e79a818b84c8")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In an XSD header step, if the header is available then the uri level should stay the same. (Multiple Items in a single header)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353, ed2127f6-327b-11e2-abc3-ebcd8ddbb97b")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20, f5bc95d0-327b-11e2-9f31-e79a818b84c8")))

    assert (header.checkStep (req1, response, chain, 0) == 0)
    assert (header.checkStep (req2, response, chain, 1) == 1)
  }

  test ("In an XSD header step, if the header is available then the contentErrorPriority should be -1. (Multiple Items in a single header)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353, ed2127f6-327b-11e2-abc3-ebcd8ddbb97b")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20, f5bc95d0-327b-11e2-9f31-e79a818b84c8")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the uri level should be -1") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the contentErrorPriority should be set.") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 10)
    assert (req2.contentErrorPriority == 10)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the uri level should be -1 (Multiple Headers)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353", "ed2127f6-327b-11e2-abc3ebcd8ddbb97")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20", "f5bc95d0327b11e29f31e79a818b84c8")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the contentErrorPriority should be set(Multiple Headers)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353", "ed2127f6-327b-11e2-abc3ebcd8ddbb97")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20", "f5bc95d0327b11e29f31e79a818b84c8")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 10)
    assert (req2.contentErrorPriority == 10)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the uri level should be -1 (Multiple Items in a single header)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353, ed2127f6-327b-11e2-abc3ebcd8ddbb97")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20, f5bc95d0327b11e29f31e79a818b84c8")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the contentErrorPriority should be set (Multiple Items in a single header)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353, ed2127f6-327b-11e2-abc3ebcd8ddbb97")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20, f5bc95d0327b11e29f31e79a818b84c8")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 10)
    assert (req2.contentErrorPriority == 10)
  }

  test ("In an XSD header step, if the header is not available then the uri level should be -1") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In an XSD header step, if the header is not available then the contentErrorPriority should be set") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 100, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 100)
    assert (req2.contentErrorPriority == 100)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the request should conatin an Exception") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the request should conatin an Exception (Custom Code)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the request should conatin an Exception (Custom Message)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Bad XSD Header"), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Bad XSD Header"))
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Bad XSD Header"))
    assert (req2.contentErrorCode == 400)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the request should conatin an Exception (Custom Message, Custom Code)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Bad XSD Header"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Bad XSD Header"))
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Bad XSD Header"))
    assert (req2.contentErrorCode == 401)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the request should conatin an Exception (Custom Message, Custom Code, ContentPriority)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Bad XSD Header"), Some(401), 1001, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Bad XSD Header"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 1001)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Bad XSD Header"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 1001)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the request should conatin an Exception (Multiple Headers)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("503e5d36-327c-11e2-80d2-33e47888902d","28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("5cbcd858-327c-11e2-82e9-27eb85d59274","2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the request should conatin an Exception (Multiple Headers, Custom Code)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("503e5d36-327c-11e2-80d2-33e47888902d","28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("5cbcd858-327c-11e2-82e9-27eb85d59274","2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the request should conatin an Exception (Multiple Headers, Custom Message)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Bste?"), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("503e5d36-327c-11e2-80d2-33e47888902d","28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("5cbcd858-327c-11e2-82e9-27eb85d59274","2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    assert (req1.contentError.getMessage.contains("Bste?"))
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
    assert (req2.contentError.getMessage.contains("Bste?"))
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the request should conatin an Exception (Multiple Headers, Custom Code, Custom Message)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Bste?"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("503e5d36-327c-11e2-80d2-33e47888902d","28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("5cbcd858-327c-11e2-82e9-27eb85d59274","2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    assert (req1.contentError.getMessage.contains("Bste?"))
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
    assert (req2.contentError.getMessage.contains("Bste?"))
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the request should conatin an Exception (Multiple Headers, Custom Code, Custom Message, contentErrorPriority)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Bste?"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("503e5d36-327c-11e2-80d2-33e47888902d","28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("5cbcd858-327c-11e2-82e9-27eb85d59274","2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    assert (req1.contentError.getMessage.contains("Bste?"))
    assert (req1.contentErrorPriority == 10)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
    assert (req2.contentError.getMessage.contains("Bste?"))
    assert (req2.contentErrorPriority == 10)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the request should conatin an Exception (Multiple Items in a single header)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("503e5d36-327c-11e2-80d2-33e47888902d, 28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("5cbcd858-327c-11e2-82e9-27eb85d59274, 2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the request should conatin an Exception (Multiple Items in a single header, Custom Code)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("503e5d36-327c-11e2-80d2-33e47888902d, 28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("5cbcd858-327c-11e2-82e9-27eb85d59274, 2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the request should conatin an Exception (Multiple Items in a single header, Custom Message)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Abc"), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("503e5d36-327c-11e2-80d2-33e47888902d, 28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("5cbcd858-327c-11e2-82e9-27eb85d59274, 2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Abc"))
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Abc"))
    assert (req2.contentErrorCode == 400)
  }

  test ("In an XSD header step, if the header is available, but the content is not correct, the request should conatin an Exception (Multiple Items in a single header, Custom Code, Custom Message)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Abc"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("503e5d36-327c-11e2-80d2-33e47888902d, 28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("5cbcd858-327c-11e2-82e9-27eb85d59274, 2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Abc"))
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Abc"))
    assert (req2.contentErrorCode == 401)
  }


  test ("In an XSD header step, if the header is available, but the content is not correct, the request should conatin an Exception (Multiple Items in a single header, Custom Code, Custom Message, ContentPrirority)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Abc"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("503e5d36-327c-11e2-80d2-33e47888902d, 28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("5cbcd858-327c-11e2-82e9-27eb85d59274, 2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Abc"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 10)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Abc"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 10)
  }

  test ("In an XSD header step, if the header is not available then the request should contain an Exception") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
  }

  test ("In an XSD header step, if the header is not available then the request should contain an Exception (Custom Code)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In an XSD header step, if the header is not available then the request should contain an Exception (Custom Message)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Xyz"), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Xyz"))
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Xyz"))
    assert (req2.contentErrorCode == 400)
  }

  test ("In an XSD header step, if the header is not available then the request should contain an Exception (Custom Message, Custom Code)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Xyz"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Xyz"))
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Xyz"))
    assert (req2.contentErrorCode == 401)
  }

  test ("In an XSD header step, if the header is not available then the request should contain an Exception (Custom Message, Custom Code, Content Priority)") {
    val header = new HeaderXSD("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Xyz"), Some(401), 25, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Xyz"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 25)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Xyz"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 25)
  }

  test ("In a header any step, if the header is available then the uri level should stay the same.") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat")))

    assert (header.checkStep (req1, response, chain, 0) == 0)
    assert (header.checkStep (req2, response, chain, 1) == 1)
  }

  test ("In a header any step, if the header is available then the uri level should stay the same in the context.") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat")))

    assert (header.checkStep (req1, response, chain, StepContext()).get.uriLevel == 0)
    assert (header.checkStep (req2, response, chain, StepContext(1)).get.uriLevel == 1)
  }

  test ("In a header any step, if the header is available and a capture header is set, the matching values should be caught in the capture header") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, None, None, Some("Foo"), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Sam", "Foo", "Bar")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Baz", "Bar", "Sat")))

    val ctx1 = header.checkStep (req1, response, chain, StepContext()).get
    val ctx2 = header.checkStep (req2, response, chain, StepContext(1)).get

    assert (ctx1.requestHeaders("Foo")  ==  List("Set", "Sam"))
    assert (ctx2.requestHeaders("Foo") ==  List("Sat"))
  }

  test ("In a header any step, if the header is available but values do not match the capture header should be set to None.") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, None, None, Some("Foo"), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Foo", "Bar")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Baz", "Bar")))

    assert (header.checkStep (req1, response, chain, StepContext()).isEmpty)
    assert (header.checkStep (req2, response, chain, StepContext(1)).isEmpty)
  }

  test ("In a header any step, if the header is not available the capture header should be set to None.") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, None, None, Some("Foo"), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Foo", "Bar")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Baz", "Bar")))

    assert (header.checkStep (req1, response, chain, StepContext()).isEmpty)
    assert (header.checkStep (req2, response, chain, StepContext(1)).isEmpty)
  }

  test ("In a header any step, if the header is available then the contentErrorPriority should be -1.") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In a header any step, if the header is available then the uri level should stay the same. (Multiple Headers)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Suite", "Station")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat", "Sweet", "Standing")))

    assert (header.checkStep (req1, response, chain, 0) == 0)
    assert (header.checkStep (req2, response, chain, 1) == 1)
  }

  test ("In a header any step, if the header is available then the contentErrorPriority should be -1. (Multiple Headers)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Suite", "Station")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat", "Sweet", "Standing")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In a header any step, if the header is available then the uri level should stay the same. (Multiple Items in a single header)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Suite, Station")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat", "Sweet, Standing")))

    assert (header.checkStep (req1, response, chain, 0) == 0)
    assert (header.checkStep (req2, response, chain, 1) == 1)
  }

  test ("In a header any step, if the header is available then the contentErrorPriority should be -1. (Multiple Items in a single header)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Suite, Station")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Sat", "Sweet, Standing")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In a header any step, if the header any available then the uri level should stay the same, even if other headers don't match.") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Auite", "Xtation")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Xat", "Aweet", "Standing")))

    assert (header.checkStep (req1, response, chain, 0) == 0)
    assert (header.checkStep (req2, response, chain, 1) == 1)
  }

  test ("In a header any step, if the header any available then the contentErrorPriority should be -1, even if other headers don't match.") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Set", "Auite", "Xtation")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Xat", "Aweet", "Standing")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In a header any step, if a header exists, but the header does not match the the regex the urilevel should be set to -1.") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In a header any step, if a header exists, but the header does not match the the regex the contentErrorPriority should be set.") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 10)
    assert (req2.contentErrorPriority == 10)
  }

  test ("In a header any step, if a header exists, but none of the headers match the the regex the urilevel should be set to -1.") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret", "RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat", "LET", "XOO!")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In a header any step, if a header exists, but none of the headers match the the regex the contntErrorPriority should be set") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret", "RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat", "LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 10)
    assert (req2.contentErrorPriority == 10)
  }

  test ("In a header any step, if a header is not found the urilevel should be set to -1.") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Ret", "RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Rat", "LET", "XOO!")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In a header any step, if a header is not found the contentErrorPriority should be set.") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Ret", "RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("Rat", "LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 10)
    assert (req2.contentErrorPriority == 10)
  }

  test ("In a header any step, if a header exists, but none of the headers match the the regex the urilevel should be set to -1. (Multiple items in a single header)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret, RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat, LET", "XOO!")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In a header any step, if a header exists, but none of the headers match the the regex the contentErrorPriority should be set. (Multiple items in a single header)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret, RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat, LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 10)
    assert (req2.contentErrorPriority == 10)
  }

  test ("In a header any step, if a header exists, but the header does not match the the regex the requst should conatin an Exception") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
  }

  test ("In a header any step, if a header exists, but the header does not match the the regex the requst should conatin an Exception (Custom Code)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In a header any step, if a header exists, but the header does not match the the regex the requst should conatin an Exception (Custom Message)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("..E.."), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("..E.."))
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("..E.."))
    assert (req2.contentErrorCode == 400)
  }

  test ("In a header any step, if a header exists, but the header does not match the the regex the requst should conatin an Exception (Custom Code, Custom Message)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("..E.."), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("..E.."))
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("..E.."))
    assert (req2.contentErrorCode == 401)
  }

  test ("In a header any step, if a header exists, but the header does not match the the regex the requst should conatin an Exception (Custom Code, Custom Message, ContentErrorPriority)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("..E.."), Some(401), 111, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("..E.."))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 111)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("..E.."))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 111)
  }

  test ("In a header any step, if a header exists, but none of the headers match the the regex the requst should conatin an Exception") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret","RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat","LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
  }

  test ("In a header any step, if a header exists, but none of the headers match the the regex the requst should conatin an Exception (Custom Code)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret","RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat","LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In a header any step, if a header exists, but none of the headers match the the regex the requst should conatin an Exception (Custom Message)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("Custom Msg"), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret","RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat","LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    assert (req1.contentError.getMessage.contains("Custom Msg"))
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
    assert (req2.contentError.getMessage.contains("Custom Msg"))
  }

  test ("In a header any step, if a header exists, but none of the headers match the the regex the requst should conatin an Exception (Custom Code, Custom Message)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("Custom Msg"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret","RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat","LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    assert (req1.contentError.getMessage.contains("Custom Msg"))
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
    assert (req2.contentError.getMessage.contains("Custom Msg"))
  }

  test ("In a header any step, if a header exists, but none of the headers match the the regex the requst should conatin an Exception (Custom Code, Custom Message, ContentErrorPriority)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("Custom Msg"), Some(401), 1123, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret","RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat","LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    assert (req1.contentError.getMessage.contains("Custom Msg"))
    assert (req1.contentErrorPriority == 1123)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
    assert (req2.contentError.getMessage.contains("Custom Msg"))
    assert (req2.contentErrorPriority == 1123)
  }

  test ("In a header any step, if the header is not found the request should conatin an Exception") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-HEADER"->List("Ret","RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-HEADER"->List("Rat","LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
  }

  test ("In a header any step, if the header is not found the request should conatin an Exception (Custom Code)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-HEADER"->List("Ret","RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-HEADER"->List("Rat","LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In a header any step, if the header is not found the request should conatin an Exception (Custom Message)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("CMsg"), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-HEADER"->List("Ret","RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-HEADER"->List("Rat","LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("CMsg"))
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("CMsg"))
    assert (req2.contentErrorCode == 400)
  }

  test ("In a header any step, if the header is not found the request should conatin an Exception (Custom Code, Custom Message)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("CMsg"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-HEADER"->List("Ret","RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-HEADER"->List("Rat","LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("CMsg"))
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("CMsg"))
    assert (req2.contentErrorCode == 401)
  }

  test ("In a header any step, if the header is not found the request should conatin an Exception (Custom Code, Custom Message, ContentErrorPriority)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("CMsg"), Some(401), 1234, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-HEADER"->List("Ret","RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-HEADER"->List("Rat","LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("CMsg"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 1234)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("CMsg"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 1234)
  }


  test ("In a header any step, if a header exists, but none of the headers match the the regex the requst should conatin an Exception (Multiple items in a single header)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret, RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat, LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
  }

  test ("In a header any step, if a header exists, but none of the headers match the the regex the requst should conatin an Exception (Multiple items in a single header, Custom Code)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret, RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat, LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In a header any step, if a header exists, but none of the headers match the the regex the requst should conatin an Exception (Multiple items in a single header, Custom Message)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("CMessage"), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret, RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat, LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("CMessage"))
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("CMessage"))
    assert (req2.contentErrorCode == 400)
  }

  test ("In a header any step, if a header exists, but none of the headers match the the regex the requst should conatin an Exception (Multiple items in a single header, Custom Code, Custom Message)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("CMessage"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret, RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat, LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("CMessage"))
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("CMessage"))
    assert (req2.contentErrorCode == 401)
  }

  test ("In a header any step, if a header exists, but none of the headers match the the regex the requst should conatin an Exception (Multiple items in a single header, Custom Code, Custom Message, ContentErrorPriority)") {
    val header = new HeaderAny("HEADER", "HEADER", "X-TEST-HEADER", "S.*".r, Some("CMessage"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Ret, RET", "ZOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("Rat, LET", "XOO!")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("CMessage"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 10)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("CMessage"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 10)
  }

  test ("In an XSD any header step, if the header is available then the uri level should stay the same.") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    assert (header.checkStep (req1, response, chain, 0) == 0)
    assert (header.checkStep (req2, response, chain, 1) == 1)
  }

  test ("In an XSD any header step, if the header is available then the uri level should stay the same in the context.") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    assert (header.checkStep (req1, response, chain, StepContext()).get.uriLevel == 0)
    assert (header.checkStep (req2, response, chain, StepContext(1)).get.uriLevel == 1)
  }

  test ("In an XSD any header step, if the header is available and a capture header is set, the matching values should be caught in the capture header.") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, None, None, Some("Foo"), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353", "FOO", "eab81b2c-b623-11e4-b090-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("BAR", "2fbf4592-e25a-11e1-bae1-93374682bd20", "BAZ", "eab81b2c-b623-11e4-b090-28cfe9213zzz")))

    val ctx1 = header.checkStep (req1, response, chain, StepContext()).get
    val ctx2 = header.checkStep (req2, response, chain, StepContext(1)).get

    assert (ctx1.requestHeaders("Foo")  ==  List("28d42e00-e25a-11e1-9897-efbf2fa68353", "eab81b2c-b623-11e4-b090-28cfe92134e7"))
    assert (ctx2.requestHeaders("Foo")  ==  List("2fbf4592-e25a-11e1-bae1-93374682bd20"))
  }

 test ("In an XSD any header step, if the header is not available then the context should be set to None.") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-I"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-I"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    assert (header.checkStep (req1, response, chain, StepContext()).isEmpty)
    assert (header.checkStep (req2, response, chain, StepContext(1)).isEmpty)
  }

  test ("In an XSD any header step, if the header is available but values do not match then the context should be set to None.") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, None, None, Some("Foo"), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("BAR", "BAZ")))

    assert (header.checkStep (req1, response, chain, StepContext()).isEmpty)
    assert (header.checkStep (req2, response, chain, StepContext(1)).isEmpty)
  }

  test ("In an XSD any header step, if the header is available then the contentErrorPriority should be -1.") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In an XSD any header step, if the header is available then the uri level should stay the same. (Multiple Headers)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353","9457bff8-56d5-11e2-8033-a39a52706e3e")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20","9ce63532-56d5-11e2-a5a1-b3291df67c83")))

    assert (header.checkStep (req1, response, chain, 0) == 0)
    assert (header.checkStep (req2, response, chain, 1) == 1)
  }


  test ("In an XSD any header step, if the header is available then the contentErrorPriority should be -1. (Multiple Headers)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353","9457bff8-56d5-11e2-8033-a39a52706e3e")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20","9ce63532-56d5-11e2-a5a1-b3291df67c83")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In an XSD any header step, if the header is available then the uri level should stay the same. (Multiple Items single header)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353, 9457bff8-56d5-11e2-8033-a39a52706e3e")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20, 9ce63532-56d5-11e2-a5a1-b3291df67c83")))

    assert (header.checkStep (req1, response, chain, 0) == 0)
    assert (header.checkStep (req2, response, chain, 1) == 1)
  }

  test ("In an XSD any header step, if the header is available then the contentErrorPriority should be -1 (Multiple Items single header)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353, 9457bff8-56d5-11e2-8033-a39a52706e3e")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20, 9ce63532-56d5-11e2-a5a1-b3291df67c83")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }


  test ("In an XSD any header step, if the header is available then the uri level should stay the same, even if other headers don't match") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353","9457bff856d5-11e2-8033-a39a52706e3e")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20","9ce6353256d5-11e2-a5a1b3291df67c83")))

    assert (header.checkStep (req1, response, chain, 0) == 0)
    assert (header.checkStep (req2, response, chain, 1) == 1)
  }

  test ("In an XSD any header step, if the header is available then the contentErrorPriority should be -1, even if other headers don't match") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2fa68353","9457bff856d5-11e2-8033-a39a52706e3e")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1-bae1-93374682bd20","9ce6353256d5-11e2-a5a1b3291df67c83")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the uri level should be -1") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the contentErrorPrioirty should be set") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 10)
    assert (req2.contentErrorPriority == 10)
  }


  test ("In an XSD any header step, if the header is available, but the content is not correct, the uri level should be -1 (Multiple headers, all incorrect)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353","foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20","bar")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the content error priority should be set (Multiple headers, all incorrect)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353","foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20","bar")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 10)
    assert (req2.contentErrorPriority == 10)
  }


  test ("In an XSD any header step, if the header is available, but the content is not correct, the uri level should be -1 (Multiple items in single header, all incorrect)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353, foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20, bar")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the content error priority should be set (Multiple items in single header, all incorrect)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 100, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353, foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20, bar")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 100)
    assert (req2.contentErrorPriority == 100)
  }


  test ("In an XSD any header step, if the header is not available, the uri level should be -1") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("509acc44-56d8-11e2-a542-cbb2f2d12c96")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("5be28dda-56d8-11e2-9cf0-4728d4210e49")))

    assert (header.checkStep (req1, response, chain, 0) == -1)
    assert (header.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In an XSD any header step, if the header is not available, the content error priority should be set") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 1001, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("509acc44-56d8-11e2-a542-cbb2f2d12c96")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("5be28dda-56d8-11e2-9cf0-4728d4210e49")))

    header.checkStep (req1, response, chain, 0)
    header.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 1001)
    assert (req2.contentErrorPriority == 1001)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the request should conatin an Exception") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the request should conatin an Exception (Custom Code)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the request should conatin an Exception (Custom Message)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Custom Message"), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Custom Message"))
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Custom Message"))
    assert (req2.contentErrorCode == 400)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the request should conatin an Exception (Custom Code, Custom Message)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Custom Message"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Custom Message"))
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Custom Message"))
    assert (req2.contentErrorCode == 401)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the request should conatin an Exception (Custom Code, Custom Message, ContentErrorPriority)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Custom Message"), Some(401), 8675309, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Custom Message"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 8675309)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Custom Message"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 8675309)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the request should conatin an Exception (multiple headers all incorrect)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353", "foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20", "bar")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the request should conatin an Exception (multiple headers all incorrect, Custom Code)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353", "foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20", "bar")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the request should conatin an Exception (multiple headers all incorrect, Custom Message)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Custom Message"), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353", "foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20", "bar")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Custom Message"))
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Custom Message"))
    assert (req2.contentErrorCode == 400)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the request should conatin an Exception (multiple headers all incorrect, Custom Code, Custom Message)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Custom Message"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353", "foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20", "bar")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Custom Message"))
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Custom Message"))
    assert (req2.contentErrorCode == 401)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the request should conatin an Exception (multiple headers all incorrect, Custom Code, Custom Message, Content Error Priority)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Custom Message"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353", "foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20", "bar")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Custom Message"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 10)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Custom Message"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 10)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the request should conatin an Exception (multiple items, all incorrect)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353, foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20, bar")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the request should conatin an Exception (multiple items, all incorrect, Custom Code)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353, foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20, bar")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the request should conatin an Exception (multiple items, all incorrect, Custom Message)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Custom Message"), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353, foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20, bar")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Custom Message"))
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Custom Message"))
    assert (req2.contentErrorCode == 400)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the request should conatin an Exception (multiple items, all incorrect, Custom Code, Custom Message)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Custom Message"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353, foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20, bar")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Custom Message"))
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Custom Message"))
    assert (req2.contentErrorCode == 401)
  }

  test ("In an XSD any header step, if the header is available, but the content is not correct, the request should conatin an Exception (multiple items, all incorrect, Custom Code, Custom Message, Priority)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Custom Message"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353, foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20, bar")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Custom Message"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 10)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Custom Message"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 10)
  }

  test ("In an XSD any header step, if the header not available, the request should conatin an Exception") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 400)
  }

  test ("In an XSD any header step, if the header not available, the request should conatin an Exception (Custom Code)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, None, Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentErrorCode == 401)
  }

  test ("In an XSD any header step, if the header not available, the request should conatin an Exception (Custom Message)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Custom Message"), None, 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Custom Message"))
    assert (req1.contentErrorCode == 400)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Custom Message"))
    assert (req2.contentErrorCode == 400)
  }

  test ("In an XSD any header step, if the header not available, the request should conatin an Exception (Custom Code, Custom Message)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Custom Message"), Some(401), 10, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Custom Message"))
    assert (req1.contentErrorCode == 401)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Custom Message"))
    assert (req2.contentErrorCode == 401)
  }

  test ("In an XSD any header step, if the header not available, the request should conatin an Exception (Custom Code, Custom Message, Content Error Priority)") {
    val header = new HeaderXSDAny("HEADER", "HEADER", "X-ID", uuidType, testSchema, Some("Custom Message"), Some(401), 101, Array[Step]())
    val req1 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("28d42e00-e25a-11e1-9897-efbf2Za68353")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("ID"->List("2fbf4592-e25a-11e1bae1-93374682bd20")))

    header.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Custom Message"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 101)
    header.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Custom Message"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 101)
  }

  test ("In a set header step, if a header does not exist in context and in the request the value should be set (No Request Headers, No Context Headers)") {
    val setHeader = new SetHeader("SET_HEADER", "SET_HEADER", "X-TEST", "A_VALUE", Array[Step]())
    val req = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    var context = StepContext()
    val newContext = setHeader.checkStep(req, response, chain, context)

    assert (newContext != None, "A context should be returned")
    assert (newContext.get.requestHeaders.getOrElse("X-TEST", List[String]()) == List("A_VALUE"),"The header should be set in the context with the correct value")
  }

  test ("In a set header step, if a header does not exist in context and in the request the value should be set (Mismatch Request Header)") {
    val setHeader = new SetHeader("SET_HEADER", "SET_HEADER", "X-TEST", "A_VALUE", Array[Step]())
    val req = request("GET", "/path/to/resource", "", "", false, Map("OTHER"->List("FOO")))
    var context = StepContext()
    val newContext = setHeader.checkStep(req, response, chain, context)

    assert (newContext != None, "A context should be returned")
    assert (newContext.get.requestHeaders.getOrElse("X-TEST", List[String]()) == List("A_VALUE"),"The header should be set in the context with the correct value")
  }

  test ("In a set header step, if a header does not exist in context and in the request the value should be set (Mismatch Context Header)") {
    val setHeader = new SetHeader("SET_HEADER", "SET_HEADER", "X-TEST", "A_VALUE", Array[Step]())
    val req = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    var context = StepContext(requestHeaders = (new HeaderMap).addHeader("OTHER","FOO"))
    val newContext = setHeader.checkStep(req, response, chain, context)

    assert (newContext != None, "A context should be returned")
    assert (newContext.get.requestHeaders.getOrElse("X-TEST", List[String]()) == List("A_VALUE"),"The header should be set in the context with the correct value")
  }

  test ("In a set header step, if a header does not exist in context and in the request the value should be set (Mismatch Context and Request Header)") {
    val setHeader = new SetHeader("SET_HEADER", "SET_HEADER", "X-TEST", "A_VALUE", Array[Step]())
    val req = request("GET", "/path/to/resource", "", "", false, Map("OTHER2"->List("FOO")))
    var context = StepContext(requestHeaders = (new HeaderMap).addHeader("OTHER","FOO"))
    val newContext = setHeader.checkStep(req, response, chain, context)

    assert (newContext != None, "A context should be returned")
    assert (newContext.get.requestHeaders.getOrElse("X-TEST", List[String]()) == List("A_VALUE"),"The header should be set in the context with the correct value")
  }

  test ("In a set header step, if a header does exist in the request but not in the context the vaule should not be set") {
    val setHeader = new SetHeader("SET_HEADER", "SET_HEADER", "X-TEST", "A_VALUE", Array[Step]())
    val req = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))
    var context = StepContext()
    val newContext = setHeader.checkStep(req, response, chain, context)

    assert (newContext != None, "A context should be returned")
    assert (newContext.get.requestHeaders.get("X-TEST") == None,"The header should be set in the context with the correct value")
  }

  test ("In a set header step, if a header does not exist in the request, but does exist in the context the value should not be set") {
    val setHeader = new SetHeader("SET_HEADER", "SET_HEADER", "X-TEST", "A_VALUE", Array[Step]())
    val req = request("GET", "/path/to/resource", "", "", false, Map("OTHER"->List("FOO")))
    var context = StepContext(requestHeaders = (new HeaderMap).addHeader("X-TEST", "BAR"))
    val newContext = setHeader.checkStep(req, response, chain, context)

    assert (newContext != None, "A context should be returned")
    assert (newContext.get.requestHeaders.get("X-TEST").get == List("BAR"),"The header should be set in the context with the correct value")
  }

  test ("In a set header step, if a header does exist in the request and it exists in the context the value should not be set") {
    val setHeader = new SetHeader("SET_HEADER", "SET_HEADER", "X-TEST", "A_VALUE", Array[Step]())
    val req = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))
    var context = StepContext(requestHeaders = (new HeaderMap).addHeader("X-TEST", "BAR"))
    val newContext = setHeader.checkStep(req, response, chain, context)

    assert (newContext != None, "A context should be returned")
    assert (newContext.get.requestHeaders.get("X-TEST").get == List("BAR"),"The header should be set in the context with the correct value")
  }

  test ("In a set header step, if a header does exist in the request and contains multiple values but not in the context the vaule should not be set") {
    val setHeader = new SetHeader("SET_HEADER", "SET_HEADER", "X-TEST", "A_VALUE", Array[Step]())
    val req = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO", "BAR")))
    var context = StepContext()
    val newContext = setHeader.checkStep(req, response, chain, context)

    assert (newContext != None, "A context should be returned")
    assert (newContext.get.requestHeaders.get("X-TEST") == None,"The header should be set in the context with the correct value")
  }

  test ("In a set header step, if a header does not exist in the request, but does exist in the context and contains multiple values the value should not be set") {
    val setHeader = new SetHeader("SET_HEADER", "SET_HEADER", "X-TEST", "A_VALUE", Array[Step]())
    val req = request("GET", "/path/to/resource", "", "", false, Map("OTHER"->List("FOO")))
    var context = StepContext(requestHeaders = (new HeaderMap).addHeaders("X-TEST", List("FOO", "BAR")))
    val newContext = setHeader.checkStep(req, response, chain, context)

    assert (newContext != None, "A context should be returned")
    assert (newContext.get.requestHeaders.get("X-TEST").get == List("FOO", "BAR"),"The header should be set in the context with the correct value")
  }

  test ("In a set header step, if a header does exist in the request AND it exists in the context and they both contain multiple values the value should not be set") {
    val setHeader = new SetHeader("SET_HEADER", "SET_HEADER", "X-TEST", "A_VALUE", Array[Step]())
    val req = request("GET", "/path/to/resource", "", "", false, Map("OTHER"->List("FOO", "BAR")))
    var context = StepContext(requestHeaders = (new HeaderMap).addHeaders("X-TEST", List("FOO", "BAR")))
    val newContext = setHeader.checkStep(req, response, chain, context)

    assert (newContext != None, "A context should be returned")
    assert (newContext.get.requestHeaders.get("X-TEST").get == List("FOO", "BAR"),"The header should be set in the context with the correct value")
  }

  test ("In a step header always step, if a header does not exist in the context it will be set when the step executes") {
    val setHeaderAlways = new SetHeaderAlways("SET_HEADER_ALWAYS", "SET_HEADER_AWLAYS", "foo", "bar", Array[Step]())
    val req = request("GET", "/path/to/resource", "", "", false, Map("OTHER"->List("FOOD", "BART")))
    val context = StepContext()
    val newContext = setHeaderAlways.checkStep(req, response, chain, context)
    assert (newContext.get.requestHeaders.get("foo").get == List ("bar"), "The set header always header should be set.")
  }

  test ("In a step header always step, if a header exist in the context a new value should  when the step executes") {
    val setHeaderAlways = new SetHeaderAlways("SET_HEADER_ALWAYS", "SET_HEADER_AWLAYS", "foo", "bar", Array[Step]())
    val req = request("GET", "/path/to/resource", "", "", false, Map("OTHER"->List("FOOD", "BART")))
    val context = StepContext(requestHeaders = (new HeaderMap).addHeaders("foo", List("broo")))
    val newContext = setHeaderAlways.checkStep(req, response, chain, context)
    assert (newContext.get.requestHeaders.get("foo").get == List ("broo","bar"), "The set header always header should be set.")
  }

  test ("In a step header always step, if a header exist in the context a new value should  when the step executes (case)") {
    val setHeaderAlways = new SetHeaderAlways("SET_HEADER_ALWAYS", "SET_HEADER_AWLAYS", "foo", "bar", Array[Step]())
    val req = request("GET", "/path/to/resource", "", "", false, Map("OTHER"->List("FOOD", "BART")))
    val context = StepContext(requestHeaders = (new HeaderMap).addHeaders("fOO", List("broo")))
    val newContext = setHeaderAlways.checkStep(req, response, chain, context)
    assert (newContext.get.requestHeaders.get("foo").get == List ("broo","bar"), "The set header always header should be set.")
  }

  test ("In a headerAll step, with a single RegEx value if the header is not avaliable the result of checkStep should be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r),  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerAll step, with a single RegEx value , if the header is not available a correct error message should be set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r),  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("foo"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("foo"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single RegEx value , if the header is not available and a custom message is set, make sure the message is reflected") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r),  Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single RegEx value , if the header is not available and a custom error code is set, then the error code should be retuned") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r),  None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("foo"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("foo"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single RegEx value , if the header is not available and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r),  Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single RegEx value , if the header value does not match the result of checkStep should be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r),  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerAll step, with a single RegEx value , if the header value does not match a correct error message should be set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r),  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("foo"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("foo"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single RegEx value , if the header vaule does not match and a custom message is set, make sure the message is reflected") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r),  Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single RegEx value , if the header value does not match a custom error code is set, then the error code should be retuned") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r),  None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("foo"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("foo"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single RegEx value , if the header value does not match and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r),  Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single RegEx value , if the header value contains multiple values and some do not match then checkStep should return None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "goo")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerAll step, with a single RegEx value , if the header contains multiple values and some do not match a correct error message should be set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("did not match"))
    assert (req1.contentError.getMessage.contains("FOO"))
    assert (req1.contentError.getMessage.contains("BAR"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("did not match"))
    assert (req2.contentError.getMessage.contains("goo"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single RegEx value , if the header contains multiple values and some do not match and a custom message is set, make sure the message is reflected") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r), Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single RegEx value , if the header contains multiple values and some do not match and a custom error code is set, then the error code should be retuned") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r), None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("did not match"))
    assert (req1.contentError.getMessage.contains("FOO"))
    assert (req1.contentError.getMessage.contains("BAR"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("did not match"))
    assert (req2.contentError.getMessage.contains("goo"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single RegEx value , if the header contians multiple values and some do not match and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r), Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single RegEx value , if the header contians a single valid value, then the return context should not be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("foo")))

    assert (header.checkStep(req1, response, chain, StepContext()) != None)
    assert (header.checkStep(req2, response, chain, StepContext()) != None)
  }

  test ("In a headerAll step, with a single RegEx value , if the header contians a single valid value, then the return context should not have additional headers set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("foo")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.isEmpty)
    assert (ctx2.get.requestHeaders.isEmpty)
  }


  test ("In a headerAll step, with a single RegEx value , if the header contians multiple valid values, then the return context should not be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo","foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("foo","foo","foo")))

    assert (header.checkStep(req1, response, chain, StepContext()) != None)
    assert (header.checkStep(req2, response, chain, StepContext()) != None)
  }

  test ("In a headerAll step, with a single RegEx value , if the header contians a multiple valid values, then the return context should not have additional headers set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo", "foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("foo", "foo", "foo")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.isEmpty)
    assert (ctx2.get.requestHeaders.isEmpty)
  }


  test("In a headerAll step, with a single RegEx value , if the header contains a valid value and a capture header is specified then it should be set"){
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("foo".r), None, None, Some("X-TEST-COPY"), 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("foo")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("foo")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.get("X-TEST-COPY").get == List("foo"))
    assert (ctx2.get.requestHeaders.get("X-TEST-COPY").get == List("foo"))
  }

  test ("In a headerAll step, with a single RegEx value , if the header contians a single or multiple valid value, then the return context should not be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("f.*".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("fun")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("function","future", "furthermore")))

    assert (header.checkStep(req1, response, chain, StepContext()) != None)
    assert (header.checkStep(req2, response, chain, StepContext()) != None)
  }

  test ("In a headerAll step, with a single RegEx value , if the header contians a single or multiple  valid value, then the return context should not have additional headers set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("f.*".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("fun")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("function","future", "furthermore")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.isEmpty)
    assert (ctx2.get.requestHeaders.isEmpty)
  }

  test("In a headerAll step, with a single RegEx value , if the header contains a valid value or multiple values and a capture header is specified then it should be set"){
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", None, None, Some("f.*".r), None, None, Some("X-TEST-COPY"), 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("fun")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("function", "future", "furthermore")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.get("X-TEST-COPY").get == List("fun"))
    assert (ctx2.get.requestHeaders.get("X-TEST-COPY").get == List("function", "future", "furthermore"))
  }

  test ("In a headerAll step, with a single XSD value if the header is not avaliable the result of checkStep should be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None,  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerAll step, with a single XSD value , if the header is not available a correct error message should be set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None,  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single XSD value , if the header is not available and a custom message is set, make sure the message is reflected") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None,  Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single XSD value , if the header is not available and a custom error code is set, then the error code should be retuned") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None,  None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single XSD value , if the header is not available and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None,  Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single XSD value , if the header value does not match the result of checkStep should be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None,  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerAll step, with a single XSD value , if the header value does not match a correct error message should be set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None,  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single XSD value , if the header vaule does not match and a custom message is set, make sure the message is reflected") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None,  Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single XSD value , if the header value does not match a custom error code is set, then the error code should be retuned") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None,  None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single XSD value , if the header value does not match and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None,  Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single XSD value , if the header value contains multiple values and some do not match then checkStep should return None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "goo")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerAll step, with a single XSD value , if the header contains multiple values and some do not match a correct error message should be set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("did not match"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("BAR"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("did not match"))
    assert (req2.contentError.getMessage.contains("goo"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single XSD value , if the header contains multiple values and some do not match and a custom message is set, make sure the message is reflected") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None, Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single XSD value , if the header contains multiple values and some do not match and a custom error code is set, then the error code should be retuned") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None, None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("did not match"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("BAR"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("did not match"))
    assert (req2.contentError.getMessage.contains("goo"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single XSD value , if the header contians multiple values and some do not match and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None, Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a single XSD value , if the header contians a single valid value, then the return context should not be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7")))

    assert (header.checkStep(req1, response, chain, StepContext()) != None)
    assert (header.checkStep(req2, response, chain, StepContext()) != None)
  }

  test ("In a headerAll step, with a single XSD value , if the header contians a single valid value, then the return context should not have additional headers set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.isEmpty)
    assert (ctx2.get.requestHeaders.isEmpty)
  }


  test ("In a headerAll step, with a single XSD value , if the header contians multiple valid values, then the return context should not be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7","c411271a-1ba9-11e6-acd4-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7","e6d9d404-1ba9-11e6-874f-28cfe92134e7","26171654-1baa-11e6-9c78-28cfe92134e7")))

    assert (header.checkStep(req1, response, chain, StepContext()) != None)
    assert (header.checkStep(req2, response, chain, StepContext()) != None)
  }

  test ("In a headerAll step, with a single XSD value , if the header contians a multiple valid values, then the return context should not have additional headers set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "c411271a-1ba9-11e6-acd4-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "e6d9d404-1ba9-11e6-874f-28cfe92134e7", "26171654-1baa-11e6-9c78-28cfe92134e7")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.isEmpty)
    assert (ctx2.get.requestHeaders.isEmpty)
  }


  test("In a headerAll step, with a single XSD value , if the header contains a valid value and a capture header is specified then it should be set"){
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None, None, None, Some("X-TEST-COPY"), 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("e6d9d404-1ba9-11e6-874f-28cfe92134e7")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.get("X-TEST-COPY").get == List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7"))
    assert (ctx2.get.requestHeaders.get("X-TEST-COPY").get == List("e6d9d404-1ba9-11e6-874f-28cfe92134e7"))
  }

  test ("In a headerAll step, with a single XSD value , if the header contians a single or multiple valid value, then the return context should not be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("26171654-1baa-11e6-9c78-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("678569ec-1baa-11e6-9fd0-28cfe92134e7","77efe348-1baa-11e6-8354-28cfe92134e7", "87bac87e-1baa-11e6-9219-28cfe92134e7")))

    assert (header.checkStep(req1, response, chain, StepContext()) != None)
    assert (header.checkStep(req2, response, chain, StepContext()) != None)
  }

  test ("In a headerAll step, with a single XSD value , if the header contians a single or multiple  valid value, then the return context should not have additional headers set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("26171654-1baa-11e6-9c78-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("678569ec-1baa-11e6-9fd0-28cfe92134e7","77efe348-1baa-11e6-8354-28cfe92134e7", "87bac87e-1baa-11e6-9219-28cfe92134e7")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.isEmpty)
    assert (ctx2.get.requestHeaders.isEmpty)
  }

  test("In a headerAll step, with a single XSD value , if the header contains a valid value or multiple values and a capture header is specified then it should be set"){
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType)), Some(testSchema), None, None, None, Some("X-TEST-COPY"), 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("26171654-1baa-11e6-9c78-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("678569ec-1baa-11e6-9fd0-28cfe92134e7", "77efe348-1baa-11e6-8354-28cfe92134e7", "87bac87e-1baa-11e6-9219-28cfe92134e7")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.get("X-TEST-COPY").get == List("26171654-1baa-11e6-9c78-28cfe92134e7"))
    assert (ctx2.get.requestHeaders.get("X-TEST-COPY").get == List("678569ec-1baa-11e6-9fd0-28cfe92134e7", "77efe348-1baa-11e6-8354-28cfe92134e7", "87bac87e-1baa-11e6-9219-28cfe92134e7"))
  }

  test ("In a headerAll step, with a multiple XSDs value if the header is not avaliable the result of checkStep should be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None,  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header is not available a correct error message should be set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None,  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header is not available and a custom message is set, make sure the message is reflected") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None,  Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header is not available and a custom error code is set, then the error code should be retuned") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None,  None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header is not available and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None,  Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header value does not match the result of checkStep should be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None,  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header value does not match a correct error message should be set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None,  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header vaule does not match and a custom message is set, make sure the message is reflected") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None,  Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header value does not match a custom error code is set, then the error code should be retuned") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None,  None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header value does not match and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None,  Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header value contains multiple values and some do not match then checkStep should return None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "goo")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header contains multiple values and some do not match a correct error message should be set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("did not match"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req1.contentError.getMessage.contains("BAR"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("did not match"))
    assert (req2.contentError.getMessage.contains("goo"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header contains multiple values and some do not match and a custom message is set, make sure the message is reflected") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None, Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header contains multiple values and some do not match and a custom error code is set, then the error code should be retuned") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None, None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("did not match"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req1.contentError.getMessage.contains("BAR"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("did not match"))
    assert (req2.contentError.getMessage.contains("goo"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header contians multiple values and some do not match and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None, Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header contians a single valid value, then the return context should not be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7")))

    assert (header.checkStep(req1, response, chain, StepContext()) != None)
    assert (header.checkStep(req2, response, chain, StepContext()) != None)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header contians a single valid value, then the return context should not have additional headers set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.isEmpty)
    assert (ctx2.get.requestHeaders.isEmpty)
  }


  test ("In a headerAll step, with a multiple XSDs value , if the header contians multiple valid values, then the return context should not be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7","c411271a-1ba9-11e6-acd4-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7","e6d9d404-1ba9-11e6-874f-28cfe92134e7","26171654-1baa-11e6-9c78-28cfe92134e7")))

    assert (header.checkStep(req1, response, chain, StepContext()) != None)
    assert (header.checkStep(req2, response, chain, StepContext()) != None)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header contians a multiple valid values, then the return context should not have additional headers set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START", "c411271a-1ba9-11e6-acd4-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "e6d9d404-1ba9-11e6-874f-28cfe92134e7", "ACCEPT")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.isEmpty)
    assert (ctx2.get.requestHeaders.isEmpty)
  }


  test("In a headerAll step, with a multiple XSDs value , if the header contains a valid value and a capture header is specified then it should be set"){
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None, None, None, Some("X-TEST-COPY"), 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("e6d9d404-1ba9-11e6-874f-28cfe92134e7")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.get("X-TEST-COPY").get == List("START"))
    assert (ctx2.get.requestHeaders.get("X-TEST-COPY").get == List("e6d9d404-1ba9-11e6-874f-28cfe92134e7"))
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header contians a single or multiple valid value, then the return context should not be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("26171654-1baa-11e6-9c78-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("678569ec-1baa-11e6-9fd0-28cfe92134e7","ACCEPT", "87bac87e-1baa-11e6-9219-28cfe92134e7")))

    assert (header.checkStep(req1, response, chain, StepContext()) != None)
    assert (header.checkStep(req2, response, chain, StepContext()) != None)
  }

  test ("In a headerAll step, with a multiple XSDs value , if the header contians a single or multiple  valid value, then the return context should not have additional headers set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None, None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("26171654-1baa-11e6-9c78-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("678569ec-1baa-11e6-9fd0-28cfe92134e7","ACCEPT", "87bac87e-1baa-11e6-9219-28cfe92134e7")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.isEmpty)
    assert (ctx2.get.requestHeaders.isEmpty)
  }

  test("In a headerAll step, with a multiple XSDs value , if the header contains a valid value or multiple values and a capture header is specified then it should be set"){
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), None, None, None, Some("X-TEST-COPY"), 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("26171654-1baa-11e6-9c78-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("678569ec-1baa-11e6-9fd0-28cfe92134e7", "ACCEPT", "87bac87e-1baa-11e6-9219-28cfe92134e7")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.get("X-TEST-COPY").get == List("26171654-1baa-11e6-9c78-28cfe92134e7"))
    assert (ctx2.get.requestHeaders.get("X-TEST-COPY").get == List("678569ec-1baa-11e6-9fd0-28cfe92134e7", "ACCEPT", "87bac87e-1baa-11e6-9219-28cfe92134e7"))
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value if the header is not avaliable the result of checkStep should be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r),  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header is not available a correct error message should be set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r),  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header is not available and a custom message is set, make sure the message is reflected") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r),  Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header is not available and a custom error code is set, then the error code should be retuned") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r),  None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header is not available and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r),  Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map[String, List[String]]())
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST"->List("FOO")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header value does not match the result of checkStep should be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r),  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header value does not match a correct error message should be set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r),  None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header vaule does not match and a custom message is set, make sure the message is reflected") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r),  Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header value does not match a custom error code is set, then the error code should be retuned") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r),  None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header value does not match and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r),  Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("FOO")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header value contains multiple values and some do not match then checkStep should return None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START", "foo", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "goo")))

    assert (header.checkStep(req1, response, chain, StepContext()) == None)
    assert (header.checkStep(req2, response, chain, StepContext()) == None)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header contains multiple values and some do not match a correct error message should be set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START", "foo", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("did not match"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req1.contentError.getMessage.contains("BAR"))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("did not match"))
    assert (req2.contentError.getMessage.contains("goo"))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header contains multiple values and some do not match and a custom message is set, make sure the message is reflected") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r), Some("Error! :-("), None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START", "foo", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 400)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 400)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header contains multiple values and some do not match and a custom error code is set, then the error code should be retuned") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r), None, Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START", "foo", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("xpecting"))
    assert (req1.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req1.contentError.getMessage.contains("did not match"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}UUID"))
    assert (req1.contentError.getMessage.contains("{http://www.rackspace.com/repose/wadl/checker/step/test}StepType"))
    assert (req1.contentError.getMessage.contains("BAR"))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("xpecting"))
    assert (req2.contentError.getMessage.contains("X-TEST-HEADER"))
    assert (req2.contentError.getMessage.contains("did not match"))
    assert (req2.contentError.getMessage.contains("goo"))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header contians multiple values and some do not match and a custom error code and message is set, then then these should be returend") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r), Some("Error! :-("), Some(403), None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START", "foo", "BAR")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "goo")))

    header.checkStep(req1, response, chain, StepContext())
    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[Exception])
    assert (req1.contentError.getMessage.contains("Error! :-("))
    assert (req1.contentErrorCode == 403)
    assert (req1.contentErrorPriority == 12345)
    header.checkStep(req2, response, chain, StepContext())
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[Exception])
    assert (req2.contentError.getMessage.contains("Error! :-("))
    assert (req2.contentErrorCode == 403)
    assert (req2.contentErrorPriority == 12345)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header contians a single valid value, then the return context should not be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7")))
    val req3 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("foo")))

    assert (header.checkStep(req1, response, chain, StepContext()) != None)
    assert (header.checkStep(req2, response, chain, StepContext()) != None)
    assert (header.checkStep(req3, response, chain, StepContext()) != None)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header contians a single valid value, then the return context should not have additional headers set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7")))
    val req3 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("foo")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())
    val ctx3 = header.checkStep(req3, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.isEmpty)
    assert (ctx2.get.requestHeaders.isEmpty)
    assert (ctx3.get.requestHeaders.isEmpty)
  }


  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header contians multiple valid values, then the return context should not be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START","c411271a-1ba9-11e6-acd4-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7","foo","ACCEPT")))

    assert (header.checkStep(req1, response, chain, StepContext()) != None)
    assert (header.checkStep(req2, response, chain, StepContext()) != None)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header contians a multiple valid values, then the return context should not have additional headers set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START", "c411271a-1ba9-11e6-acd4-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("4f8d8f24-1ba8-11e6-a581-28cfe92134e7", "foo", "ACCEPT")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.isEmpty)
    assert (ctx2.get.requestHeaders.isEmpty)
  }


  test("In a headerAll step, with a multiple XSDs and a RegEx value , if the header contains a valid value and a capture header is specified then it should be set"){
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r), None, None, Some("X-TEST-COPY"), 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("START")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("e6d9d404-1ba9-11e6-874f-28cfe92134e7")))
    val req3 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("foo")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())
    val ctx3 = header.checkStep(req3, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.get("X-TEST-COPY").get == List("START"))
    assert (ctx2.get.requestHeaders.get("X-TEST-COPY").get == List("e6d9d404-1ba9-11e6-874f-28cfe92134e7"))
    assert (ctx3.get.requestHeaders.get("X-TEST-COPY").get == List("foo"))
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header contians a single or multiple valid value, then the return context should not be None") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("26171654-1baa-11e6-9c78-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("678569ec-1baa-11e6-9fd0-28cfe92134e7","ACCEPT", "foo")))

    assert (header.checkStep(req1, response, chain, StepContext()) != None)
    assert (header.checkStep(req2, response, chain, StepContext()) != None)
  }

  test ("In a headerAll step, with a multiple XSDs and a RegEx value , if the header contians a single or multiple  valid value, then the return context should not have additional headers set") {
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r), None, None, None, 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("26171654-1baa-11e6-9c78-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("678569ec-1baa-11e6-9fd0-28cfe92134e7","ACCEPT", "foo")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.isEmpty)
    assert (ctx2.get.requestHeaders.isEmpty)
  }

  test("In a headerAll step, with a multiple XSDs and a RegEx value , if the header contains a valid value or multiple values and a capture header is specified then it should be set"){
    val header = new HeaderAll("HEADER_ALL", "HEADER_ALL", "X-TEST-HEADER", Some(List(uuidType, stepType)), Some(testSchema), Some("foo".r), None, None, Some("X-TEST-COPY"), 12345, Array[Step]())

    val req1 = request("GET", "/path/to/resource", "", "", false, Map("X-TEST-HEADER"->List("26171654-1baa-11e6-9c78-28cfe92134e7")))
    val req2 = request("GET", "/path/to/resource", "", "", false, Map("X-test-HEADER"->List("678569ec-1baa-11e6-9fd0-28cfe92134e7", "ACCEPT", "foo")))

    val ctx1 = header.checkStep(req1, response, chain, StepContext())
    val ctx2 = header.checkStep(req2, response, chain, StepContext())

    assert (ctx1.get.requestHeaders.get("X-TEST-COPY").get == List("26171654-1baa-11e6-9c78-28cfe92134e7"))
    assert (ctx2.get.requestHeaders.get("X-TEST-COPY").get == List("678569ec-1baa-11e6-9fd0-28cfe92134e7", "ACCEPT", "foo"))
  }

  test ("In a JSON Schema test, if the content contains valid JSON, the uriLevel should stay the same.") {
    val jsonSchema = new JSONSchema("JSONSchema","JSONSchema", testJSONSchema, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : "Jorge",
         "lastName" : "Williams",
         "age" : 38
    }
                        """, true)
    val req2 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : "Rachel",
         "lastName" : "Kraft",
         "age" : 32
    }
                        """, true)
    assert(jsonSchema.checkStep (req1, response, chain, 0) == 0)
    assert(jsonSchema.checkStep (req2, response, chain, 1) == 1)
  }

  test ("In a JSON Schema test, if the content contains valid JSON, the contentErrorPrirority should be -1.") {
    val jsonSchema = new JSONSchema("JSONSchema","JSONSchema", testJSONSchema, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : "Jorge",
         "lastName" : "Williams",
         "age" : 38
    }
                        """, true)
    val req2 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : "Rachel",
         "lastName" : "Kraft",
         "age" : 32
    }
                        """, true)
    jsonSchema.checkStep (req1, response, chain, 0)
    jsonSchema.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In a JSON Schema test, if the content contains invalid JSON, the uriLevel should be -1") {
    val jsonSchema = new JSONSchema("JSONSchema","JSONSchema", testJSONSchema, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : "Jorge",
         "lastName" : "Williams",
         "age" : "38"
    }
                        """, true)
    val req2 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : false,
         "lastName" : "Kraft",
         "age" : 32
    }
                        """, true)
    assert(jsonSchema.checkStep (req1, response, chain, 0) == -1)
    assert(jsonSchema.checkStep (req2, response, chain, 1) == -1)
  }

  test ("In a JSON Schema test, if the content contains invalid JSON, the contentErrorPriority should be set") {
    val jsonSchema = new JSONSchema("JSONSchema","JSONSchema", testJSONSchema, 100, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : "Jorge",
         "lastName" : "Williams",
         "age" : "38"
    }
                        """, true)
    val req2 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : false,
         "lastName" : "Kraft",
         "age" : 32
    }
                        """, true)
    jsonSchema.checkStep (req1, response, chain, 0)
    jsonSchema.checkStep (req2, response, chain, 1)
    assert (req1.contentErrorPriority == 100)
    assert (req2.contentErrorPriority == 100)
  }


  test ("In a JSON Schema test, if the content contains invalid JSON, the request should conatain a ProcessingException") {
    val jsonSchema = new JSONSchema("JSONSchema","JSONSchema", testJSONSchema, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : "Jorge",
         "lastName" : "Williams",
         "age" : "38"
    }
                        """, true)
    val req2 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : false,
         "lastName" : "Kraft",
         "age" : 32
    }
                        """, true)
    jsonSchema.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.getCause.isInstanceOf[ProcessingException])
    jsonSchema.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.getCause.isInstanceOf[ProcessingException])
  }

  test ("In a JSON Schema test, if the content contains invalid JSON, error messages should be concise") {
    val jsonSchema = new JSONSchema("JSONSchema","JSONSchema", testJSONSchema, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : "Jorge",
         "lastName" : "Williams",
         "age" : "38"
    }
                        """, true)
    val req2 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : false,
         "lastName" : "Kraft",
         "age" : 32
    }
                        """, true)
    jsonSchema.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (!req1.contentError.getMessage.contains("\n"))
    jsonSchema.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (!req2.contentError.getMessage.contains("\n"))
  }

  test ("In a JSON Schema test, if the content contains invalid JSON, the request should conatain a ProcessingException which refernces the invalid attribute") {
    val jsonSchema = new JSONSchema("JSONSchema","JSONSchema", testJSONSchema, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : "Jorge",
         "lastName" : "Williams",
         "age" : "38"
    }
                        """, true)
    val req2 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : false,
         "lastName" : "Kraft",
         "age" : 32
    }
                        """, true)
    jsonSchema.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.getCause.isInstanceOf[ProcessingException])
    assert (req1.contentError.getMessage.contains("/age"))
    jsonSchema.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.getCause.isInstanceOf[ProcessingException])
    assert (req2.contentError.getMessage.contains("/firstName"))
  }

  test ("In a JSON Schema test, if the content contains invalid JSON, that references an invalid attribute, the error message must be concise") {
    val jsonSchema = new JSONSchema("JSONSchema","JSONSchema", testJSONSchema, 10, Array[Step]())
    val req1 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : "Jorge",
         "lastName" : "Williams",
         "age" : "38"
    }
                        """, true)
    val req2 = request ("PUT", "/a/b", "application/json", """
    {
         "firstName" : false,
         "lastName" : "Kraft",
         "age" : 32
    }
                        """, true)
    jsonSchema.checkStep (req1, response, chain, 0)
    assert (req1.contentError != null)
    assert (req1.contentError.getCause.isInstanceOf[ProcessingException])
    assert (!req1.contentError.getMessage.contains("\n"))
    jsonSchema.checkStep (req2, response, chain, 1)
    assert (req2.contentError != null)
    assert (req2.contentError.getCause.isInstanceOf[ProcessingException])
    assert (!req2.contentError.getMessage.contains("\n"))
  }

  test ("In an Assert test if an XPath validates then a step context should be returned (checking method)") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val assertStep = new Assert("Assert","Assert", "$req:method = 'GET'", None, None, nsContext, 31, 10, Array[Step]())
    val req1 = request("GET", "/a/b")
    val req2 = request("GET", "/a/c")

    assert(assertStep.checkStep(req1, response, chain, StepContext()).isDefined)
    assert(assertStep.checkStep(req2, response, chain, StepContext(1)).isDefined)
  }

  test ("In an Assert test if an XPath validates then a the content error priorisy should be -1") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val assertStep = new Assert("Assert","Assert", "$req:method = 'GET'", None, None, nsContext, 31, 10, Array[Step]())
    val req1 = request("GET", "/a/b")
    val req2 = request("GET", "/a/c")

    assertStep.checkStep(req1, response, chain, StepContext())
    assertStep.checkStep(req2, response, chain, StepContext(1))

    assert (req1.contentErrorPriority == -1)
    assert (req2.contentErrorPriority == -1)
  }

  test ("In an Assert test if an XPath does not validates then a step context should be empty (checking method)") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val assertStep = new Assert("Assert","Assert", "$req:method = 'GET'", None, None, nsContext, 31, 10, Array[Step]())
    val req1 = request("DELETE", "/a/b")
    val req2 = request("PUT", "/a/c")

    assert(assertStep.checkStep(req1, response, chain, StepContext()).isEmpty)
    assert(assertStep.checkStep(req2, response, chain, StepContext(1)).isEmpty)
  }

  test ("In an Assert test if an XPath does not validates then the error priority should be set (checking method)") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val assertStep = new Assert("Assert","Assert", "$req:method = 'GET'", None, None, nsContext, 31, 10, Array[Step]())
    val req1 = request("DELETE", "/a/b")
    val req2 = request("PUT", "/a/c")

    assertStep.checkStep(req1, response, chain, StepContext())
    assertStep.checkStep(req2, response, chain, StepContext(1))

    assert (req1.contentErrorPriority == 10)
    assert (req2.contentErrorPriority == 10)
  }

  test ("In an Assert test if an XPath does not validate then the request should contain a SAXParseException") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val assertStep = new Assert("Assert","Assert", "$req:method = 'GET'", None, None, nsContext, 31, 10, Array[Step]())
    val req1 = request("DELETE", "/a/b")
    val req2 = request("PUT", "/a/c")

    assertStep.checkStep(req1, response, chain, StepContext())
    assertStep.checkStep(req2, response, chain, StepContext(1))

    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
  }


  test ("In an Assert test if an XPath does not validate then the request should contain a SAXParseException with message of 'Expecting '+XPath") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val assertStep = new Assert("Assert","Assert", "$req:method = 'GET'", None, None, nsContext, 31, 10, Array[Step]())
    val req1 = request("DELETE", "/a/b")
    val req2 = request("PUT", "/a/c")

    assertStep.checkStep(req1, response, chain, StepContext())
    assertStep.checkStep(req2, response, chain, StepContext(1))

    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("Expecting $req:method = 'GET'"))
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("Expecting $req:method = 'GET'"))
  }

  test ("In an Assert test if an XPath does not validate then the request should contain an error code = 400") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val assertStep = new Assert("Assert","Assert", "$req:method = 'GET'", None, None, nsContext, 31, 10, Array[Step]())
    val req1 = request("DELETE", "/a/b")
    val req2 = request("PUT", "/a/c")

    assertStep.checkStep(req1, response, chain, StepContext())
    assertStep.checkStep(req2, response, chain, StepContext(1))

    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("Expecting $req:method = 'GET'"))
    assert (req1.contentErrorCode == 400)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("Expecting $req:method = 'GET'"))
    assert (req2.contentErrorCode == 400)
  }

  test ("In an Assert test if an XPath does not validate then the request should contain a SAXParseException with message of setMessage") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val assertStep = new Assert("Assert","Assert", "$req:method = 'GET'", Some("Expecting a GET Method"), None, nsContext, 31, 10, Array[Step]())
    val req1 = request("DELETE", "/a/b")
    val req2 = request("PUT", "/a/c")

    assertStep.checkStep(req1, response, chain, StepContext())
    assertStep.checkStep(req2, response, chain, StepContext(1))

    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("Expecting a GET Method"))
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("Expecting a GET Method"))
  }


  test ("In an Assert test if an XPath does not validate then the request should contain an error code of setError Code") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val assertStep = new Assert("Assert","Assert", "$req:method = 'GET'", None, Some(401), nsContext, 31, 10, Array[Step]())
    val req1 = request("DELETE", "/a/b")
    val req2 = request("PUT", "/a/c")

    assertStep.checkStep(req1, response, chain, StepContext())
    assertStep.checkStep(req2, response, chain, StepContext(1))

    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("Expecting $req:method = 'GET'"))
    assert (req1.contentErrorCode == 401)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("Expecting $req:method = 'GET'"))
    assert (req2.contentErrorCode == 401)
  }


  test ("In an Assert test if an XPath does not validate then the request should contain an error message of setMessage  and an code of setError Code") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val assertStep = new Assert("Assert","Assert", "$req:method = 'GET'", Some("Expecting GET Method"), Some(401), nsContext, 31, 10, Array[Step]())
    val req1 = request("DELETE", "/a/b")
    val req2 = request("PUT", "/a/c")

    assertStep.checkStep(req1, response, chain, StepContext())
    assertStep.checkStep(req2, response, chain, StepContext(1))

    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("Expecting GET Method"))
    assert (req1.contentErrorCode == 401)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("Expecting GET Method"))
    assert (req2.contentErrorCode == 401)
  }

  test ("In an Assert test if an XPath does not validate then the request should contain an error message of setMessage  and an code of setError Code,  setPriority") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val assertStep = new Assert("Assert","Assert", "$req:method = 'GET'", Some("Expecting GET Method"), Some(401), nsContext, 31, 50, Array[Step]())
    val req1 = request("DELETE", "/a/b")
    val req2 = request("PUT", "/a/c")

    assertStep.checkStep(req1, response, chain, StepContext())
    assertStep.checkStep(req2, response, chain, StepContext(1))

    assert (req1.contentError != null)
    assert (req1.contentError.isInstanceOf[SAXParseException])
    assert (req1.contentError.getMessage.contains("Expecting GET Method"))
    assert (req1.contentErrorCode == 401)
    assert (req1.contentErrorPriority == 50)
    assert (req2.contentError != null)
    assert (req2.contentError.isInstanceOf[SAXParseException])
    assert (req2.contentError.getMessage.contains("Expecting GET Method"))
    assert (req2.contentErrorCode == 401)
    assert (req2.contentErrorPriority == 50)
  }

  test("In Capture Header step, we should be able to capture method of the request") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val captureHeaderStep = new CaptureHeader("CaptureHeader", "CaptureHeader", "X-METHOD", "$req:method", nsContext, 31, Array[Step]())

    val newCTX1 = captureHeaderStep.checkStep(request("GET", "/foo/bar"), response, chain, StepContext())
    val newCTX2 = captureHeaderStep.checkStep(request("POST", "/bar/foo"), response, chain, StepContext())

    assert(newCTX1.get.requestHeaders("X-METHOD") == List("GET"));
    assert(newCTX2.get.requestHeaders("X-METHOD") == List("POST"));
  }


  test("In Capture Header step, we should be able to capture method of the request (Existing context header)") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val captureHeaderStep = new CaptureHeader("CaptureHeader", "CaptureHeader", "X-METHOD", "$req:method", nsContext, 31, Array[Step]())

    val header1 = new HeaderMap()
    val newCTX1 = captureHeaderStep.checkStep(request("GET", "/foo/bar"), response, chain, StepContext(requestHeaders=header1.addHeader("X-METHOD","FAZZ")))
    val header2 = new HeaderMap()
    val newCTX2 = captureHeaderStep.checkStep(request("POST", "/bar/foo"), response, chain, StepContext(requestHeaders=header1.addHeaders("X-METHOD",List("FAZZ","FIZZ"))))

    assert(newCTX1.get.requestHeaders("X-METHOD") == List("FAZZ","GET"));
    assert(newCTX2.get.requestHeaders("X-METHOD") == List("FAZZ", "FIZZ", "POST"));
  }


  test("In Capture Header step, a sequence should be split into multiple header values") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val captureHeaderStep = new CaptureHeader("CaptureHeader", "CaptureHeader", "X-VALUES", "(1, 23, 38839, $req:uriLevel)", nsContext, 31, Array[Step]())

    val newCTX1 = captureHeaderStep.checkStep(request("GET", "/foo/bar"), response, chain, StepContext())
    val newCTX2 = captureHeaderStep.checkStep(request("POST", "/bar/foo"), response, chain, StepContext(5))

    assert(newCTX1.get.requestHeaders("X-VALUES") == List("1","23","38839","0"));
    assert(newCTX2.get.requestHeaders("X-VALUES") == List("1","23","38839","5"));
  }


  test("In Capture Header step, an empty sequence should be okay") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val captureHeaderStep = new CaptureHeader("CaptureHeader", "CaptureHeader", "X-METHOD", "()", nsContext, 31, Array[Step]())

    val header1 = new HeaderMap()
    val newCTX1 = captureHeaderStep.checkStep(request("GET", "/foo/bar"), response, chain, StepContext(requestHeaders=header1.addHeader("X-METHOD","FAZZ")))
    val header2 = new HeaderMap()
    val newCTX2 = captureHeaderStep.checkStep(request("POST", "/bar/foo"), response, chain, StepContext(requestHeaders=header1.addHeaders("X-METHOD",List("FAZZ","FIZZ"))))

    assert(newCTX1.get.requestHeaders("X-METHOD") == List("FAZZ"));
    assert(newCTX2.get.requestHeaders("X-METHOD") == List("FAZZ", "FIZZ"));
  }

  test ("The pop rep step test should pop a representation") {
    val popRepStep = new PopRep("PopRep", "PopRep", Array[Step]())
    val req = request("GET","/foo/bar")
    req.pushRepresentation(EMPTY_DOC)
    assert (req.parsedRepresentation.isInstanceOf[ParsedXML])
    val popContext = popRepStep.checkStep(req, response, chain, StepContext())
    assert (popContext.isDefined)
    assert (req.parsedRepresentation == ParsedNIL)
  }

  test ("The pop rep step test should pop a representation (multiple-reps)") {
    val popRepStep = new PopRep("PopRep", "PopRep", Array[Step]())
    val req = request("GET","/foo/bar")
    req.pushRepresentation(EMPTY_DOC)
    req.pushRepresentation(EMPTY_JSON)
    assert (req.parsedRepresentation.isInstanceOf[ParsedJSON])
    val popContext = popRepStep.checkStep(req, response, chain, StepContext())
    assert (popContext.isDefined)
    assert (req.parsedRepresentation.isInstanceOf[ParsedXML])
    val popContext2 = popRepStep.checkStep(req, response, chain, StepContext())
    assert (popContext2.isDefined)
    assert (req.parsedRepresentation == ParsedNIL)
  }

  test ("The pop rep step should result in exception if we have a stack underflow, and a log error") {
    val popRepStep = new PopRep("PopRep", "PopRep", Array[Step]())

    val popLog = log(Level.ERROR) {
      intercept[java.util.NoSuchElementException] {
        popRepStep.checkStep(request("GET","/foo/bar"), response, chain, StepContext())
      }
    }
    //
    // We should output a message stating what happened
    //
    assert(popLog, "pop an empty representation stack")
  }

  test("In a PushXML step, we should be able to push an XML representation from a request body") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val pushXML = new PushXML("PushXML", "PushXML", "XML", "$body('xml')",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/json","""
         {
            "xml" : "<foo xmlns='http://rackspace.com/bar' />"
         }
    """, true)
    assert(req.parsedRepresentation.isInstanceOf[ParsedJSON])
    val pushContext = pushXML.checkStep(req, response, chain, StepContext())

    assert(pushContext.isDefined)
    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    val xml = XML.load(req.getInputStream)
    assert (xml != null)
    assert (xml.toString.contains("<foo"))

    val req2 = request("PUT", "/a/b","application/json","""
         {
            "xml" : "<bar xmlns='http://rackspace.com/bar' />"
         }
    """, true)
    assert(req2.parsedRepresentation.isInstanceOf[ParsedJSON])
    val pushContext2 = pushXML.checkStep(req2, response, chain, StepContext())

    assert(pushContext2.isDefined)
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])
    val xml2 = XML.load(req2.getInputStream)
    assert (xml2 != null)
    assert (xml2.toString.contains("<bar"))
  }

  test("In a PushXML step, we should be able to push an XML representation from a request body, when the result of the path is a Node") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val pushXML = new PushXML("PushXML", "PushXML", "XML", "/root/xml",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/xml",
         <root>
             <xml>
                &lt;foo xmlns="http://rackspace.com/bar" /&gt;
             </xml>
         </root>
    , true)
    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    val pushContext = pushXML.checkStep(req, response, chain, StepContext())

    assert(pushContext.isDefined)
    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    val xml = XML.load(req.getInputStream)
    assert (xml != null)
    assert (xml.toString.contains("<foo"))

    val req2 = request("PUT", "/a/b","application/xml",
         <root>
             <xml>
               <internal>
                 &lt;bar xmlns="http://rackspace.com/biz" /&gt;
               </internal>
             </xml>
         </root>
    , true)
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])
    val pushContext2 = pushXML.checkStep(req2, response, chain, StepContext())

    assert(pushContext2.isDefined)
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])
    val xml2 = XML.load(req2.getInputStream)
    assert (xml2 != null)
    assert (xml2.toString.contains("<bar"))
  }

  test("In a PushXML step, pushing malformed XML should result in a SAXParseException as the contentError") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val pushXML = new PushXML("PushXML", "PushXML", "XML", "$_?xml",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/json",
      """{
         |   "xml" : "<foo xmlns='http://rackspace.com/bar'>"
         |}""".stripMargin, true)
    val origRep = req.parsedRepresentation
    assert(origRep.isInstanceOf[ParsedJSON])
    val pushContext = pushXML.checkStep(req, response, chain, StepContext())

    //
    //  Context should not be defined
    //
    assert(pushContext.isEmpty)

    //
    //  Original rep should stay in place
    //
    assert(req.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req.parsedRepresentation == origRep)

    //
    //  Appropriate Exception and priority
    //
    assert(req.contentError != null)
    assert(req.contentError.isInstanceOf[SAXParseException])
    assert(req.contentErrorPriority == 10)


    val req2 = request("PUT", "/a/b","application/json",
      """{
         |   "xml" : "booga"
         |}""".stripMargin, true)
    val origRep2 = req2.parsedRepresentation
    assert(origRep2.isInstanceOf[ParsedJSON])
    val pushContext2 = pushXML.checkStep(req2, response, chain, StepContext())

    //
    //  Context should not be defined
    //
    assert(pushContext2.isEmpty)

    //
    //  Original rep should stay in place
    //
    assert(req2.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req2.parsedRepresentation == origRep2)

    //
    //  Appropriate Exception and priority
    //
    assert(req2.contentError != null)
    assert(req2.contentError.isInstanceOf[SAXParseException])
    assert(req2.contentErrorPriority == 10)
  }

  test("In a PushXML step, pushing an atomic value other than a string should result in representation contentError with appropriate msg") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val pushXML = new PushXML("PushXML", "PushXML", "XML", "$_?xml",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/json",
      """{
         |   "xml" : 42
         |}""".stripMargin, true)
    val origRep = req.parsedRepresentation
    assert(origRep.isInstanceOf[ParsedJSON])
    val pushContext = pushXML.checkStep(req, response, chain, StepContext())

    //
    //  Context should not be defined
    //
    assert(pushContext.isEmpty)

    //
    //  Original rep should stay in place
    //
    assert(req.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req.parsedRepresentation == origRep)

    //
    //  Appropriate Exception and priority
    //
    assert(req.contentError != null)
    assert(req.contentError.isInstanceOf[RepresentationException])
    assert(req.contentErrorPriority == 10)
    assert(req.contentError.getMessage.contains("atomic value"))
    assert(req.contentError.getMessage.contains("42"))
    assert(req.contentError.getMessage.contains("cannot be converted into XML"))
    assert(req.contentError.getMessage.contains("$_?xml"))

    val req2 = request("PUT", "/a/b","application/json",
      """{
         |   "xml" : false
         |}""".stripMargin, true)
    val origRep2 = req2.parsedRepresentation
    assert(origRep2.isInstanceOf[ParsedJSON])
    val pushContext2 = pushXML.checkStep(req2, response, chain, StepContext())

    //
    //  Context should not be defined
    //
    assert(pushContext2.isEmpty)

    //
    //  Original rep should stay in place
    //
    assert(req2.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req2.parsedRepresentation == origRep2)

    //
    //  Appropriate Exception and priority
    //
    assert(req2.contentError != null)
    assert(req2.contentError.isInstanceOf[RepresentationException])
    assert(req2.contentErrorPriority == 10)
    assert(req2.contentError.getMessage.contains("atomic value"))
    assert(req2.contentError.getMessage.contains("false"))
    assert(req2.contentError.getMessage.contains("cannot be converted into XML"))
    assert(req2.contentError.getMessage.contains("$_?xml"))
  }

  test("In a PushXML step, pushing a function value should result in representation contentError with appropriate msg") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val pushXML = new PushXML("PushXML", "PushXML", "XML", "$_?xml",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/json",
      """{
         |   "xml" : { "biz" : "baz" }
         |}""".stripMargin, true)
    val origRep = req.parsedRepresentation
    assert(origRep.isInstanceOf[ParsedJSON])
    val pushContext = pushXML.checkStep(req, response, chain, StepContext())

    //
    //  Context should not be defined
    //
    assert(pushContext.isEmpty)

    //
    //  Original rep should stay in place
    //
    assert(req.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req.parsedRepresentation == origRep)

    //
    //  Appropriate Exception and priority
    //
    assert(req.contentError != null)
    assert(req.contentError.isInstanceOf[RepresentationException])
    assert(req.contentErrorPriority == 10)
    assert(req.contentError.getMessage.contains("A map"))
    assert(req.contentError.getMessage.contains("cannot be converted into XML"))
    assert(req.contentError.getMessage.contains("$_?xml"))

    val req2 = request("PUT", "/a/b","application/json",
      """{
         |   "xml" : [1, 2, 3]
         |}""".stripMargin, true)
    val origRep2 = req2.parsedRepresentation
    assert(origRep2.isInstanceOf[ParsedJSON])
    val pushContext2 = pushXML.checkStep(req2, response, chain, StepContext())

    //
    //  Context should not be defined
    //
    assert(pushContext2.isEmpty)

    //
    //  Original rep should stay in place
    //
    assert(req2.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req2.parsedRepresentation == origRep2)

    //
    //  Appropriate Exception and priority
    //
    assert(req2.contentError != null)
    assert(req2.contentError.isInstanceOf[RepresentationException])
    assert(req2.contentErrorPriority == 10)
    assert(req2.contentError.getMessage.contains("An array"))
    assert(req2.contentError.getMessage.contains("cannot be converted into XML"))
    assert(req2.contentError.getMessage.contains("$_?xml"))
  }

  test("In a PushXML step, pushing an empty sequence should result in representation contentError with appropriate msg") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val pushXML = new PushXML("PushXML", "PushXML", "XML", "$_?xml",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/json",
      """{
         |   "xml" : null
         |}""".stripMargin, true)
    val origRep = req.parsedRepresentation
    assert(origRep.isInstanceOf[ParsedJSON])
    val pushContext = pushXML.checkStep(req, response, chain, StepContext())

    //
    //  Context should not be defined
    //
    assert(pushContext.isEmpty)

    //
    //  Original rep should stay in place
    //
    assert(req.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req.parsedRepresentation == origRep)

    //
    //  Appropriate Exception and priority
    //
    assert(req.contentError != null)
    assert(req.contentError.isInstanceOf[RepresentationException])
    assert(req.contentErrorPriority == 10)
    assert(req.contentError.getMessage.contains("Expecting XML"))
    assert(req.contentError.getMessage.contains("got an empty sequence"))
    assert(req.contentError.getMessage.contains("$_?xml"))

    val req2 = request("PUT", "/a/b","application/json",
      """{
         |   "array" : [1, 2, 3]
         |}""".stripMargin, true)
    val origRep2 = req2.parsedRepresentation
    assert(origRep2.isInstanceOf[ParsedJSON])
    val pushContext2 = pushXML.checkStep(req2, response, chain, StepContext())

    //
    //  Context should not be defined
    //
    assert(pushContext2.isEmpty)

    //
    //  Original rep should stay in place
    //
    assert(req2.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req2.parsedRepresentation == origRep2)

    //
    //  Appropriate Exception and priority
    //
    assert(req2.contentError != null)
    assert(req2.contentError.isInstanceOf[RepresentationException])
    assert(req2.contentErrorPriority == 10)
    assert(req2.contentError.getMessage.contains("Expecting XML"))
    assert(req2.contentError.getMessage.contains("got an empty sequence"))
    assert(req2.contentError.getMessage.contains("$_?xml"))
  }

  test("In a PushXML step, returning a sequence of multiple items should result in representation contentError with appropriate msg") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val pushXML = new PushXML("PushXML", "PushXML", "XML", "($_?xml,'<baz />')",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/json",
      """{
         |   "xml" : "<foo xmlns='http://rackspace.com/bar' />"
         |}""".stripMargin, true)
    val origRep = req.parsedRepresentation
    assert(origRep.isInstanceOf[ParsedJSON])
    val pushContext = pushXML.checkStep(req, response, chain, StepContext())

    //
    //  Context should not be defined
    //
    assert(pushContext.isEmpty)

    //
    //  Original rep should stay in place
    //
    assert(req.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req.parsedRepresentation == origRep)

    //
    //  Appropriate Exception and priority
    //
    assert(req.contentError != null)
    assert(req.contentError.isInstanceOf[RepresentationException])
    assert(req.contentErrorPriority == 10)
    assert(req.contentError.getMessage.contains("Expecting XML"))
    assert(req.contentError.getMessage.contains("got a sequence of size > 1"))
    assert(req.contentError.getMessage.contains("($_?xml,'<baz />')"))

    val req2 = request("PUT", "/a/b","application/json",
      """{
         |   "xml" : [1, 2, 3]
         |}""".stripMargin, true)
    val origRep2 = req2.parsedRepresentation
    assert(origRep2.isInstanceOf[ParsedJSON])
    val pushContext2 = pushXML.checkStep(req2, response, chain, StepContext())

    //
    //  Context should not be defined
    //
    assert(pushContext2.isEmpty)

    //
    //  Original rep should stay in place
    //
    assert(req2.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req2.parsedRepresentation == origRep2)

    //
    //  Appropriate Exception and priority
    //
    assert(req2.contentError != null)
    assert(req2.contentError.isInstanceOf[RepresentationException])
    assert(req2.contentErrorPriority == 10)
    assert(req.contentError.getMessage.contains("Expecting XML"))
    assert(req.contentError.getMessage.contains("got a sequence of size > 1"))
    assert(req.contentError.getMessage.contains("($_?xml,'<baz />')"))
  }

  test("In a PushXML step, we should be able to push an XML representation and pass to additonal steps") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val pushXML = new PushXML("PushXML", "PushXML", "XML", "$body('xml')",nsContext,31,10,Array[Step](new Accept("A","A",100)))
    val req = request("PUT", "/a/b","application/json",
      """{
         |   "xml" : "<foo xmlns='http://rackspace.com/bar' />"
         |}""".stripMargin, true)
    assert(req.parsedRepresentation.isInstanceOf[ParsedJSON])
    val pushResult = pushXML.check(req, response, chain, StepContext())

    assert(pushResult.isDefined)
    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    val xml = XML.load(req.getInputStream)
    assert (xml != null)
    assert (xml.toString.contains("<foo"))

    val req2 = request("PUT", "/a/b","application/json",
      """{
         |   "xml" : "<bar xmlns='http://rackspace.com/bar' />"
         |}""".stripMargin, true)
    assert(req2.parsedRepresentation.isInstanceOf[ParsedJSON])
    val pushResult2 = pushXML.check(req2, response, chain, StepContext())

    assert(pushResult2.isDefined)
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])
    val xml2 = XML.load(req2.getInputStream)
    assert (xml2 != null)
    assert (xml2.toString.contains("<bar"))
  }

  test("In a PushXML step, if we correctly push a representation but a child step fails, we should pop the representation out") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val pushXML = new PushXML("PushXML", "PushXML", "XML", "$body('xml')",nsContext,31,10,Array[Step](new ContentFail("C","C",100)))
    val req = request("PUT", "/a/b","application/json",
      """{
         |   "xml" : "<foo xmlns='http://rackspace.com/bar' />"
         |}""".stripMargin, true)
    req.contentError = new IOException("Phony exception")
    assert(req.parsedRepresentation.isInstanceOf[ParsedJSON])
    val origJSON = req.parsedRepresentation
    val pushResult = pushXML.check(req, response, chain, StepContext())

    assert(pushResult.isDefined)
    assert(req.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(origJSON == req.parsedRepresentation)
    assert(req.contentError.isInstanceOf[IOException])
    assert(req.contentError.getMessage.contains("Phony exception"))

    val req2 = request("PUT", "/a/b","application/json",
      """{
         |   "xml" : "<bar xmlns='http://rackspace.com/bar' />"
         |}""".stripMargin, true)
    req2.contentError = new IOException("Phony exception")
    assert(req2.parsedRepresentation.isInstanceOf[ParsedJSON])
    val origJSON2 = req2.parsedRepresentation
    val pushResult2 = pushXML.check(req2, response, chain, StepContext())

    assert(pushResult2.isDefined)
    assert(req2.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(origJSON2 == req2.parsedRepresentation)
    assert(req2.contentError.isInstanceOf[IOException])
    assert(req2.contentError.getMessage.contains("Phony exception"))
  }

  test("In a PushJSON step, we should be able to push an JSON representation from a request body") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val pushJSON = new PushJSON("PushJSON", "PushJSON", "JSON", "/root/json",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |    <json>
        |
        |      {
        |        "foo" : "bar"
        |      }
        |    </json>
        |</root>""".stripMargin, true)
    val req2 = request("PUT", "/a/b","application/xml",
      """
         |<root>
         |   <other />
         |   <json>
         |    <actual>
         |      {
         |         "baz" : "biz"
         |      }
         |    </actual>
         |   </json>
         |</root>""".stripMargin.stripMargin, true)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    val pushContext  = pushJSON.checkStep(req, response, chain, StepContext())
    val pushContext2 = pushJSON.checkStep(req2, response, chain, StepContext())

    assert(pushContext.isDefined)
    assert(pushContext2.isDefined)

    assert(req.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedJSON])

    var jparser : ObjectMapper = null
    try {
      jparser = ObjectMapperPool.borrowParser
      val j1 = jparser.readValue(req.getInputStream, classOf[java.util.Map[Object, Object]])
      val j2 = jparser.readValue(req2.getInputStream, classOf[java.util.Map[Object, Object]])

      assert (j1 != null)
      assert (j2 != null)

      assert (j1.asInstanceOf[java.util.Map[Object,Object]].get("foo") == "bar")
      assert (j2.asInstanceOf[java.util.Map[Object,Object]].get("baz") == "biz")

    } finally {
      if (jparser != null) ObjectMapperPool.returnParser(jparser)
    }
  }

  test("In a PushJSON step, we should be able to push an JSON representation from a request body (result is a string)") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val pushJSON = new PushJSON("PushJSON", "PushJSON", "JSON", "string(/root/json)",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <json>
        |    {
        |      "foo" : "bar"
        |    }
        |  </json>
        |</root>""".stripMargin, true)
    val req2 = request("PUT", "/a/b","application/xml",
      """
        |<root>
        | <other />
        | <json>
        |   <actual>
        |     {
        |      "baz" : "biz"
        |     }
        |   </actual>
        | </json>
        |</root>""".stripMargin, true)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    val pushContext  = pushJSON.checkStep(req, response, chain, StepContext())
    val pushContext2 = pushJSON.checkStep(req2, response, chain, StepContext())

    assert(pushContext.isDefined)
    assert(pushContext2.isDefined)

    assert(req.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedJSON])

    var jparser : ObjectMapper = null
    try {
      jparser = ObjectMapperPool.borrowParser
      val j1 = jparser.readValue(req.getInputStream, classOf[java.util.Map[Object, Object]])
      val j2 = jparser.readValue(req2.getInputStream, classOf[java.util.Map[Object, Object]])

      assert (j1 != null)
      assert (j2 != null)

      assert (j1.asInstanceOf[java.util.Map[Object,Object]].get("foo") == "bar")
      assert (j2.asInstanceOf[java.util.Map[Object,Object]].get("baz") == "biz")

    } finally {
      if (jparser != null) ObjectMapperPool.returnParser(jparser)
    }
  }


  test("In a PushJSON step, we should be able to push atomic values as JSON representation from a request body (integer)") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]("xsd"->"http://www.w3.org/2001/XMLSchema"))
    val pushJSON = new PushJSON("PushJSON", "PushJSON", "JSON", "xsd:integer(/root/json)",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <json>
        |    42
        |  </json>
        |</root>""".stripMargin, true)
    val req2 = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <other />
        |  <json>
        |     76
        |  </json>
        |</root>""".stripMargin, true)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    val pushContext  = pushJSON.checkStep(req, response, chain, StepContext())
    val pushContext2 = pushJSON.checkStep(req2, response, chain, StepContext())

    assert(pushContext.isDefined)
    assert(pushContext2.isDefined)

    assert(req.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedJSON])

    var jparser : ObjectMapper = null
    try {
      jparser = ObjectMapperPool.borrowParser
      val j1 = jparser.readValue(req.getInputStream, classOf[Integer])
      val j2 = jparser.readValue(req2.getInputStream, classOf[Integer])

      assert (j1 != null)
      assert (j2 != null)

      assert (j1.asInstanceOf[Integer] == 42)
      assert (j2.asInstanceOf[Integer] == 76)
    } finally {
      if (jparser != null) ObjectMapperPool.returnParser(jparser)
    }
  }

  test("In a PushJSON step, we should be able to push atomic values as JSON representation from a request body (boolean)") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]("xsd"->"http://www.w3.org/2001/XMLSchema"))
    val pushJSON = new PushJSON("PushJSON", "PushJSON", "JSON", "xsd:boolean(/root/json)",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <json>
        |   false
        |  </json>
        |</root>""".stripMargin, true)
    val req2 = request("PUT", "/a/b","application/xml",
      """
        |<root>
        | <other />
        | <json>
        |   true
        | </json>
        |</root>""".stripMargin, true)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    val pushContext  = pushJSON.checkStep(req, response, chain, StepContext())
    val pushContext2 = pushJSON.checkStep(req2, response, chain, StepContext())

    assert(pushContext.isDefined)
    assert(pushContext2.isDefined)

    assert(req.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedJSON])

    var jparser : ObjectMapper = null
    try {
      jparser = ObjectMapperPool.borrowParser
      val j1 = jparser.readValue(req.getInputStream, classOf[Boolean])
      val j2 = jparser.readValue(req2.getInputStream, classOf[Boolean])

      assert (!j1.asInstanceOf[Boolean])
      assert (j2.asInstanceOf[Boolean])
    } finally {
      if (jparser != null) ObjectMapperPool.returnParser(jparser)
    }
  }

  test("In a PushJSON step, we should be able to push null values as JSON representation from a request body") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]("xsd"->"http://www.w3.org/2001/XMLSchema"))
    val pushJSON = new PushJSON("PushJSON", "PushJSON", "JSON", "/root/json",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <json>
        |   null
        |  </json>
        |</root>""".stripMargin, true)
    val req2 = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <other />
        |  <json>null</json>
        |</root>""".stripMargin, true)

    println(req.contentError)
    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    val pushContext  = pushJSON.checkStep(req, response, chain, StepContext())
    val pushContext2 = pushJSON.checkStep(req2, response, chain, StepContext())

    assert(pushContext.isDefined)
    assert(pushContext2.isDefined)

    assert(req.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedJSON])

    var jparser : ObjectMapper = null
    try {
      jparser = ObjectMapperPool.borrowParser
      val j1 = jparser.readValue(req.getInputStream, classOf[String])
      val j2 = jparser.readValue(req2.getInputStream, classOf[String])

      assert (j1 == null)
      assert (j2 == null)
    } finally {
      if (jparser != null) ObjectMapperPool.returnParser(jparser)
    }
  }


  test("In a PushJSON step, pushing an empty (or whitespace only) string should generate a representation error") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]("xsd"->"http://www.w3.org/2001/XMLSchema"))
    val pushJSON = new PushJSON("PushJSON", "PushJSON", "JSON", "/root/json",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <json>         </json>
        |</root>""".stripMargin, true)
    val req2 = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <json />
        |</root>""".stripMargin, true)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    val origRep  = req.parsedRepresentation
    val origRep2 = req2.parsedRepresentation

    val pushContext  = pushJSON.checkStep(req, response, chain, StepContext())
    val pushContext2 = pushJSON.checkStep(req2, response, chain, StepContext())

    assert(pushContext.isEmpty)
    assert(pushContext2.isEmpty)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    assert(origRep == req.parsedRepresentation)
    assert(origRep2 == req2.parsedRepresentation)

    assert(req.contentError != null)
    assert(req.contentError.isInstanceOf[RepresentationException])
    assert(req.contentErrorPriority == 10)
    assert(req.contentError.getMessage.contains("Expecting JSON"))
    assert(req.contentError.getMessage.contains("got an empty string"))
    assert(req.contentError.getMessage.contains("/root/json"))

    assert(req2.contentError != null)
    assert(req2.contentError.isInstanceOf[RepresentationException])
    assert(req2.contentErrorPriority == 10)
    assert(req2.contentError.getMessage.contains("Expecting JSON"))
    assert(req2.contentError.getMessage.contains("got an empty string"))
    assert(req2.contentError.getMessage.contains("/root/json"))
  }


  test("In a PushJSON step, pushing an empty sequence should generate a representation error") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]("xsd"->"http://www.w3.org/2001/XMLSchema"))
    val pushJSON = new PushJSON("PushJSON", "PushJSON", "JSON", "/root/json",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <wooga>         </wooga>
        |</root>""".stripMargin, true)
    val req2 = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <other />
        |</root>""".stripMargin, true)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    val origRep  = req.parsedRepresentation
    val origRep2 = req2.parsedRepresentation

    val pushContext  = pushJSON.checkStep(req, response, chain, StepContext())
    val pushContext2 = pushJSON.checkStep(req2, response, chain, StepContext())

    assert(pushContext.isEmpty)
    assert(pushContext2.isEmpty)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    assert(origRep == req.parsedRepresentation)
    assert(origRep2 == req2.parsedRepresentation)

    assert(req.contentError != null)
    assert(req.contentError.isInstanceOf[RepresentationException])
    assert(req.contentErrorPriority == 10)
    assert(req.contentError.getMessage.contains("Expecting JSON"))
    assert(req.contentError.getMessage.contains("got an empty sequence"))
    assert(req.contentError.getMessage.contains("/root/json"))

    assert(req2.contentError != null)
    assert(req2.contentError.isInstanceOf[RepresentationException])
    assert(req2.contentErrorPriority == 10)
    assert(req2.contentError.getMessage.contains("Expecting JSON"))
    assert(req2.contentError.getMessage.contains("got an empty sequence"))
    assert(req2.contentError.getMessage.contains("/root/json"))
  }

  test("In a PushJSON step, pushing a sequence of multiple items should generate a representation error") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]("xsd"->"http://www.w3.org/2001/XMLSchema"))
    val pushJSON = new PushJSON("PushJSON", "PushJSON", "JSON", "/root/json",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <json>true</json>
        |  <json>{ "booga" : "wooga" }</json>
        |</root>""".stripMargin, true)
    val req2 = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <json>null</json>
        |  <json />
        |</root>""".stripMargin, true)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    val origRep  = req.parsedRepresentation
    val origRep2 = req2.parsedRepresentation

    val pushContext  = pushJSON.checkStep(req, response, chain, StepContext())
    val pushContext2 = pushJSON.checkStep(req2, response, chain, StepContext())

    assert(pushContext.isEmpty)
    assert(pushContext2.isEmpty)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    assert(origRep == req.parsedRepresentation)
    assert(origRep2 == req2.parsedRepresentation)

    assert(req.contentError != null)
    assert(req.contentError.isInstanceOf[RepresentationException])
    assert(req.contentErrorPriority == 10)
    assert(req.contentError.getMessage.contains("Expecting JSON"))
    assert(req.contentError.getMessage.contains("got a sequence of size > 1"))
    assert(req.contentError.getMessage.contains("/root/json"))

    assert(req2.contentError != null)
    assert(req2.contentError.isInstanceOf[RepresentationException])
    assert(req2.contentErrorPriority == 10)
    assert(req2.contentError.getMessage.contains("Expecting JSON"))
    assert(req2.contentError.getMessage.contains("got a sequence of size > 1"))
    assert(req2.contentError.getMessage.contains("/root/json"))
  }

  test("In a PushJSON step, pushing a function should generate a representation error") {

    val nsContext = ImmutableNamespaceContext(Map[String,String]("xsd"->"http://www.w3.org/2001/XMLSchema"))
    val pushJSON = new PushJSON("PushJSON", "PushJSON", "JSON", "parse-json(/root/json)",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <json>{ "booga" : "wooga" }</json>
        |</root>""".stripMargin, true)
    val req2 = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <json>[1, 2, 3]</json>
        |</root>""".stripMargin, true)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    val origRep  = req.parsedRepresentation
    val origRep2 = req2.parsedRepresentation

    val pushContext  = pushJSON.checkStep(req, response, chain, StepContext())
    val pushContext2 = pushJSON.checkStep(req2, response, chain, StepContext())

    assert(pushContext.isEmpty)
    assert(pushContext2.isEmpty)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    assert(origRep == req.parsedRepresentation)
    assert(origRep2 == req2.parsedRepresentation)

    assert(req.contentError != null)
    assert(req.contentError.isInstanceOf[RepresentationException])
    assert(req.contentErrorPriority == 10)
    assert(req.contentError.getMessage.contains("A map"))
    assert(req.contentError.getMessage.contains("cannot be converted into JSON"))
    assert(req.contentError.getMessage.contains("parse-json(/root/json)"))

    assert(req2.contentError != null)
    assert(req2.contentError.isInstanceOf[RepresentationException])
    assert(req2.contentErrorPriority == 10)
    assert(req2.contentError.getMessage.contains("An array"))
    assert(req2.contentError.getMessage.contains("cannot be converted into JSON"))
    assert(req2.contentError.getMessage.contains("parse-json(/root/json)"))
  }

  test("In a PushJSON step, pushing non-json atomic values should generate parse errors") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]("xsd"->"http://www.w3.org/2001/XMLSchema"))
    val pushJSON = new PushJSON("PushJSON", "PushJSON", "JSON", "xsd:dateTime(/root/json)",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/xml",
      """
        |<root>
        | <json>
        |   2017-10-26T21:32:52
        | </json>
        |</root>""".stripMargin, true)
    val req2 = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <other />
        |  <json>
        |    2017-12-25T00:00:09
        |  </json>
        |</root>""".stripMargin, true)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    val origRep  = req.parsedRepresentation
    val origRep2 = req2.parsedRepresentation

    val pushContext  = pushJSON.checkStep(req, response, chain, StepContext())
    val pushContext2 = pushJSON.checkStep(req2, response, chain, StepContext())

    assert(pushContext.isEmpty)
    assert(pushContext2.isEmpty)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    assert(origRep == req.parsedRepresentation)
    assert(origRep2 == req2.parsedRepresentation)

    assert(req.contentError != null)
    assert(req.contentError.isInstanceOf[JsonParseException])
    assert(req.contentErrorPriority == 10)

    assert(req2.contentError != null)
    assert(req2.contentError.isInstanceOf[JsonParseException])
    assert(req2.contentErrorPriority == 10)
  }

  test("In a PushJSON step, pushing malformed JSON should result in JSONParseException as the content Error") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val pushJSON = new PushJSON("PushJSON", "PushJSON", "JSON", "/root/json",nsContext,31,10,Array[Step]())
    val req = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <json>
        |    {
        |      "foo" :
        |    }
        |  </json>
        |</root>""".stripMargin, true)
    val req2 = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <json>
        |    {
        |      "baz" : baz
        |    }
        |  </json>
        |</root>""".stripMargin, true)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    val origRep  = req.parsedRepresentation
    val origRep2 = req2.parsedRepresentation

    val pushContext  = pushJSON.checkStep(req, response, chain, StepContext())
    val pushContext2 = pushJSON.checkStep(req2, response, chain, StepContext())

    assert(pushContext.isEmpty)
    assert(pushContext2.isEmpty)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    assert(origRep == req.parsedRepresentation)
    assert(origRep2 == req2.parsedRepresentation)

    assert(req.contentError != null)
    assert(req.contentError.isInstanceOf[JsonParseException])
    assert(req.contentErrorPriority == 10)

    assert(req2.contentError != null)
    assert(req2.contentError.isInstanceOf[JsonParseException])
    assert(req2.contentErrorPriority == 10)
  }

  test("In a PushJSON step, we should be able to push an JSON representation to additional steps") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val pushJSON = new PushJSON("PushJSON", "PushJSON", "JSON", "/root/json",nsContext,31,10,Array[Step](new Accept("A","A", 100)))
    val req = request("PUT", "/a/b","application/xml",
      """
        |<root>
        | <json>
        |   {
        |     "foo" : "bar"
        |   }
        | </json>
        |</root>""".stripMargin, true)
    val req2 = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <other />
        |  <json>
        |   <actual>
        |     {
        |      "baz" : "biz"
        |     }
        |   </actual>
        |   </json>
        |</root>""".stripMargin, true)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])

    val pushResult  = pushJSON.check(req, response, chain, StepContext())
    val pushResult2 = pushJSON.check(req2, response, chain, StepContext())

    assert(pushResult.isDefined)
    assert(pushResult2.isDefined)

    assert(req.parsedRepresentation.isInstanceOf[ParsedJSON])
    assert(req2.parsedRepresentation.isInstanceOf[ParsedJSON])

    var jparser : ObjectMapper = null
    try {
      jparser = ObjectMapperPool.borrowParser
      val j1 = jparser.readValue(req.getInputStream, classOf[java.util.Map[Object, Object]])
      val j2 = jparser.readValue(req2.getInputStream, classOf[java.util.Map[Object, Object]])

      assert (j1 != null)
      assert (j2 != null)

      assert (j1.asInstanceOf[java.util.Map[Object,Object]].get("foo") == "bar")
      assert (j2.asInstanceOf[java.util.Map[Object,Object]].get("baz") == "biz")

    } finally {
      if (jparser != null) ObjectMapperPool.returnParser(jparser)
    }
  }

  test("In a PushJSON step, if we correctly push a representation but a child step fails, we should pop the representation out") {
    val nsContext = ImmutableNamespaceContext(Map[String,String]())
    val pushJSON = new PushJSON("PushJSON", "PushJSON", "JSON", "/root/json",nsContext,31,10,Array[Step](new ContentFail("C","C", 100)))
    val req = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <json>
        |    {
        |      "foo" : "bar"
        |    }
        |  </json>
        |</root>""".stripMargin, true)
    val req2 = request("PUT", "/a/b","application/xml",
      """
        |<root>
        |  <other />
        |  <json>
        |    <actual>
        |      {
        |        "baz" : "biz"
        |      }
        |    </actual>
        |  </json>
        |</root>""".stripMargin, true)

    val origRep  = req.parsedRepresentation
    val origRep2 = req2.parsedRepresentation

    assert(origRep.isInstanceOf[ParsedXML])
    assert(origRep2.isInstanceOf[ParsedXML])

    req.contentError = new IOException("Phony exception")
    req2.contentError = new IOException("Phony exception")

    val pushResult  = pushJSON.check(req, response, chain, StepContext())
    val pushResult2 = pushJSON.check(req2, response, chain, StepContext())

    assert(pushResult.isDefined)
    assert(pushResult2.isDefined)

    assert(req.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req.parsedRepresentation == origRep)
    assert(req.contentError.isInstanceOf[IOException])
    assert(req.contentError.getMessage.contains("Phony exception"))
    assert(req2.parsedRepresentation.isInstanceOf[ParsedXML])
    assert(req2.parsedRepresentation == origRep2)
    assert(req2.contentError.isInstanceOf[IOException])
    assert(req2.contentError.getMessage.contains("Phony exception"))
  }

}
