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
package com.rackspace.com.papi.components.checker.servlet

import java.io.{BufferedReader, ByteArrayOutputStream, IOException, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.net.{URI, URISyntaxException}
import java.util
import java.util.Base64
import java.util.NoSuchElementException
import javax.servlet.ServletInputStream
import javax.servlet.http.{HttpServletRequest, HttpServletRequestWrapper}
import javax.xml.transform.Transformer
import javax.xml.transform.Source
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.netaporter.uri.encoding.PercentEncoder
import com.rackspace.com.papi.components.checker.servlet.RequestAttributes._
import com.rackspace.com.papi.components.checker.util.IdentityTransformPool._
import com.rackspace.com.papi.components.checker.util.{DateUtils, HeaderMap, ObjectMapperPool}
import com.rackspace.com.papi.components.checker.util.JSONConverter
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.w3c.dom.Document
import net.sf.saxon.om.Sequence
import net.sf.saxon.s9api.XdmValue
import net.sf.saxon.s9api.XdmMap
import net.sf.saxon.Configuration

import scala.collection.JavaConverters._
import scala.collection.mutable.Stack


object CheckerServletRequest {
  type MappedRoles = Map[String, List[String]]
  val  NilMappedRoles : MappedRoles = Map[String, List[String]]()

  val DEFAULT_CONTENT_ERROR_CODE : Integer = 400
  val DEFAULT_URI_CHARSET : String = "ASCII"
  val MAP_ROLES_HEADER : String = "X-MAP-ROLES"
  val ROLES_HEADER : String = "X-ROLES"

  val uriEncoder = new PercentEncoder()
  val privateMapper = {
    val om = new ObjectMapper

    om.registerModule(DefaultScalaModule)
    om
  }

  val base64Decoder = Base64.getDecoder
}

//
//  An HTTP Request with some additional helper functions
//
class CheckerServletRequest(val request : HttpServletRequest) extends HttpServletRequestWrapper(request) with LazyLogging {

  import com.rackspace.com.papi.components.checker.servlet.CheckerServletRequest._

  private val repStack = new Stack[ParsedRepresentation]

  private var auxiliaryHeaders = new HeaderMap

  val parsedRequestURI : (Option[URI], Option[URISyntaxException]) = {
    try {
      (Some(new URI(request.getRequestURI)), None)
    } catch {
      case u : URISyntaxException => (None, Some(u))
    }
  }

  val URISegment : Array[String] = parsedRequestURI match {
    case (Some(u), None) => u.getPath.split("/").filterNot(e => e == "")
    case (None, Some(e)) => Array[String]()
    case (Some(u), Some(e)) => val ru = request.getRequestURI
                               logger.warn (s"Very strange, was simultaneously able to parse the request uri: '{$ru}' and also got a syntax error. Assuming a bad URI.")
                               Array[String]()
    case (None, None) => val ru = request.getRequestURI
                         val e = new URISyntaxException(ru, s"Unable to parse URI, don't know why???")
                         logger.error ("Very strange, unable to parse the URI, but didn't recieve a URISyntaxException, so I'm generating one anyway", e)
                         Array[String]()
  }

  private var clearInput = false

  def clearInputStream : Unit = {
    clearInput = true
  }

  def pathToSegment(uriLevel : Int) : String = {
    "/" + URISegment.slice(0, uriLevel).reduceLeft( _ + "/" +_ )
  }

  def mappedRoles : MappedRoles = {
    Option(request.getAttribute(MAP_ROLES).asInstanceOf[MappedRoles]) match {
      case Some (m : MappedRoles) => m
      case None => setMappedRoles
    }
  }

  private[this] def setMappedRoles : MappedRoles = {
    val ret = Option(getHeader(MAP_ROLES_HEADER)) match {
      case Some(s : String) =>
        try {
          Option(privateMapper.readValue(new String(base64Decoder.decode(s),UTF_8), classOf[MappedRoles])) match {
            case Some(m : MappedRoles) => m
            case None => NilMappedRoles
          }
        } catch {
          case e : Exception =>
            logger.error(s"Strange error, the header $MAP_ROLES_HEADER could not be parsed.  Ignoring map roles!",e)
            NilMappedRoles
        }
      case None => NilMappedRoles
    }
    request.setAttribute(MAP_ROLES, ret)
    ret
  }

  def parsedRepresentation : ParsedRepresentation = {
    Option(request.getAttribute(PARSED_REPRESENTATION).asInstanceOf[ParsedRepresentation]) match {
      case Some(r : ParsedRepresentation) => r
      case None => ParsedNIL
    }
  }

  def pushRepresentation (doc : Document) : Unit = {
    repStack.push(parsedRepresentation)
    parsedXML = doc
  }

  def pushRepresentation (tb : JsonNode) : Unit = {
    repStack.push(parsedRepresentation)
    parsedJSON = tb
  }

  def popRepresentation : Unit = {
    try {
      repStack.pop match  {
        case xml   : ParsedXML  => parsedXML  = xml
        case json  : ParsedJSON => parsedJSON = json
        case ParsedNIL  => clearParsedRepresentation
      }
    } catch {
      case nsee : NoSuchElementException =>
        logger.error ("Very strange, I tried to pop an empty representation stack.  This should never happen, please report this error.", nsee)
        //
        //  This exception is handled at the step level.
        //
        throw nsee
    }
  }

  def clearParsedRepresentation : Unit = {
    parsedRepresentation.clearRepresentation(this)
    request.setAttribute(PARSED_REPRESENTATION, ParsedNIL)
  }

  def parsedXML : Document = request.getAttribute(PARSED_XML).asInstanceOf[Document]
  def parsedXML_= (doc : Document):Unit = {
    val xmlRep = new ParsedXML(doc)
    parsedXML = xmlRep
  }
  def parsedXML_= (xmlRep : ParsedXML) : Unit = {
    clearParsedRepresentation
    request.setAttribute (PARSED_REPRESENTATION, xmlRep)
    xmlRep.setRepresentation(this)
  }

  def parsedXMLSource : Source = {
    val src = request.getAttribute(PARSED_XML_SOURCE).asInstanceOf[Source]
    if (src == null && parsedXML != null) {
      val nsrc = new DOMSource(parsedXML)
      request.setAttribute (PARSED_XML_SOURCE, nsrc)
      nsrc
    } else {
      src
    }
  }


  def parsedJSON : JsonNode = request.getAttribute(PARSED_JSON).asInstanceOf[JsonNode]
  def parsedJSON_= (tb : JsonNode):Unit = {
    val jsonRep = new ParsedJSON(tb)
    parsedJSON = jsonRep
  }
  def parsedJSON_= (jsonRep : ParsedJSON) : Unit = {
    clearParsedRepresentation
    request.setAttribute (PARSED_REPRESENTATION, jsonRep)
    jsonRep.setRepresentation(this)
  }

  def parsedJSONSequence : Sequence = {
    val seq = request.getAttribute(PARSED_JSON_SEQUENCE).asInstanceOf[Sequence]
    if (seq == null && parsedJSON != null) {
      val nseq = JSONConverter.convert(parsedJSON)
      request.setAttribute (PARSED_JSON_SEQUENCE, nseq)
      nseq
    } else {
      seq
    }
  }


  def parsedJSONXdmValue : XdmValue = {
    val xdmVal = request.getAttribute(PARSED_JSON_XDM_VALUE).asInstanceOf[XdmValue]
    if (xdmVal == null && parsedJSONSequence != null) {
      val nxdmVal = XdmValue.wrap(parsedJSONSequence)
      request.setAttribute(PARSED_JSON_XDM_VALUE, nxdmVal)
      nxdmVal
    } else {
      xdmVal
    }
  }


  def contentError : Exception = request.getAttribute(CONTENT_ERROR).asInstanceOf[Exception]
  def contentError_= (e : Exception):Unit = {
    request.setAttribute(CONTENT_ERROR, e)
    request.setAttribute(CONTENT_ERROR_CODE, DEFAULT_CONTENT_ERROR_CODE)
  }
  def contentError(e : Exception, c : Int, p : Long = -1) : Unit = {
    request.setAttribute(CONTENT_ERROR, e)
    request.setAttribute(CONTENT_ERROR_CODE, c)
    request.setAttribute(CONTENT_ERROR_PRIORITY, p)
  }

  def contentErrorCode : Int = request.getAttribute(CONTENT_ERROR_CODE).asInstanceOf[Int]

  def contentErrorPriority : Long = request.getAttribute(CONTENT_ERROR_PRIORITY) match {
    case l : Object => l.asInstanceOf[Long]
    case null => -1
  }
  def contentErrorPriority_= (p : Long) : Unit = request.setAttribute (CONTENT_ERROR_PRIORITY, p)

  def asXdmValue : XdmValue = request.getAttribute(REQUEST_XDM_VALUE) match {
    case reqValue : XdmValue => reqValue
    case null => val rv = XdmMap.makeMap(new HttpServletRequestMap(this))
                 request.setAttribute(REQUEST_XDM_VALUE, rv)
                 rv
  }

  def addHeader(name: String, value: String): Unit = {
    auxiliaryHeaders = auxiliaryHeaders.addHeader(name, value)
    request.setAttribute(REQUEST_XDM_VALUE, null) //invalidate our xdm value cache
  }

  def addHeaders(hm : HeaderMap) : Unit = {
    auxiliaryHeaders = auxiliaryHeaders.addHeaders(hm)
    request.setAttribute(REQUEST_XDM_VALUE, null) //invalidate our xdm value cache
  }

  override def getDateHeader(name: String): Long = {
    Option(getHeader(name)) match {
      case Some(headerValue) =>
        Option(DateUtils.parseDate(headerValue)) match {
          case Some(parsedDate) => parsedDate.getTime
          case None => throw new IllegalArgumentException("Header value could not be converted to a date")
        }
      case None => -1
    }
  }

  override def getHeader(name: String): String = {
    auxiliaryHeaders.get(name) match {
      case Some(firstValue :: _) => firstValue
      case _ => super.getHeader(name)
    }
  }

  override def getHeaders(name: String): util.Enumeration[String] = {
    // Note: We store a Scala set to prevent inconsistent unions (probably due to primaryHeaderNames
    // being a Java Enumeration).
    Option(super.getHeaders(name)) match {
      case Some(originalHeaders) => (auxiliaryHeaders.get(name).getOrElse(List()) ++ originalHeaders.asScala.toList).toIterator.asJavaEnumeration
      case None => null
    }
  }

  override def getHeaderNames: util.Enumeration[String] = {
//    Note: We store a Scala set to prevent inconsistent unions (probably due to primaryHeaderNames
//            being a Java Enumeration).
    Option(super.getHeaderNames) match {
      case Some(originalHeaderNames) => (auxiliaryHeaders.keySet ++ originalHeaderNames.asScala.toList).toIterator.asJavaEnumeration
      case None => null
    }
  }

  override def getIntHeader(name: String): Int = {
    Option(getHeader(name)) match {
      case Some(headerValue) => headerValue.toInt
      case None => -1
    }
  }

  override def getRequestURI : String = parsedRequestURI match {
    case (Some(u), _) => request.getRequestURI
    case _  => // Try to encode the URI if there was a syntax error.
               // Handlers may try to parse it.
               uriEncoder.encode(super.getRequestURI, DEFAULT_URI_CHARSET)
  }

  override def getInputStream : ServletInputStream = {
    if (clearInput) {
      new ByteArrayServletInputStream(Array[Byte]())
    } else if (parsedXML != null) {
      var transformer : Transformer = null
      val bout = new ByteArrayOutputStream()
      try {
        parsedXML.normalizeDocument
        transformer = borrowTransformer
        transformer.transform (new DOMSource(parsedXML), new StreamResult(bout))
        new ByteArrayServletInputStream(bout.toByteArray())
      } catch {
        case e : Exception => throw new IOException("Error while serializing!", e)
      } finally {
        clearParsedRepresentation
        returnTransformer(transformer)
      }
    } else if (parsedJSON != null) {
      var om : ObjectMapper = null
      try {
        om = ObjectMapperPool.borrowParser
        new ByteArrayServletInputStream(om.writeValueAsBytes(parsedJSON))
      } finally {
        clearParsedRepresentation
        if (om != null) {
          ObjectMapperPool.returnParser(om)
        }
      }
    } else {
      super.getInputStream
    }
  }

  override def getReader : BufferedReader = {
    if (clearInput) {
      new BufferedReader(new InputStreamReader (getInputStream, "UTF-8"))
    } else if (parsedXML != null) {
      new BufferedReader(new InputStreamReader (getInputStream, parsedXML.getInputEncoding))
    } else if (parsedJSON != null) {
      new BufferedReader(new InputStreamReader (getInputStream, "UTF-8"))
    }else {
      super.getReader
    }
  }
}
