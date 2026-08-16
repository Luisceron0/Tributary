package com.tributary.adapter.de;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * T-500: {@link SecureXmlFactory} is the only place this module may create an XML parser
 * (enforced separately by T-501's ArchUnit rule). Every limit here was chosen after empirically
 * probing this exact JDK, not assumed from documentation — see the class-level note on {@link
 * SecureXmlFactory} for what that probing found.
 */
class SecureXmlFactoryTest {

  @Test
  @DisplayName("a well-formed, shallow, reasonably-sized document parses normally")
  void parsesAnOrdinaryDocument() throws Exception {
    String xml = "<?xml version=\"1.0\"?><invoice><id>INV-1</id></invoice>";

    Document doc = SecureXmlFactory.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

    assertEquals("invoice", doc.getDocumentElement().getTagName());
  }

  @Test
  @DisplayName("CV-04: an external entity targeting a real local file is rejected outright, never resolved")
  void xxeTargetingARealFileIsRejected() {
    // /etc/passwd is a real, readable file on this host — this is not a theoretical payload;
    // an unhardened factory (see SecureXmlFactory's class note) genuinely leaks it into the DOM.
    String payload =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>"
            + "<foo>&xxe;</foo>";

    Exception thrown =
        assertThrows(
            SAXException.class,
            () -> SecureXmlFactory.parse(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8))));

    // The rejection must happen at the DOCTYPE itself — not "resolved, then discarded".
    assertTrue(thrown.getMessage().contains("DOCTYPE"), "expected a DOCTYPE rejection, got: " + thrown.getMessage());
  }

  @Test
  @DisplayName("CV-04: a DOCTYPE-declared internal-entity expansion bomb is rejected the same way — no DTD means no entities, external or internal")
  void entityExpansionBombIsRejected() {
    StringBuilder payload = new StringBuilder("<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\">");
    for (int i = 1; i <= 9; i++) {
      payload.append("<!ENTITY lol").append(i).append(" \"&lol").append(i - 1).append(";&lol").append(i - 1).append(";\">");
    }
    payload.append("]><lolz>&lol9;</lolz>");

    assertThrows(
        SAXException.class,
        () -> SecureXmlFactory.parse(new ByteArrayInputStream(payload.toString().getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  @DisplayName("a document nested deeper than the explicit depth limit is rejected, with no entities involved at all")
  void excessiveNestingDepthIsRejectedWithoutAnyEntities() {
    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?>");
    int depth = SecureXmlFactory.MAX_ELEMENT_DEPTH + 10;
    xml.append("<r>".repeat(1)); // root, then nest
    for (int i = 0; i < depth; i++) {
      xml.append("<a>");
    }
    xml.append("x");
    for (int i = 0; i < depth; i++) {
      xml.append("</a>");
    }
    xml.append("</r>");

    assertThrows(
        SAXException.class,
        () -> SecureXmlFactory.parse(new ByteArrayInputStream(xml.toString().getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  @DisplayName("a document within the depth limit, but with no entities, still parses fine")
  void nestingWithinTheLimitParsesFine() throws Exception {
    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?>");
    int depth = SecureXmlFactory.MAX_ELEMENT_DEPTH - 10;
    for (int i = 0; i < depth; i++) {
      xml.append("<a>");
    }
    xml.append("x");
    for (int i = 0; i < depth; i++) {
      xml.append("</a>");
    }

    Document doc = SecureXmlFactory.parse(new ByteArrayInputStream(xml.toString().getBytes(StandardCharsets.UTF_8)));

    assertEquals("a", doc.getDocumentElement().getTagName());
  }

  @Test
  @DisplayName("an input larger than the explicit byte-size limit is rejected before full parsing")
  void oversizedInputIsRejected() {
    // Larger than MAX_INPUT_BYTES, but otherwise perfectly well-formed and shallow — the size
    // check must be a real, independent limit, not a side effect of the depth/entity checks.
    String opening = "<?xml version=\"1.0\"?><r>";
    String closing = "</r>";
    int padding = SecureXmlFactory.MAX_INPUT_BYTES - opening.length() - closing.length() + 1;
    StringBuilder xml = new StringBuilder(opening.length() + closing.length() + padding);
    xml.append(opening);
    xml.append("x".repeat(padding));
    xml.append(closing);

    IOException thrown =
        assertThrows(
            IOException.class,
            () -> SecureXmlFactory.parse(new ByteArrayInputStream(xml.toString().getBytes(StandardCharsets.UTF_8))));
    assertTrue(thrown.getMessage().contains("MAX_INPUT_BYTES") || thrown.getMessage().toLowerCase().contains("size"));
  }

  @Test
  @DisplayName("an input exactly at the byte-size limit still parses")
  void inputExactlyAtTheLimitParses() throws Exception {
    String opening = "<?xml version=\"1.0\"?><r>";
    String closing = "</r>";
    int padding = SecureXmlFactory.MAX_INPUT_BYTES - opening.length() - closing.length();
    StringBuilder xml = new StringBuilder(SecureXmlFactory.MAX_INPUT_BYTES);
    xml.append(opening);
    xml.append("x".repeat(padding));
    xml.append(closing);
    assertEquals(SecureXmlFactory.MAX_INPUT_BYTES, xml.length());

    Document doc = SecureXmlFactory.parse(new ByteArrayInputStream(xml.toString().getBytes(StandardCharsets.UTF_8)));

    assertEquals("r", doc.getDocumentElement().getTagName());
  }
}
