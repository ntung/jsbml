/*
 * ----------------------------------------------------------------------------
 * This file is part of JSBML. Please visit <http://sbml.org/Software/JSBML>
 * for the latest version of JSBML and more information about SBML.
 *
 * Copyright (C) 2009-2022 jointly by the following organizations:
 * 1. The University of Tuebingen, Germany
 * 2. EMBL European Bioinformatics Institute (EBML-EBI), Hinxton, UK
 * 3. The California Institute of Technology, Pasadena, CA, USA
 * 4. The University of California, San Diego, La Jolla, CA, USA
 * 5. The Babraham Institute, Cambridge, UK
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation. A copy of the license agreement is provided
 * in the file named "LICENSE.txt" included with this software distribution
 * and also available online as <http://sbml.org/Software/JSBML/License>.
 * ----------------------------------------------------------------------------
 */
package org.sbml.jsbml.xml.stax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.InputStream;

import javax.xml.stream.XMLStreamException;

import org.junit.Test;
import org.sbml.jsbml.ASTNode;
import org.sbml.jsbml.KineticLaw;
import org.sbml.jsbml.Model;
import org.sbml.jsbml.Reaction;
import org.sbml.jsbml.SBMLDocument;
import org.sbml.jsbml.util.SimpleTreeNodeChangeListener;
import org.sbml.jsbml.xml.XMLNode;

/**
 * Regression tests for the refactored {@link SBMLReader}.
 *
 * Covers the five logic paths changed in the refactor (issue #283):
 * <ol>
 *   <li>{@code readMathML(String)} — delegates through extracted {@code getAstNode()}</li>
 *   <li>{@code readMathML(String, TreeNodeChangeListener)} — same extraction path</li>
 *   <li>{@code readMathML(String, TreeNodeChangeListener, MathContainer)} — with parent</li>
 *   <li>{@code readNotes} — redundant {@code object != null} guards removed</li>
 *   <li>Full SBML file parse — exercises {@code processEndElement} ASTNode path</li>
 * </ol>
 */
public class SBMLReaderReadingTest {

  private static final String SIMPLE_MATH =
    "<math xmlns=\"http://www.w3.org/1998/Math/MathML\">"
    + "  <apply><plus/><cn>1</cn><cn>2</cn></apply>"
    + "</math>";

  private static final String NOTES_XHTML =
    "<notes><body xmlns=\"http://www.w3.org/1999/xhtml\"><p>Hello</p></body></notes>";

  // ── readMathML ──────────────────────────────────────────────────────────────

  @Test
  public void readMathML_simpleExpression_returnsASTNode() throws XMLStreamException {
    SBMLReader reader = new SBMLReader();
    ASTNode node = reader.readMathML(SIMPLE_MATH, new SimpleTreeNodeChangeListener());
    assertNotNull(node);
    assertEquals(ASTNode.Type.PLUS, node.getType());
    assertEquals(2, node.getChildCount());
  }

  @Test
  public void readMathML_withoutListener_returnsASTNode() throws XMLStreamException {
    // ASTNode.readMathMLFromString delegates to readMathML(String, listener)
    ASTNode node = ASTNode.readMathMLFromString(SIMPLE_MATH);
    assertNotNull(node);
    assertEquals(ASTNode.Type.PLUS, node.getType());
  }

  @Test
  public void readMathML_withParent_setsMathOnParent() throws XMLStreamException {
    // When a MathContainer parent is provided, the parser sets math on the parent directly
    // (the parent is pushed onto the element stack instead of a synthetic Constraint wrapper),
    // so the return value is null and the result is retrieved from the parent.
    KineticLaw parent = new SBMLDocument(2, 1).createModel().createReaction().createKineticLaw();
    SBMLReader reader = new SBMLReader();
    ASTNode returned = reader.readMathML(SIMPLE_MATH, new SimpleTreeNodeChangeListener(), parent);
    assertNull("readMathML with parent pushes onto parent — return value should be null", returned);
    assertNotNull("math must be set on the parent KineticLaw", parent.getMath());
    assertEquals(ASTNode.Type.PLUS, parent.getMath().getType());
  }

  @Test
  public void readMathML_invalidMathML_returnsNull() throws XMLStreamException {
    // A bare <p> element is not MathML; getAstNode should return null gracefully.
    SBMLReader reader = new SBMLReader();
    ASTNode node = reader.readMathML(
      "<p xmlns=\"http://www.w3.org/1999/xhtml\">not math</p>",
      new SimpleTreeNodeChangeListener());
    assertNull(node);
  }

  // ── readNotes ───────────────────────────────────────────────────────────────

  @Test
  public void readNotes_validXhtml_returnsXMLNode() throws XMLStreamException {
    SBMLReader reader = new SBMLReader();
    XMLNode notes = reader.readNotes(NOTES_XHTML, new SimpleTreeNodeChangeListener());
    assertNotNull(notes);
  }

  @Test
  public void readNotes_viaXMLNodeConversionMethod_returnsXMLNode() throws XMLStreamException {
    // XMLNode.convertStringToXMLNode uses readNotes internally
    XMLNode node = XMLNode.convertStringToXMLNode(NOTES_XHTML);
    assertNotNull(node);
  }

  // ── full SBML file parse (exercises processEndElement ASTNode path) ─────────

  @Test
  public void readSBMLFromStream_modelWithFunctions_parsesKineticLawMath() throws Exception {
    String resource = "/org/sbml/jsbml/xml/test/data/libsbml-test-data/l2v1-functions.xml";
    try (InputStream is = SBMLReaderReadingTest.class.getResourceAsStream(resource)) {
      assertNotNull("Test resource not found: " + resource, is);
      SBMLDocument doc = new SBMLReader().readSBMLFromStream(is);
      assertNotNull(doc);
      Model model = doc.getModel();
      assertNotNull(model);
      Reaction r = model.getReaction("reaction_1");
      assertNotNull(r);
      KineticLaw kl = r.getKineticLaw();
      assertNotNull(kl);
      assertNotNull("KineticLaw math must not be null", kl.getMath());
    }
  }

  @Test
  public void readSBMLFromStream_minimalModel_parsesSuccessfully() throws Exception {
    String resource = "/org/sbml/jsbml/xml/test/data/libsbml-test-data/l1v1-minimal.xml";
    try (InputStream is = SBMLReaderReadingTest.class.getResourceAsStream(resource)) {
      assertNotNull("Test resource not found: " + resource, is);
      SBMLDocument doc = new SBMLReader().readSBMLFromStream(is);
      assertNotNull(doc);
      assertNotNull(doc.getModel());
    }
  }
}
