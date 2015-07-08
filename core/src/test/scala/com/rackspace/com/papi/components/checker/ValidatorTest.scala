/** *
  * Copyright 2014 Rackspace US, Inc.
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
package com.rackspace.com.papi.components.checker

import java.io.ByteArrayInputStream
import javax.xml.transform.stream.StreamSource

import com.rackspace.com.papi.components.checker.handler.{ConsoleResultHandler, DispatchResultHandler, ResultHandler, ServletResultHandler}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ValidatorTest extends BaseValidatorSuite {

  val wadl = """<application xmlns="http://wadl.dev.java.net/2009/02"
               |             xmlns:rax="http://docs.rackspace.com/api"
               |             xmlns:tst="http://test.rackspace.com/test"
               |             xmlns:xsd="http://www.w3.org/2001/XMLSchema"
               |>
               |  <grammars>
               |    <schema
               |      elementFormDefault="qualified"
               |      attributeFormDefault="unqualified"
               |      xmlns="http://www.w3.org/2001/XMLSchema"
               |      xmlns:xsd="http://www.w3.org/2001/XMLSchema"
               |      xmlns:body="http://test.rackspace.com/body"
               |      targetNamespace="http://test.rackspace.com/body">
               |
               |      <element name="body-root" type="body:BodyRoot"/>
               |
               |      <complexType name="BodyRoot">
               |        <sequence>
               |          <element name="body-element" type="xsd:integer" minOccurs="1" maxOccurs="2" rax:message="Wrong number of Elements!!!"/>
               |        </sequence>
               |      </complexType>
               |    </schema>
               |  </grammars>
               |
               |  <resources base="http://localhost:${targetPort}">
               |    <resource path="/path/to/test" id="path-to-test">
               |      <method name="GET" id="path-to-test-GET">
               |        <request>
               |          <param name="X-TEST" style="header" type="xsd:string" rax:message="Not Present" required="true"/>
               |          <representation mediaType="application/xml"/>
               |        </request>
               |      </method>
               |    </resource>
               |  </resources>
               |</application>
             """.stripMargin

  test("GET on /path/to/test should fail without header X-TEST") {
    val config = new Config

    config.resultHandler = new DispatchResultHandler(List[ResultHandler](
      new ConsoleResultHandler(),
//      new AssertResultHandler(),
      new RunAssertionsHandler(),
      new ServletResultHandler()
    ))

    // Configuration Parameter               Non-Default     Type       Default
    //------------------------------------   -------------   ---------  ----------
    //config.removeDups                      = false      // : Boolean  = true
    config.setXSDEngine                    ("SaxonEE")  // : String   = "Xerces"
    config.checkWellFormed                 = true       // : Boolean  = false
    config.checkXSDGrammar                 = true       // : Boolean  = false
    config.checkElements                   = true       // : Boolean  = false
    config.xpathVersion                    = 2          // : Int      = 1
    config.checkPlainParams                = true       // : Boolean  = false
    //config.doXSDGrammarTransform           = true       // : Boolean  = false
    config.enablePreProcessExtension       = false      // : Boolean  = true
    //config.xslEngine                       = "XalanC"   // : String   = "Xalan"
    //config.xslEngine                       = "SaxonEE"  // : String   = "Xalan"
    //config.xslEngine                       = "SaxonHE"  // : String   = "Xalan"
    config.joinXPathChecks                 = true       // : Boolean  = false
    config.checkHeaders                    = true       // : Boolean  = false
    //config.enableIgnoreXSDExtension        = false      // : Boolean  = true
    //config.enableMessageExtension          = false      // : Boolean  = true
    //config.checkJSONGrammar                = true       // : Boolean  = false
    //config.enableIgnoreJSONSchemaExtension = false      // : Boolean  = true
    //config.enableRaxRolesExtension         = true       // : Boolean  = false
    //config.preserveRequestBody             = true       // : Boolean  = false
    //config.maskRaxRoles403                 = true       // : Boolean  = false

    val validator = Validator.apply(
      "test_" + System.currentTimeMillis,
      new StreamSource(new ByteArrayInputStream(wadl.getBytes), "file://test.wadl"),
      config
    )
    val result = validator.validate(
      request(
        "GET",
        "/path/to/test",
        "application/xml",
        """<body-root xmlns="http://test.rackspace.com/body">
          |    <body-element>1</body-element>
          |    <body-element>2</body-element>
          |    <body-element>3</body-element>
          |</body-root>
        """.stripMargin,
        false,
        Map("X-TEST"->List("here"))
        //Map.empty[String, List[String]]
      ),
      response,
      chain
    )
    result.toString
  }
}
