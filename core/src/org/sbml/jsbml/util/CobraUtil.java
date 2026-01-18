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

package org.sbml.jsbml.util;

import java.util.List;
import java.util.Properties;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.xml.stream.XMLStreamException;

import org.apache.log4j.Logger;
import org.sbml.jsbml.JSBML;
import org.sbml.jsbml.SBase;
import org.sbml.jsbml.xml.XMLNode;

/**
 * Contains some useful methods to manipulate 'COBRA SBML'
 *
 * @author Nicolas Rodriguez
 * @since 1.1
 */
public class CobraUtil {
  
  private static final transient Logger logger = Logger.getLogger(CobraUtil.class);

  /**
   * Known COBRA-style keys used in notes. This list is intentionally conservative;
   * fields not in this list can still be accepted if they look like all-uppercase
   * identifiers (e.g., GENE_ASSOCIATION).
   */
  private static final Set<String> KNOWN_COBRA_KEYS = new HashSet<String>(Arrays.asList(
      "FORMULA",
      "CHARGE",
      "HEPATONET_1.0_ABBREVIATION",
      "EHMN_ABBREVIATION",
      "INCHI",
      "GENE_ASSOCIATION",
      "SUBSYSTEM",
      "EC Number",
      "Confidence Level",
      "AUTHORS",
      "NOTES"
  ));

  /**
   * Heuristic to decide whether a given key is likely to be a structured COBRA
   * property key rather than arbitrary free text.
   */
  private static boolean isLikelyCobraKey(String key) {
    if (key == null) {
      return false;
    }
    key = key.trim();
    if (key.isEmpty()) {
      return false;
    }

    // Explicitly known keys (may contain lowercase, spaces, etc.)
    if (KNOWN_COBRA_KEYS.contains(key)) {
      return true;
    }

    // If the key contains lowercase letters and is not explicitly known,
    // treat it as free text and ignore it.
    for (int i = 0; i < key.length(); i++) {
      if (Character.isLowerCase(key.charAt(i))) {
        return false;
      }
    }

    // Only allow reasonably "identifier-like" keys:
    // uppercase letters, digits, underscore, dot, and space.
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (!(Character.isUpperCase(c) || Character.isDigit(c)
          || c == '_' || c == '.' || c == ' ')) {
        return false;
      }
    }

    return true;
  }
  
  /**
   * Parses the notes of the given {@link SBase} element.
   *
   * <p>The notes are expecting to have some 'p' elements with
   * a content of the form 'KEY: VALUE'. The key will be used for the property
   * name and the value will be the property value. The key is inserted even is the value
   * is an empty String.
   * 
   * <p>Below are examples for a species and a reaction:
   * 
   * <pre>
   * &lt;body xmlns="http://www.w3.org/1999/xhtml"&gt;
   *   &lt;p&gt;FORMULA: H4N&lt;/p&gt;
   *   &lt;p&gt;CHARGE: 1&lt;/p&gt;
   *   &lt;p&gt;HEPATONET_1.0_ABBREVIATION: HC00765&lt;/p&gt;
   *   &lt;p&gt;EHMN_ABBREVIATION: C01342&lt;/p&gt;
   *   &lt;p&gt;INCHI: InChI=1S/H3N/h1H3/p+1&lt;/p&gt;
   * &lt;/body&gt;
   * </pre>
   *
   * <pre>
   * &lt;body xmlns="http://www.w3.org/1999/xhtml"&gt;
   *   &lt;p&gt;GENE_ASSOCIATION: 1594.1&lt;/p&gt;
   *   &lt;p&gt;SUBSYSTEM: Vitamin D metabolism&lt;/p&gt;
   *   &lt;p&gt;EC Number: &lt;/p&gt;
   *   &lt;p&gt;Confidence Level: 4&lt;/p&gt;
   *   &lt;p&gt;AUTHORS: PMID:14671156,PMID:9333115&lt;/p&gt;
   *   &lt;p&gt;NOTES: based on Vitamins, G.F.M. Ball,2004, Blackwell publishing, 1st ed (book) pg.196 IT&lt;/p&gt;
   * &lt;/body&gt;
   * </pre>
   *
   * @param sbase
   * @return a {@link Properties} object that store all the KEY/VALUE pair found in the notes. If the given {@link SBase}
   * has no notes or if the notes are not of the expected format, an empty {@link Properties} object is returned.
   */
  public static Properties parseCobraNotes(SBase sbase) {
    Properties props = new Properties();
    
    if (sbase.isSetNotes()) {
      XMLNode notes = sbase.getNotes();
      // just in case no body element is present
      XMLNode parent = notes;
      
      // Getting the body element
      XMLNode body = notes.getChildElement("body", null);
      
      if (body == null) {
        // in some models a 'p' element is used instead of the 'body'
        body = notes.getChildElement("p", null);
      }
      
      if (body == null) {
        // in some models a 'html' element is used instead of the 'body'
        body = notes.getChildElement("html", null);
      }      
      
      if (body != null) {
        parent = body;
      } 
      
      // Getting all the p elements (only direct children of 'parent')
      List<XMLNode> pNodes = parent.getChildElements("p", null);
      
      for (XMLNode pNode : pNodes) {
        if (pNode.getChildCount() > 0) {
          String content = pNode.getChild(0).getCharacters();
          if (content == null) {
            continue;
          }

          String trimmed = content.trim();
          int firstColonIndex = trimmed.indexOf(':');

          if (firstColonIndex != -1) {
            String key = trimmed.substring(0, firstColonIndex).trim();
            String value = trimmed.substring(firstColonIndex + 1).trim();

            if (isLikelyCobraKey(key)) {
              props.setProperty(key, value);
            } else if (logger.isDebugEnabled()) {
              logger.debug("Ignoring notes entry that does not look like a COBRA key: '" + content + "'");
            }
          } else if (logger.isDebugEnabled()) {
            logger.debug("The content of one of the 'p' element does not seems to respect the expected pattern (KEY: VALUE), found '" + content + "'");
          }
        }
        // else: no children; ignore
      }
    }
    
    return props;
  }
  
  /**
   * Deletes the notes of the given {@link SBase} element and writes the content of the {@link Properties} object to the notes of the {@link SBase}.
   *
   * @param sbase 
   * @param properties
   */
  public static void writeCobraNotes(SBase sbase, Properties properties) {
    sbase.unsetNotes();
    for (Object pElement : properties.keySet()) {
      try {
        sbase.appendNotes("<body xmlns=\"" + JSBML.URI_XHTML_DEFINITION + "\"><p>" + pElement + ": " + properties.getProperty((String)pElement) + "</p></body>");
      } catch (XMLStreamException e) {
        e.printStackTrace();
      }
    }
  }
  
  /**
   * Appends notes to the given {@link SBase} from the given {@link Properties}.
   *
   * @param sbase
   * @param properties
   */
  public static void appendCobraNotes(SBase  sbase, Properties properties) {

    for (Object pElement : properties.keySet()) {
      try {
        sbase.appendNotes("<body xmlns=\"" + JSBML.URI_XHTML_DEFINITION + "\"><p>" + pElement + ": " + properties.getProperty((String)pElement) + "</p></body>");
      } catch (XMLStreamException e) {
        e.printStackTrace();
      }
    }
  }

}