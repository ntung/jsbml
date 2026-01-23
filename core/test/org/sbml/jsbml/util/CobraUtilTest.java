package org.sbml.jsbml.util;

import static org.junit.Assert.*;

import java.util.Properties;

import javax.xml.stream.XMLStreamException;

import org.junit.Test;
import org.sbml.jsbml.Parameter;

public class CobraUtilTest {

  @Test
  public void testParseCobraNotesRecognizesSimpleKeys() throws XMLStreamException {
    Parameter p = new Parameter(3, 1);
    p.setId("p");

    // Simple COBRA-style notes using keys without spaces
    p.appendNotes("<body xmlns=\"http://www.w3.org/1999/xhtml\">"
        + "<p>FORMULA: H4N</p>"
        + "<p>CHARGE: 1</p>"
        + "<p>GENE_ASSOCIATION: 1594.1</p>"
        + "</body>");

    Properties props = CobraUtil.parseCobraNotes(p);

    assertEquals("H4N", props.getProperty("FORMULA"));
    assertEquals("1", props.getProperty("CHARGE"));
    assertEquals("1594.1", props.getProperty("GENE_ASSOCIATION"));
  }

  @Test
  public void testParseCobraNotesIgnoresKeysWithSpaces() throws XMLStreamException {
    Parameter p = new Parameter(3, 1);
    p.setId("p");

    // Keys with spaces before the colon should be ignored by the generic rule
    p.appendNotes("<body xmlns=\"http://www.w3.org/1999/xhtml\">"
        + "<p>EC Number: 123</p>"
        + "<p>Confidence Level: 4</p>"
        + "</body>");

    Properties props = CobraUtil.parseCobraNotes(p);

    assertNull("Keys with spaces should be ignored", props.getProperty("EC Number"));
    assertNull("Keys with spaces should be ignored", props.getProperty("Confidence Level"));
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