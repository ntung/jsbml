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
   * Parses the notes of the given {@link SBase} element.
   *
   * <p>The notes are expecting to have some 'p' elements with
   * a content of the form <code>KEY: VALUE</code>. The key will be used for the property
   * name and the value will be the property value. The key is inserted even if the value
   * is an empty {@link String}.
   * 
   * <p>This implementation is generic and does <strong>not</strong> restrict the key
   * to a fixed set of COBRA fields. Instead, it:
   * <ul>
   *   <li>Requires exactly one colon in the content, and</li>
   *   <li>Rejects keys containing whitespace (to avoid treating arbitrary
   *       sentences as keys).</li>
   * </ul>
   *
   * @param sbase
   * @return a {@link Properties} object that stores all the KEY/VALUE pairs found
   *         in the notes. If the given {@link SBase} has no notes or if the notes
   *         are not of the expected format, an empty {@link Properties} object is
   *         returned.
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

          // count colons
          int colonCount = 0;
          for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == ':') {
              colonCount++;
            }
          }

          if (colonCount == 1) {
            int firstColonIndex = content.indexOf(':');
            String key = content.substring(0, firstColonIndex).trim();
            String value = content.substring(firstColonIndex + 1).trim();

            // no whitespaces allowed in key (generic but avoids sentences)
            if (!key.contains(" ")) {
              props.setProperty(key, value);
            } else if (logger.isDebugEnabled()) {
              logger.debug("Ignoring COBRA notes entry with whitespace in key: '" + content + "'");
            }
          } else if (logger.isDebugEnabled()) {
            logger.debug("Ignoring COBRA notes entry without exactly one colon: '" + content + "'");
          }
        }
        // else: no children; ignore
      }
    }
    
    return props;
  }
  
  /**
   * Deletes the notes of the given {@link SBase} element and writes the content
   * of the {@link Properties} object to the notes of the {@link SBase}.
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