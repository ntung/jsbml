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

import com.ctc.wstx.api.WstxInputProperties;
import com.ctc.wstx.stax.WstxInputFactory;
import org.apache.log4j.Logger;
import org.sbml.jsbml.*;
import org.sbml.jsbml.ASTNode.Type;
import org.sbml.jsbml.util.SimpleTreeNodeChangeListener;
import org.sbml.jsbml.util.StringTools;
import org.sbml.jsbml.util.TreeNodeChangeListener;
import org.sbml.jsbml.util.TreeNodeWithChangeSupport;
import org.sbml.jsbml.util.filters.Filter;
import org.sbml.jsbml.validator.offline.constraints.SBMLDocumentConstraints;
import org.sbml.jsbml.xml.XMLNode;
import org.sbml.jsbml.xml.parsers.*;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.*;
import java.util.Map.Entry;

import static java.text.MessageFormat.format;


/**
 * Provides all the methods to read a SBML file.
 *
 * <p>Warning: This class is not thread safe, so if using some thread to process SBML files in parallel,
 * you should make sure to use new instances of SBMLReader in each thread.</p>
 *
 * @author Marine Dumousseau
 * @author Andreas Dr&auml;ger
 * @author Nicolas Rodriguez
 * @author Clemens Wrzodek
 * @since 0.8
 */
public class SBMLReader {

  // Commenting out this static block as setting those system properties has some unwanted side
  // effect - for example, in OSGi where the properties are global,
  // The fact to use directly WstxOutputFactory and WstxInputFactory when creating the parser
  // should prevent the problem that setting those properties was fixing.
  //  static {
  //    // Making sure that we use the good XML library
  //    System.setProperty("javax.xml.stream.XMLOutputFactory", "com.ctc.wstx.stax.WstxOutputFactory");
  //    System.setProperty("javax.xml.stream.XMLInputFactory", "com.ctc.wstx.stax.WstxInputFactory");
  //    System.setProperty("javax.xml.stream.XMLEventFactory", "com.ctc.wstx.stax.WstxEventFactory");
  //  }

  private static final Map<String, Class<? extends AnnotationReader>> annotationParserClasses = new HashMap<>();

 static {
   
   // loading the annotation parsers once
   JSBML.loadClasses("org/sbml/jsbml/resources/cfg/annotationParsers.xml", annotationParserClasses);

  }

 
 /**
  * Contains all the initialized parsers.
  */
 private Map<String, ReadingParser> initializedParsers = new HashMap<>();

 /**
  * Annotation readers applied after the {@code <annotation>} element is fully parsed.
  */
 private final List<AnnotationReader> annotationParsers = new ArrayList<>();

  /**
   * The parent of the mathML we are parsing through the readMathML methods.
   * It allows parsing properly the FunctionDefinition contained in the mathML.
   *
   */
  private MathContainer astNodeParent;


  /**
   * Initialize a static instance of the core parser.
   */
  private static final SBMLCoreParser sbmlCoreParser = new SBMLCoreParser();

  /**
   * A {@link Logger} for this class.
   */
  private static final Logger logger = Logger.getLogger(SBMLReader.class);

  /**
   * Creates the ReadingParser instances and stores them in a
   * HashMap.
   */
  private void initializePackageParsers() {
    Logger logger = Logger.getLogger(SBMLReader.class);

    if (logger.isDebugEnabled()) {
      logger.debug("initializePackageParsers called.");
    }

    if ((initializedParsers == null) || (initializedParsers.isEmpty())) {
      initializedParsers = ParserManager.getManager().getReadingParsers();
      initializeAnnotationParsers();
    }

  }

  /**
   * Associates any unknown namespaces encountered in {@code startElement} with the anyXML
   * {@link AnnotationReader}, so they are not silently dropped during parsing.
   *
   * @param startElement the start element whose namespace declarations are inspected
   */
  private void addAnnotationParsers(StartElement startElement)
  {
    Iterator<Namespace> namespacesIterator = startElement.getNamespaces();

    while (namespacesIterator.hasNext()) {
      String namespaceURI = namespacesIterator.next().getNamespaceURI();

      if (initializedParsers.get(namespaceURI) == null) {
        initializedParsers.put(namespaceURI, initializedParsers.get("anyXML"));
      }
    }
  }


  /**
   * Initializes the packageParser {@link HashMap} of this class.
   *
   */
  public void initializeAnnotationParsers() {

    // TODO - make use of the java6 annotation to know which annotationParsers to initialize

    for (Class<? extends AnnotationReader> annotationReaderClass : annotationParserClasses.values()) {
      try {
        annotationParsers.add(annotationReaderClass.newInstance());
      } catch (InstantiationException | IllegalAccessException e) {
        e.printStackTrace();
      }
    }
  }


  /**
   * Reads the file passed as argument and write it to the console,
   * using the method {@link SBMLWriter#write(SBMLDocument, java.io.OutputStream)}.
   *
   * @param args the command line arguments, we are taking the first one as
   * the file name to read.
   *
   * @throws IOException if the file name is not valid.
   * @throws SBMLException if there are any problems reading or writing the SBML model.
   * @throws XMLStreamException if there are any problems reading or writing the XML file.
   */
  public static void main(String[] args) throws IOException, XMLStreamException, SBMLException  {
    // TODO: This class should not contain a main method; move to examples/.
    if (args.length < 1) {
      System.out
      .println("Usage: java org.sbml.jsbml.xml.stax.SBMLReader sbmlFileName");
      System.exit(0);
    }

    String fileName = args[0];

    SBMLDocument testDocument = new org.sbml.jsbml.SBMLReader().readSBMLFromFile(fileName);

    System.out.println("Number of namespaces: " + testDocument.getDeclaredNamespaces().size());

    for (String prefix : testDocument.getDeclaredNamespaces().keySet()) {
      System.out.println("PREFIX = "+prefix);
      String uri = testDocument.getDeclaredNamespaces().get(prefix);
      System.out.println("URI = "+uri);
    }

    System.out.println("Model NoRDFAnnotation String = \n@" + testDocument.getModel().getAnnotation().getNonRDFannotation() + "@");

    System.out.println("Model Annotation String = \n@" + testDocument.getModel().getAnnotationString() + "@");

    for (Species species : testDocument.getModel().getListOfSpecies()) {
      species.getAnnotationString();
    }

    new SBMLWriter().write(testDocument, System.out);

    /*
		String mathMLString1 = "<math xmlns=\"http://www.w3.org/1998/Math/MathML\">\n"
			+ "  <apply>\n"
            + "    <times/>\n"
            + "    <ci> uVol </ci>\n"
            + "    <ci> MKP3 </ci>\n"
            + "  </apply>\n"
            + "</math>\n";

		String mathMLString2 = "<math:math xmlns:math=\"http://www.w3.org/1998/Math/MathML\">\n"
			+ "  <math:apply>\n"
            + "    <math:times/>\n"
            + "    <math:ci> uVol </math:ci>\n"
            + "    <math:ci> MKP3 </math:ci>\n"
            + "  </math:apply>\n"
            + "</math:math>\n";

		String notesHTMLString = "<notes>\n" +
			"  <body xmlns=\"" + JSBML.URI_XHTML_DEFINITION + "\">\n " +
			"    <p>The model describes the double phosphorylation of MAP kinase by an ordered mechanism using the Michaelis-Menten formalism. " +
			"Two enzymes successively phosphorylate the MAP kinase, but one phosphatase dephosphorylates both sites.</p>\n" +
			"  </body>\n" +
			"</notes>";

		SBMLReader reader = new SBMLReader();

		Object astNodeObject1 = reader.readXMLFromString(mathMLString1);
		Object astNodeObject2 = reader.readXMLFromString(mathMLString2);
		Object xmlNodeObject = reader.readXMLFromString(notesHTMLString);

		System.out.println("MathML object = " + astNodeObject1);
		System.out.println("MathML object = " + ((AssignmentRule) astNodeObject2).getMath());
		System.out.println("Notes object = " + ((SBase) xmlNodeObject).getNotes().toXMLString());
     */
  }

  /**
   * Reads and parses the SBML document from the given file using a default change listener.
   *
   * @param file the SBML file to read
   * @return the parsed {@link SBMLDocument}
   * @throws XMLStreamException if the XML is malformed or cannot be parsed
   * @throws IOException if the file cannot be opened or read
   */
  public SBMLDocument readSBML(File file) throws IOException, XMLStreamException {
    return readSBML(file, null);
  }

  /**
   * Reads and parses the SBML document from the given file.
   *
   * @param file     a {@link File} containing valid SBML content
   * @param listener a {@link TreeNodeChangeListener} notified of model changes during parsing,
   *                 or {@code null} to use a no-op listener
   * @return the parsed {@link SBMLDocument}
   * @throws IOException        if the file cannot be opened or read
   * @throws XMLStreamException if the XML is malformed or cannot be parsed
   */
  public SBMLDocument readSBML(File file, TreeNodeChangeListener listener) throws IOException, XMLStreamException {
    FileInputStream stream = new FileInputStream(file);
    XMLStreamException exc1 = null;
    Object readObject = null;
    try {
      readObject = readXMLFromStream(stream, listener);
    } catch (XMLStreamException exc) {
      /*
       * Catching this exception makes sure that we have still the chance to
       * close the stream. Otherwise, it will stay opened, although the execution
       * of this method is over.
       */
      exc1 = exc;
    } finally {
      try {
        stream.close();
      } catch (IOException exc2) {
        // Ok, we lost. No chance to really close this stream. Heavy error.
        if (exc1 != null) {
          exc2.initCause(exc1);
        }
        throw exc2;
      } finally {
        if (exc1 != null) {
          throw exc1;
        }
      }
    }
    if (readObject instanceof SBMLDocument) {
      ((SBMLDocument) readObject).setLocationURI(file.toURI().toString());

      return (SBMLDocument) readObject;
    }
    throw new XMLStreamException(MessageFormat.format(
      "JSBML could not properly read file {0}. Please check if it contains valid SBML. If you think it is valid, please submit a bug report to the bug tracker of JSBML.",
      file.getAbsolutePath()));
  }

  /**
   * Reads and parses the SBML file at the given path.
   *
   * <p>Convenience alias for {@link #readSBMLFile(String)}.</p>
   *
   * @param file the path to the SBML file to read
   * @return the parsed {@link SBMLDocument}
   * @throws XMLStreamException if the file does not contain valid SBML
   * @throws IOException        if the file cannot be opened or read
   */
  public SBMLDocument readSBML(String file) throws XMLStreamException,
  IOException {
    return readSBMLFile(file);
  }

  /**
   * Reads and parses the SBML file at the given path.
   *
   * @param fileName the path to the SBML file to read
   * @return the parsed {@link SBMLDocument}
   * @throws XMLStreamException if the file does not contain valid SBML
   * @throws IOException        if the file cannot be opened or read
   */
  public SBMLDocument readSBMLFile(String fileName)
      throws XMLStreamException, IOException {
    return readSBML(new File(fileName));
  }


  /**
   * Reads an {@link SBMLDocument} from the given {@link XMLEventReader}.
   *
   * @param xmlEventReader an {@link XMLEventReader} positioned at the start of an SBML document
   * @param listener       a {@link TreeNodeChangeListener} notified of model changes during parsing
   * @return the parsed {@link SBMLDocument}
   * @throws XMLStreamException if the XML stream cannot be read or is not valid SBML
   */
  public SBMLDocument readSBML(XMLEventReader xmlEventReader, TreeNodeChangeListener listener)
      throws XMLStreamException {
    return (SBMLDocument) readXMLFromXMLEventReader(xmlEventReader, listener);
  }

  /**
   * Reads an {@link SBMLDocument} from the given {@link XMLEventReader} using a default change listener.
   *
   * @param xmlEventReader an {@link XMLEventReader} positioned at the start of an SBML document
   * @return the parsed {@link SBMLDocument}
   * @throws XMLStreamException if the XML stream cannot be read or is not valid SBML
   */
  public SBMLDocument readSBML(XMLEventReader xmlEventReader) throws XMLStreamException {
    return readSBML(xmlEventReader, new SimpleTreeNodeChangeListener());
  }

  /**
   * Parses a MathML string into an {@link ASTNode}.
   *
   * @param mathML   a MathML XML string to parse
   * @param listener a {@link TreeNodeChangeListener} notified of tree changes during parsing
   * @return an {@link ASTNode} representing the root of the parsed expression,
   *         or {@code null} if no math element could be extracted
   * @throws XMLStreamException if the MathML string cannot be parsed
   */
  public ASTNode readMathML(String mathML, TreeNodeChangeListener listener)
      throws XMLStreamException
  {
    if (logger.isDebugEnabled()) {
      logger.debug("SBMLReader.readMathML called");
    }

    return getAstNode(mathML, listener);
  }

  private ASTNode getAstNode(String mathML, TreeNodeChangeListener listener) throws XMLStreamException {
    Object object = readXMLFromString(mathML, listener);
    if (object instanceof Constraint) {
      ASTNode math = ((Constraint) object).getMath();
      if (math != null) {
        cleanTreeNode(math);
        return math;
      }
    }
    return null;
  }

  /**
   * Cleans the given node by removing user object(s) set during reading/parsing.
   * 
   * @param treeNode the node to be cleaned
   */
  private void cleanTreeNode(AbstractTreeNode treeNode)
  {
    // Go through the whole treeNode (using a fake filter!) to remove the variable that says that we were in the process of reading an xml stream.
    treeNode.filter(new Filter() {

      @Override
      public boolean accepts(Object o) {
        if (o instanceof TreeNodeWithChangeSupport) {
          if (((TreeNodeWithChangeSupport) o).isSetUserObjects()) {
            ((TreeNodeWithChangeSupport) o).userObjectKeySet().remove(JSBML.READING_IN_PROGRESS);
          } // else if (! ((o instanceof TreeNodeAdapter) || (o instanceof XMLNode))) {
          //	            System.out.println("######### user objects not set !!!!!!!! " + o + " class name = " + o.getClass().getSimpleName());
          //	          }
        }
        return false;
      }
    });

  }

  /**
   * Parses a MathML string into an {@link ASTNode}, with awareness of the parent container.
   * Providing a parent {@link MathContainer} enables correct resolution of
   * {@code FunctionDefinition} references during parsing.
   *
   * @param mathML   a MathML XML string to parse
   * @param listener a {@link TreeNodeChangeListener} notified of tree changes during parsing
   * @param parent   the {@link MathContainer} that will own the resulting {@link ASTNode};
   *                 used to resolve function definitions
   * @return an {@link ASTNode} representing the root of the parsed expression,
   *         or {@code null} if no math element could be extracted
   * @throws XMLStreamException if the MathML string cannot be parsed
   */
  public ASTNode readMathML(String mathML, TreeNodeChangeListener listener, MathContainer parent)
      throws XMLStreamException
  {
    astNodeParent = parent;

    if (logger.isDebugEnabled()) {
      logger.debug("SBMLReader.readMathML with parent called");
    }

    return getAstNode(mathML, listener);
  }

  /**
   * Parses a MathML string into an {@link ASTNode} using a default change listener.
   *
   * @param mathML a MathML XML string to parse
   * @return an {@link ASTNode} representing the root of the parsed expression,
   *         or {@code null} if no math element could be extracted
   * @throws XMLStreamException if the MathML string cannot be parsed
   */
  public ASTNode readMathML(String mathML) throws XMLStreamException {
    return readMathML(mathML, new SimpleTreeNodeChangeListener());
  }

  /**
   * Parses an XHTML notes string into an {@link XMLNode}.
   *
   * @param notesXHTML a notes XML string (e.g. {@code <notes><body>...</body></notes>})
   * @param listener   a {@link TreeNodeChangeListener} notified of tree changes during parsing
   * @return an {@link XMLNode} representing the parsed notes, or {@code null} if parsing fails
   * @throws XMLStreamException if the notes string cannot be parsed
   */
  public XMLNode readNotes(String notesXHTML, TreeNodeChangeListener listener)
      throws XMLStreamException {
    Object object = readXMLFromString(notesXHTML, listener);

    if (object instanceof Constraint) {
      Constraint constraint = ((Constraint) object);
      cleanTreeNode(constraint);

      if (constraint.isSetNotes()) {
        XMLNode notes = constraint.getNotes();
        if (notes != null) {
          return notes;
        }
      } else if (constraint.isSetMessage()) {
        XMLNode message = constraint.getMessage();
        if (message != null) {
          return message;
        }
      } else if (constraint.isSetAnnotation()) {
        XMLNode annotation = constraint.getAnnotation().getNonRDFannotation();
        if (annotation != null) {
          return annotation;
        }
      } else if (constraint.getUserObject(org.sbml.jsbml.SBMLReader.UNKNOWN_XML_NODE) != null) {
        return (XMLNode) constraint.getUserObject(org.sbml.jsbml.SBMLReader.UNKNOWN_XML_NODE);
      }
    }
    else if (object instanceof XMLNode)
    {
      // Should not happen at the moment but could if readXMLFromString directly returned
      // the XMLNode instead of a Constraint object.
      return (XMLNode) object;
    }

    logger.warn("Tried to read @" + notesXHTML + "@ as XMLNode without success ! ");

    return null;
  }

  /**
   * Parses an XHTML notes string into an {@link XMLNode} using a default change listener.
   *
   * @param notesXHTML a notes XML string (e.g. {@code <notes><body>...</body></notes>})
   * @return an {@link XMLNode} representing the parsed notes, or {@code null} if parsing fails
   * @throws XMLStreamException if the notes string cannot be parsed
   */
  public XMLNode readNotes(String notesXHTML) throws XMLStreamException {
    return readNotes(notesXHTML, new SimpleTreeNodeChangeListener());
  }

  /**
   * Reads and parses a SBML document from the given input stream.
   *
   * @param stream   an {@link InputStream} containing valid SBML XML content
   * @param listener a {@link TreeNodeChangeListener} notified of model changes during parsing
   * @return the parsed {@link SBMLDocument}
   * @throws XMLStreamException if the stream cannot be read or does not contain valid SBML
   */
  public SBMLDocument readSBMLFromStream(InputStream stream, TreeNodeChangeListener listener)
      throws XMLStreamException {
    WstxInputFactory inputFactory = new WstxInputFactory();
    // see https://github.com/OWASP/CheatSheetSeries/blob/master/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.md
    inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    inputFactory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    XMLEventReader xmlEventReader = inputFactory.createXMLEventReader(stream);
    return (SBMLDocument) readXMLFromXMLEventReader(xmlEventReader, listener);
  }

  /**
   * Reads and parses a SBML document from the given input stream using a default change listener.
   *
   * @param stream an {@link InputStream} containing valid SBML XML content
   * @return the parsed {@link SBMLDocument}
   * @throws XMLStreamException if the stream cannot be read or does not contain valid SBML
   */
  public SBMLDocument readSBMLFromStream(InputStream stream) throws XMLStreamException {
    return readSBMLFromStream(stream, new SimpleTreeNodeChangeListener());
  }

  /**
   * Reads an XML document from the given stream. The stream must contain a self-contained
   * fragment of an SBML document (full model, math element, or notes element).
   *
   * @param stream   an {@link InputStream} containing SBML XML content
   * @param listener a {@link TreeNodeChangeListener} notified of tree changes during parsing
   * @return the top-level object parsed from the stream: an {@link SBMLDocument}, {@link ASTNode},
   *         {@link XMLNode}, or {@code null} if the stream is empty
   * @throws XMLStreamException if the stream cannot be read or parsed
   */
  private Object readXMLFromStream(InputStream stream, TreeNodeChangeListener listener)
      throws XMLStreamException {
    WstxInputFactory inputFactory = new WstxInputFactory();

    try {
      // see https://groups.google.com/d/msg/jsbml-development/cckEJPYNzQY/5ynmIbqNCAAJ for why we did set this value
      inputFactory.setProperty(WstxInputProperties.P_MAX_ELEMENT_DEPTH, 5000);

      // see https://github.com/OWASP/CheatSheetSeries/blob/master/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.md
      inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
      inputFactory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    } catch(IllegalArgumentException e) {
      // do nothing - the XML libraries used do not support this property for some reason
    }

    XMLEventReader xmlEventReader = inputFactory.createXMLEventReader(stream);
    return readXMLFromXMLEventReader(xmlEventReader, listener);
  }


  /** Mutable state shared across XML event handlers during a single parse. */
  private static final class XmlParseState {
    String encoding = null;
    boolean isNested = false;
    boolean isText = false;
    boolean isHTML = false;
    boolean isInsideAnnotation = false;
    int annotationDeepness = -1;
    int level = -1;
    int version = -1;
    Object lastElement = null;
    QName currentNode = null;
    ReadingParser parser = null;
    final Stack<Object> sbmlElements = new Stack<Object>();
  }

  private Object readXMLFromXMLEventReader(XMLEventReader xmlEventReader, TreeNodeChangeListener listener)
      throws XMLStreamException {
    initializePackageParsers();
    XmlParseState state = new XmlParseState();

    while (xmlEventReader.hasNext()) {
      XMLEvent event = xmlEventReader.nextEvent();

      if (event.isStartDocument()) {
        handleStartDocument((StartDocument) event, state);
      } else if (event.isStartElement()) {
        handleStartElement(event.asStartElement(), state, listener);
      } else if (event.isCharacters()) {
        handleCharacters(event.asCharacters(), state);
      } else if (event.isEndElement()) {
        Object result = handleEndElement(event.asEndElement(), state);
        if (result != null) {
          return result;
        }
      }
    }

    // Reached end of XML fragment without finding a 'sbml' element —
    // likely parsing a standalone math or notes string.
    if (logger.isDebugEnabled()) {
      logger.debug("no more XMLEvent: stack.size = " + state.sbmlElements.size());
      logger.debug("no more XMLEvent: stack = " + state.sbmlElements);
    }

    initializedParsers.remove("");
    return state.sbmlElements.isEmpty() ? null : state.sbmlElements.peek();
  }

  private void handleStartDocument(StartDocument startDocument, XmlParseState state) {
    if (startDocument.encodingSet()) {
      state.encoding = startDocument.getCharacterEncodingScheme();
    }
  }

  private void handleStartElement(StartElement startElement, XmlParseState state, TreeNodeChangeListener listener) {
    state.currentNode = startElement.getName();
    state.isNested = false;
    state.isText = false;

    addAnnotationParsers(startElement);

    String localPart = state.currentNode.getLocalPart();
    if (localPart.equals("sbml")) {
      initializeSbmlDocument(startElement, state, listener);
    } else if (state.lastElement == null) {
      initializeFreeXmlParsing(state);
    } else if (localPart.equals("annotation")) {
      handleAnnotationStart(state);
    } else if (state.isInsideAnnotation) {
      state.annotationDeepness++;
    } else if (localPart.equals("notes") || localPart.equals("message")) {
      handleNotesOrMessageStart(state);
    }

    if (state.isInsideAnnotation && logger.isDebugEnabled()) {
      logger.debug("startElement: local part = " + localPart);
    }

    // annotationDeepness = 0 is the annotation element; pass everything inside it to the anyXML parser
    state.parser = processStartElement(startElement, state.currentNode, state.isHTML, state.sbmlElements, (state.annotationDeepness > 0));
    state.lastElement = state.sbmlElements.peek();
  }

  private void initializeSbmlDocument(StartElement startElement, XmlParseState state, TreeNodeChangeListener listener) {
    SBMLDocument sbmlDocument = new SBMLDocument();
    sbmlDocument.putUserObject(JSBML.READING_IN_PROGRESS, Boolean.TRUE);

    if (state.encoding != null) {
      sbmlDocument.putUserObject(SBMLDocumentConstraints.XML_DECLARED_ENCODING, state.encoding);
    }
    if (state.currentNode.getPrefix() != null && !state.currentNode.getPrefix().trim().isEmpty()) {
      sbmlDocument.putUserObject(JSBML.ELEMENT_XML_PREFIX, state.currentNode.getPrefix());
    }

    sbmlDocument.addTreeNodeChangeListener(listener == null ? new SimpleTreeNodeChangeListener() : listener);

    for (
      Iterator<Attribute> iterator = startElement.getAttributes(); iterator.hasNext();) {
      Attribute attr = iterator.next();
      if (attr.getName().toString().equals("level")) {
        state.level = StringTools.parseSBMLInt(attr.getValue());
        sbmlDocument.setLevel(state.level);
      } else if (attr.getName().toString().equals("version")) {
        state.version = StringTools.parseSBMLInt(attr.getValue());
        sbmlDocument.setVersion(state.version);
      }
    }
    state.sbmlElements.push(sbmlDocument);
  }

  private void initializeFreeXmlParsing(XmlParseState state) {
    // Hack: push a fake Constraint so math/notes/annotation fragments can be parsed standalone.
    // If an explicit parent container was set on this reader, use it instead.
    // TODO: will not work with arbitrary SBML part
    // TODO: set the Model element in the Constraint so FunctionDefinitions are recognized.
    if (astNodeParent != null) {
      state.sbmlElements.push(astNodeParent);
    } else {
      state.sbmlElements.push(new Constraint(3, 1));
    }

    String localPart = state.currentNode.getLocalPart();
    if (localPart.equals("notes") || localPart.equals("message") || localPart.equals("annotation")) {
      initializedParsers.put("", sbmlCoreParser);
      SBase sbase = (SBase) state.sbmlElements.firstElement();
      String sbmlNamespace = JSBML.getNamespaceFrom(sbase.getLevel(), sbase.getVersion());
      state.currentNode = new QName(sbmlNamespace, localPart);
    } else if (localPart.equals("math")) {
      initializedParsers.put("", new MathMLStaxParser());
      initializedParsers.put(ASTNode.URI_MATHML_DEFINITION, new MathMLStaxParser());
      state.currentNode = new QName(ASTNode.URI_MATHML_DEFINITION, "math");
    }
    // TODO: add something generic for the L3 packages or change all parsers to work when contextObject is null
  }

  private void handleAnnotationStart(XmlParseState state) {
    SBase sbmlDoc = (SBase) state.sbmlElements.firstElement();
    String sbmlNamespace = JSBML.getNamespaceFrom(sbmlDoc.getLevel(), sbmlDoc.getVersion());

    if (state.currentNode.getNamespaceURI().equals(sbmlNamespace)) {
      if (state.isInsideAnnotation) {
        logger.warn("Starting to read a new annotation element while the previous annotation element is not finished.");
      }
      state.isInsideAnnotation = true;
    }
  }

  private void handleNotesOrMessageStart(XmlParseState state) {
    SBase firstElement = (SBase) state.sbmlElements.firstElement();

    if (firstElement instanceof SBMLDocument) {
      String sbmlNamespace = JSBML.getNamespaceFrom(firstElement.getLevel(), firstElement.getVersion());
      if (state.currentNode.getNamespaceURI().equals(sbmlNamespace)) {
        state.isHTML = true;
      }
    } else if (firstElement instanceof Constraint) {
      // reading a partial document, e.g. via SBMLReader#readNotes
      state.isHTML = true;
    }
  }

  private void handleCharacters(Characters characters, XmlParseState state) {
    if (!characters.isWhiteSpace()) {
      state.isText = true;
    }
    if ((!state.sbmlElements.isEmpty() && (state.sbmlElements.peek() instanceof XMLNode)) || state.isHTML || state.isInsideAnnotation) {
      state.isText = true; // preserve whitespace/formatting inside HTML blocks
    }

    if (state.parser != null && !state.sbmlElements.isEmpty() && state.isText) {
      if (state.isHTML) {
        state.parser = initializedParsers.get(JSBML.URI_XHTML_DEFINITION); // TODO: probably not needed
      } else if (state.isInsideAnnotation) {
        state.parser = initializedParsers.get("anyXML");
      }

      if (logger.isDebugEnabled()) {
        logger.debug(" PackageParser = " + state.parser.getClass().getName());
        logger.debug(" Characters = @" + characters.getData() + "@");
      }

      if (state.currentNode != null) {
        state.parser.processCharactersOf(state.currentNode.getLocalPart(), characters.getData(), state.sbmlElements.peek());
      } else {
        state.parser.processCharactersOf(null, characters.getData(), state.sbmlElements.peek());
      }
    } else if (state.isText) {
      logger.warn(MessageFormat.format("Some characters cannot be read: {0}", characters.getData()));
      if (logger.isDebugEnabled()) {
        logger.debug("PackageParser = " + state.parser);
        if (state.sbmlElements.isEmpty()) {
          logger.debug("The Object Stack is empty!");
        } else {
          logger.debug("The current Object in the stack is: " + state.sbmlElements.peek());
        }
      }
    }
  }

  private Object handleEndElement(EndElement endElement, XmlParseState state) {
    state.lastElement = state.sbmlElements.peek();
    state.currentNode = endElement.getName();

    if (state.currentNode != null) {
      boolean isSBMLelement = isSbmlNamespaceElement(state);
      String localPart = state.currentNode.getLocalPart();

      if (localPart.equals("annotation") && isSBMLelement) {
        state.isInsideAnnotation = false;
        state.annotationDeepness = -1;
        processAnnotationEnd(state.lastElement);
      } else if (state.isInsideAnnotation) {
        state.annotationDeepness--;
      } else if ((localPart.equals("notes") || localPart.equals("message")) && isSBMLelement) {
        state.isHTML = false;
      }
    }

    // processEndElement returns null until the closing </sbml> element is reached
    SBMLDocument sbmlDocument = processEndElement(state.currentNode, state.isNested, state.isText, state.isHTML,
      state.level, state.version, state.parser, state.sbmlElements, (state.annotationDeepness >= 0));

    state.currentNode = null;
    state.isNested = false;
    state.isText = false;

    return sbmlDocument;
  }

  private boolean isSbmlNamespaceElement(XmlParseState state) {
    if (state.sbmlElements.firstElement() instanceof SBase) {
      SBase sbmlDoc = (SBase) state.sbmlElements.firstElement();
      String sbmlNamespace = JSBML.getNamespaceFrom(sbmlDoc.getLevel(), sbmlDoc.getVersion());
      return state.currentNode.getNamespaceURI().equals(sbmlNamespace);
    }
    return true;
  }

  private void processAnnotationEnd(Object lastElement) {
    if (lastElement instanceof Annotation) {
      Annotation annotation = (Annotation) lastElement;
      Object parent = annotation.getParent();
      if (parent instanceof SBase) {
        for (AnnotationReader annoReader : annotationParsers) {
          annoReader.processAnnotation((SBase) parent);
        }
      } else {
        logger.error(format(
          "End of <annotation>: expected parent of Annotation to be an SBase but found {0}. Skipping annotation parsing.",
          parent == null ? "null" : parent.getClass().getCanonicalName()
        ));
      }
    } else {
      logger.error(format(
        "End of <annotation>: expected top stack element to be an Annotation but found {0}. Skipping annotation parsing.",
        lastElement == null ? "null" : lastElement.getClass().getCanonicalName()
      ));
    }
  }

  /**
   * Reads and parses a SBML document from the given XML string.
   *
   * @param xml      a string containing a complete, valid SBML document
   * @param listener a {@link TreeNodeChangeListener} notified of model changes during parsing
   * @return the parsed {@link SBMLDocument}
   * @throws XMLStreamException if the string does not contain valid SBML
   */
  public SBMLDocument readSBMLFromString(String xml, TreeNodeChangeListener listener) throws XMLStreamException {
    Object readObject = readXMLFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), listener);
    if (readObject instanceof SBMLDocument) {
      return (SBMLDocument) readObject;
    }
    throw new XMLStreamException("The given file seems not to be a valid SBMl file. Please check it using the SBML online validator.");
  }

  /**
   * Reads and parses a SBML document from the given XML string using a default change listener.
   *
   * @param xml a string containing a complete, valid SBML document
   * @return the parsed {@link SBMLDocument}
   * @throws XMLStreamException if the string does not contain valid SBML
   */
  public SBMLDocument readSBMLFromString(String xml) throws XMLStreamException {
    return readSBMLFromString(xml, new SimpleTreeNodeChangeListener());
  }

  /**
   * Reads an XML string that is a self-contained fragment of an SBML model
   * (full document, math element, or notes element).
   *
   * @param xml      an XML string containing SBML content
   * @param listener a {@link TreeNodeChangeListener} notified of tree changes during parsing
   * @return the top-level parsed object: {@link SBMLDocument}, {@link ASTNode},
   *         {@link XMLNode}, or {@code null}
   * @throws XMLStreamException if the XML string cannot be parsed
   */
  private Object readXMLFromString(String xml, TreeNodeChangeListener listener)
      throws XMLStreamException {
    return readXMLFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), listener);
  }


  /**
   * Processes a {@link StartElement} event: selects the appropriate parser, delegates namespace
   * and attribute processing, and pushes newly created model objects onto the element stack.
   *
   * @param startElement       the SAX/StAX start-element event to process
   * @param currentNode        the qualified name of the element being opened
   * @param isHTML             {@code true} when inside a notes/message XHTML block
   * @param sbmlElements       the stack of in-progress model objects
   * @param isInsideAnnotation {@code true} when inside an {@code <annotation>} block, causing
   *                           the anyXML parser to handle all child content
   * @return the {@link ReadingParser} selected for this element, or {@code null} if none found
   */
  private ReadingParser processStartElement(StartElement startElement, QName currentNode,
    Boolean isHTML, Stack<Object> sbmlElements, boolean isInsideAnnotation)
  {
    ReadingParser parser = null;

    String elementNamespace = currentNode.getNamespaceURI();

    if (logger.isDebugEnabled()) {
      logger.debug("processStartElement: " + currentNode.getLocalPart() + ", " + elementNamespace);
    }

    // To be able to parse all the SBML file, the sbml node
    // should have been read first.
    if (!sbmlElements.isEmpty() && (initializedParsers != null)) {

      // All the element should have a namespace.
      if (elementNamespace != null) {

        // TODO - change the way we deal with notes, message and annotation and just use the context object ! If XMLNode, we use the 'anyXML' parser
        // it will allow us to deal easily with unknowns XML elements.

        parser = initializedParsers.get(elementNamespace);
        // if the current node is a notes or message element
        // and the matching ReadingParser is an XMLNodeReader,
        // we need to set the typeOfNotes variable of the
        // XMLNodeReader instance.
        if (currentNode.getLocalPart().equals("notes")
            || currentNode.getLocalPart().equals("message")
            || currentNode.getLocalPart().equals("annotation"))
        {
          ReadingParser sbmlparser = initializedParsers.get("anyXML");
          SBase sbmlDoc = (SBase) sbmlElements.firstElement();
          String sbmlNamespace = JSBML.getNamespaceFrom(sbmlDoc.getLevel(), sbmlDoc.getVersion());

          if (sbmlparser instanceof XMLNodeReader && elementNamespace.equals(sbmlNamespace)) {
            // TODO - update only when the top level element from the stack is an SBase ??
            XMLNodeReader notesParser = (XMLNodeReader) sbmlparser;
            notesParser.setTypeOfNotes(currentNode.getLocalPart());
          }
        }

        if (parser != null) {

          Iterator<Namespace> nam = startElement.getNamespaces();
          Iterator<Attribute> att = startElement.getAttributes();
          boolean hasAttributes = att.hasNext();
          boolean hasNamespace = nam.hasNext();

          // if the object on the top of the stack is an XMLNode, we always use the XMLNodeReader
          if (isInsideAnnotation || (sbmlElements.peek() instanceof XMLNode))
          {
            parser = initializedParsers.get("anyXML");
          }

          // All the subNodes of SBML are processed.
          if (!currentNode.getLocalPart().equals("sbml"))
          {
            Object processedElement = parser.processStartElement(currentNode.getLocalPart(),
              currentNode.getNamespaceURI(),
              currentNode.getPrefix(), hasAttributes,
              hasNamespace, sbmlElements.peek());

            if (processedElement != null) {
              // TODO - we won't need this code any more if the list of child is stored directly in the ASTNode facade
              // TODO - try to remove this code and check if the ASTNode2 can still pass the sbml-test-suite
              if (processedElement instanceof ASTNode) {
                ASTNode astNode = (ASTNode) processedElement;
                if (currentNode.getLocalPart().equals("cn") && hasAttributes) {
                  Object object = sbmlElements.peek();

                  while (att.hasNext()) {

                    Attribute attribute = att.next();
                    String attributeName = attribute.getName().getLocalPart();

                    if (attributeName.equals("type")) {
                      String type = attribute.getValue();

                      if (type.equalsIgnoreCase("integer")) {
                        astNode.setType(Type.INTEGER);
                      } else if(type.equalsIgnoreCase("e-notation")) {
                        astNode.setType(Type.REAL_E);
                      } else if(type.equalsIgnoreCase("rational")) {
                        astNode.setType(Type.RATIONAL);
                      }

                      if (object instanceof ASTNode) {
                        ASTNode parent = (ASTNode) object;

                        // we need to remove the last child as the hierarchy of children is stored in the ASTNode2 and not directly in the ASTNode
                        parent.removeChild(parent.getChildCount() - 1);
                        parent.addChild(astNode);
                      } // else the parent can be directly a MathContainer - nothing to do in this case.
                    }
                  }
                }
                if (currentNode.getLocalPart().equals("csymbol") && hasAttributes) {
                  Object object = sbmlElements.peek();

                  while (att.hasNext()) {

                    Attribute attribute = att.next();
                    String attributeName = attribute.getName().getLocalPart();

                    if (attributeName.equals("definitionURL")) {
                      String type = attribute.getValue();

                      if (type.equalsIgnoreCase(ASTNode.URI_TIME_DEFINITION)) {
                        astNode.setType(Type.NAME_TIME);
                      } else if(type.equalsIgnoreCase(ASTNode.URI_DELAY_DEFINITION)) {
                        astNode.setType(Type.FUNCTION_DELAY);
                      } else if(type.equalsIgnoreCase(ASTNode.URI_AVOGADRO_DEFINITION)) {
                        astNode.setType(Type.NAME_AVOGADRO);
                      } else if(type.equalsIgnoreCase(ASTNode.URI_RATE_OF_DEFINITION)) {
                        astNode.setType(Type.FUNCTION_RATE_OF);
                      }

                      if (object instanceof ASTNode) {
                        ASTNode parent = (ASTNode) object;

                        // we need to remove the last child as the hierarchy of children is stored in the ASTNode2 and not directly in the ASTNode
                        parent.removeChild(parent.getChildCount() - 1);
                        parent.addChild(astNode);
                      } // else the parent can be directly a MathContainer - nothing to do in this case.
                    }
                  }
                }

                // reset the Iterator of attributes so that they can be processed correctly in #processAttributes(...)
                att = startElement.getAttributes();
              }

              sbmlElements.push(processedElement);
              if (processedElement instanceof TreeNodeWithChangeSupport) {
                ((TreeNodeWithChangeSupport) processedElement).putUserObject(JSBML.READING_IN_PROGRESS, Boolean.TRUE);
              }
            } else {
              // It is normal to sometimes have null returned as some of the
              // XML elements are ignored or do not produce a new java object (like 'apply' in mathML).
            }
          }

          // process the namespaces
          processNamespaces(nam, currentNode,sbmlElements, parser, hasAttributes);

          // Process the attributes
          processAttributes(att, currentNode, sbmlElements, parser, hasAttributes, isInsideAnnotation);

        } else {
          logger.warn(MessageFormat.format("Cannot find a parser for the {0} namespace", elementNamespace));
        }
      } else {
        logger.warn(MessageFormat.format("Cannot find a parser for the {0} namespace", elementNamespace));
      }
    }

    return parser;
  }

  // TODO: the attributes hasAttributes, hasNamespace, isLastAttribute and  isLastNamespace are probably not needed for XML reading.

  /**
   * Processes all namespace declarations on the current element, notifying both the element's
   * own parser and any secondary parser registered for each namespace URI.
   *
   * @param nam           iterator over the namespace declarations on the current element
   * @param currentNode   the qualified name of the element whose namespaces are being processed
   * @param sbmlElements  the stack of in-progress model objects; the top element receives the call
   * @param parser        the primary {@link ReadingParser} for the current element
   * @param hasAttributes {@code true} if the current element has attributes
   */
  private void processNamespaces(Iterator<Namespace> nam, QName currentNode,
    Stack<Object> sbmlElements,	ReadingParser parser, boolean hasAttributes)
  {
    ReadingParser namespaceParser;

    while (nam.hasNext()) {
      Namespace namespace = nam.next();
      boolean isLastNamespace = !nam.hasNext();
      namespaceParser = initializedParsers.get(namespace.getNamespaceURI());

      logger.debug("processNamespaces: " + namespace.getNamespaceURI());

      // Calling the currentNode parser to store all the declared namespaces
      parser.processNamespace(currentNode.getLocalPart(),
        namespace.getNamespaceURI(),
        namespace.getName().getPrefix(),
        namespace.getName().getLocalPart(),
        hasAttributes, isLastNamespace,
        sbmlElements.peek());

      // Calling each corresponding parser, in case they want to initialize things for the currentNode
      if ((namespaceParser != null) && !namespaceParser.getClass().equals(parser.getClass())) {

        logger.debug("processNamespaces 2e parser: " + namespaceParser);

        namespaceParser.processNamespace(currentNode.getLocalPart(),
          namespace.getNamespaceURI(),
          namespace.getName().getPrefix(),
          namespace.getName().getLocalPart(),
          hasAttributes, isLastNamespace,
          sbmlElements.peek());
      } else if (namespaceParser == null) {
        // These namespaces would be treated by the anyXML parser
        logger.warn(MessageFormat.format("Cannot find a parser for the {0} namespace", namespace.getNamespaceURI()));
      }
    }

  }

  /**
   * Processes all attribute declarations on the current element, dispatching each attribute
   * to the appropriate parser and storing any unrecognised attributes as unknown.
   *
   * @param att                iterator over the attributes of the current element
   * @param currentNode        the qualified name of the element whose attributes are being processed
   * @param sbmlElements       the stack of in-progress model objects; the top element receives each call
   * @param parser             the primary {@link ReadingParser} used when no namespace-specific parser is found
   * @param hasAttributes      {@code true} if the current element has attributes (passed through to the parser)
   * @param isInsideAnnotation {@code true} when inside an {@code <annotation>} block; routes all
   *                           namespaced attributes to the anyXML parser
   */
  private void processAttributes(Iterator<Attribute> att, QName currentNode,
    Stack<Object> sbmlElements, ReadingParser parser, boolean hasAttributes,
    boolean isInsideAnnotation)
  {
    ReadingParser attributeParser;

    while (att.hasNext()) {

      Attribute attribute = att.next();
      boolean isLastAttribute = !att.hasNext();
      QName attributeName = attribute.getName();

      if (!attribute.getName().getNamespaceURI().isEmpty()) {
        String attributeNamespaceURI = attribute.getName().getNamespaceURI();

        if (isInsideAnnotation)
        {
          attributeParser = initializedParsers.get("anyXML");
        }
        else
        {
          attributeParser = initializedParsers.get(attributeNamespaceURI);
        }

      } else {
        attributeParser = parser;
      }

      if (attributeParser != null) {
        boolean isAttributeRead = attributeParser.processAttribute(
          currentNode.getLocalPart(),
          attributeName.getLocalPart(),
          attribute.getValue(),
          attributeName.getNamespaceURI(),
          attributeName.getPrefix(),
          isLastAttribute, sbmlElements.peek());

        if (!isAttributeRead) {
          // store the unknownAttribute
          AbstractReaderWriter.processUnknownAttribute(attributeName.getLocalPart(), attributeName.getNamespaceURI(),
            attribute.getValue(), attributeName.getPrefix(), sbmlElements.peek());
        }

      } else {
        logger.warn("Cannot find a parser for the " + attribute.getName().getNamespaceURI() + " namespace");
      }
    }
  }


  /**
   * Processes an end-element event: notifies the active parser, pops the element from the
   * stack, and — when the closing {@code </sbml>} tag is reached — finalises all parsers
   * and returns the completed document.
   *
   * @param currentNode        the qualified name of the element being closed
   * @param isNested           {@code true} if the element is nested inside another element of the same type
   * @param isText             {@code true} if the element contained character data
   * @param isHTML             {@code true} if the element was inside a notes/message XHTML block
   * @param level              the SBML Level declared on the root {@code <sbml>} element
   * @param version            the SBML Version declared on the root {@code <sbml>} element
   * @param parser             the {@link ReadingParser} active when this element was opened
   * @param sbmlElements       the stack of in-progress model objects
   * @param isInsideAnnotation {@code true} when inside an {@code <annotation>} block
   * @return the completed {@link SBMLDocument} when the root element is closed, or {@code null}
   *         for all other elements
   */
  private SBMLDocument processEndElement(QName currentNode, Boolean isNested, Boolean isText,
    Boolean isHTML, int level, int version, ReadingParser parser,
    Stack<Object> sbmlElements, boolean isInsideAnnotation)
  {
    if (logger.isDebugEnabled()) {
      logger.debug("event.isEndElement: stack.size = " + sbmlElements.size());
      logger.debug("event.isEndElement: element name = " + currentNode.getLocalPart());

      if (currentNode.getLocalPart().equals("kineticLaw") || currentNode.getLocalPart().startsWith("listOf")
          || currentNode.getLocalPart().equals("math")) {
        logger.debug("event.isEndElement: stack = " + sbmlElements);
      }
    }

    // check that the stack did not increase before and after an element?
    if (initializedParsers != null) {
      String elementNamespaceURI = currentNode.getNamespaceURI();
      parser = initializedParsers.get(elementNamespaceURI);

      if (isInsideAnnotation)
      {
        parser = initializedParsers.get("anyXML");
      }

      // process the end of the element.
      if (!sbmlElements.isEmpty() && (parser != null)) {

        if (logger.isDebugEnabled()) {
          logger.debug("event.isEndElement: calling parser.processEndElement " + parser.getClass());
        }

        boolean popElementFromTheStack = parser.processEndElement(currentNode.getLocalPart(),
          currentNode.getPrefix(), isNested, sbmlElements.peek());
        // remove the top of the SBMLElements stack at the
        // end of an element if this element is not the sbml
        // element.
        if (!currentNode.getLocalPart().equals("sbml")) {
          if (popElementFromTheStack) {
            sbmlElements.pop();
          }
        } else {

          logger.debug("event.isEndElement: sbml element found");

          // process the end of the document and return
          // the final SBMLDocument
          if (sbmlElements.peek() instanceof SBMLDocument) {
            SBMLDocument sbmlDocument = (SBMLDocument) sbmlElements.peek();

            Iterator<Entry<String, ReadingParser>> iterator = initializedParsers.entrySet().iterator();
            List<String> readingParserClasses = new ArrayList<>();

            // Calling endDocument for all parsers
            while (iterator.hasNext()) {
              Entry<String, ReadingParser> entry = iterator.next();
              ReadingParser sbmlParser = entry.getValue();

              if (!readingParserClasses.contains(sbmlParser.getClass().getCanonicalName())) {

                readingParserClasses.add(sbmlParser.getClass().getCanonicalName());

                logger.debug("event.isEndElement: EndDocument found: parser = " + sbmlParser.getClass());

                sbmlParser.processEndDocument(sbmlDocument);

                // call endDocument only on the parser associated with the namespaces
                // declared on the sbml document ??.
              }
            }

            logger.debug("event.isEndElement: EndDocument returned.");

            return sbmlDocument;

          } else {
            // At the end of a sbml node, the
            // SBMLElements stack must contain only a
            // SBMLDocument instance.
            // Otherwise, there is a syntax error in the
            // SBML document
            logger.warn("!!! event.isEndElement: there is a problem in your SBML file !!!!");
            logger.warn("Found an element '" + sbmlElements.peek().getClass().getCanonicalName() +
              "', expected " + SBMLDocument.class.getCanonicalName());
          }
        }
      } else {
        // If SBMLElements.isEmpty => there is a syntax
        // error in the SBMLDocument
        // If parser == null => there is no parser for
        // the namespace of this element
        logger.warn("!!! event.isEndElement: there is a problem in your SBML file !!!!");
        logger.warn("This should never happen, there is probably a problem with the parsers used." +
            "\n Try to check if one needed parser is missing or if you are using a parser in development.");
      }
    } else {
      // The initialized parsers map should be initialized as soon as there is a sbml node.
      // If it is null, there is a syntax error in the SBML
      // file.
      logger.warn("The parsers are not initialized, this should not happen !!!");
    }

    // We return null as long as we did not find the SBMLDocument closing tag
    return null;
  }

}
