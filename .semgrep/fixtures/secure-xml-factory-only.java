package com.tributary.semgreptest;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;

class VulnerableXmlParser {

  void unsafeDom() throws Exception {
    // ruleid: tributary-secure-xml-factory-only
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
  }

  void unsafeSax() throws Exception {
    // ruleid: tributary-secure-xml-factory-only
    SAXParserFactory factory = SAXParserFactory.newInstance();
  }

  void safe() throws Exception {
    // ok: tributary-secure-xml-factory-only
    var builder = com.tributary.adapter.de.SecureXmlFactory.newDocumentBuilder();
  }
}
