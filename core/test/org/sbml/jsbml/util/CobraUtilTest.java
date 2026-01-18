package org.sbml.jsbml.util;

import static org.junit.Assert.*;

import java.util.Properties;

import javax.xml.stream.XMLStreamException;

import org.junit.Test;
import org.sbml.jsbml.Parameter;

public class CobraUtilTest {

  @Test
  public void testParseCobraNotesRecognizesKnownKeys() throws XMLStreamException {
    Parameter p = new Parameter(3, 1);
    p.setId("p");

    // Simple COBRA-style notes using known keys
    p.appendNotes("<body xmlns=\"http://www.w3.org/1999/xhtml\">"
        + "<p>FORMULA: H4N</p>"
        + "<p>CHARGE: 1</p>"
        + "<p>EC Number: 123</p>"
        + "</body>");

    Properties props = CobraUtil.parseCobraNotes(p);

    assertEquals("H4N", props.getProperty("FORMULA"));
    assertEquals("1", props.getProperty("CHARGE"));
    assertEquals("123", props.getProperty("EC Number"));
  }

  @Test
  public void testParseCobraNotesIgnoresGenericHtmlSentences() throws XMLStreamException {
    Parameter p = new Parameter(3, 1);
    p.setId("p");

    // Notes that are general text, not structured COBRA fields
    p.appendNotes("<body xmlns=\"http://www.w3.org/1999/xhtml\">"
        + "<p>This is an explanation: not a COBRA field.</p>"
        + "<p>Another sentence: still not a structured key.</p>"
        + "</body>");

    Properties props = CobraUtil.parseCobraNotes(p);

    assertTrue("Generic HTML sentences with ':' must be ignored", props.isEmpty());
  }
}