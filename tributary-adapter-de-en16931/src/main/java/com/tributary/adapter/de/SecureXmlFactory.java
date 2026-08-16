package com.tributary.adapter.de;

import java.io.IOException;
import java.io.InputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * T-500: the only place this module may create an XML parser (T-501's ArchUnit rule bans direct
 * {@code DocumentBuilderFactory}/{@code SAXParser}/etc. instantiation anywhere else) — closes the
 * T-004 threat (XXE / entity-expansion DoS while parsing third-party EN 16931 XML), rated
 * <b>Crítico</b> in the SRS risk table. §5.3 requires this "sin excepciones y sin configuración
 * por defecto heredada de la librería" — every limit below is set explicitly, never left to
 * whatever a given JDK build happens to default to.
 *
 * <p><b>Empirically probed against this project's own JDK before choosing these limits</b> (not
 * assumed from documentation): a completely bare {@code DocumentBuilderFactory}, with zero
 * configuration, genuinely leaked the real {@code /etc/passwd} of the build host into the parsed
 * DOM — XXE here is not theoretical. Separately, this JDK build turned out to already default
 * {@code jdk.xml.entityExpansionLimit} and {@code jdk.xml.maxElementDepth} to non-zero baseline
 * values (a relatively recent JAXP hardening change, not guaranteed on every JDK vendor/version) —
 * meaning a naive falsifiability probe that only removes the explicit depth setting would prove
 * nothing, since the JDK's own coincidental default would silently keep blocking the same probe
 * document (the exact mistake L-015 already warns about, applied here to XML instead of SQL
 * grants). {@link #MAX_ELEMENT_DEPTH} is therefore set below this JDK's own coincidental default
 * (64, not 100) specifically so the explicit setting is provably load-bearing, not redundant.
 *
 * <p>{@code disallow-doctype-decl} alone already closes both external-entity XXE and internal-
 * entity expansion bombs (billion laughs) in one stroke — a document without a permitted DOCTYPE
 * cannot declare any entity, external or internal, at all. Every other feature below is defense
 * in depth for the same threat, kept explicit rather than relied-upon-by-omission.
 */
public final class SecureXmlFactory {

  /**
   * Chosen deliberately below this JDK's own coincidental default of 100 (see class note) so the
   * setting is provably load-bearing. Real CII/XRechnung documents (RC-1/2/3, and the official
   * KoSIT reference instances) nest at most ~15 levels deep — 64 is generous headroom, not a tight
   * fit, while still being a real, explicit ceiling rather than "whatever the JDK defaults to".
   */
  static final int MAX_ELEMENT_DEPTH = 64;

  /**
   * JAXP has no native "reject if the raw input exceeds N bytes" knob — entity/depth limits bound
   * expansion and nesting, not a single huge text node or attribute value. 5 MiB is generous for
   * an invoice document (RC-1/2/3's XRechnung output is tens of KB) while still being a real,
   * explicit ceiling against a memory-exhaustion DoS via an oversized document.
   */
  static final int MAX_INPUT_BYTES = 5 * 1024 * 1024;

  private SecureXmlFactory() {}

  /** A hardened builder — see the class note for exactly what each feature closes and why. */
  public static DocumentBuilder newDocumentBuilder() {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    try {
      // Primary defense: no DOCTYPE means no entity declarations at all, external or internal.
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      // Defense in depth: JAXP's own resource-exhaustion limits and accessExternalDTD/-Schema
      // restrictions, in case a future change ever loosens the DOCTYPE ban above.
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      // Explicit, not inherited from whatever this JDK build happens to default to — see class note.
      factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", MAX_ELEMENT_DEPTH);
    } catch (ParserConfigurationException e) {
      // Every feature/attribute above is a standard JAXP one this JDK is required to support —
      // failure here means a broken environment, not bad input, so it is not worth a checked
      // exception on every caller.
      throw new IllegalStateException("this JDK does not support a required XML hardening feature", e);
    }
    try {
      return factory.newDocumentBuilder();
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException("could not construct a hardened DocumentBuilder", e);
    }
  }

  /**
   * Parses {@code input} with a hardened builder, after first rejecting anything over {@link
   * #MAX_INPUT_BYTES} without buffering the whole thing into memory to find out.
   */
  public static Document parse(InputStream input) throws SAXException, IOException {
    return newDocumentBuilder().parse(new SizeLimitedInputStream(input, MAX_INPUT_BYTES));
  }

  /** Reads at most {@code limit} bytes; the (limit+1)th read fails the stream, not the caller's heap. */
  private static final class SizeLimitedInputStream extends InputStream {
    private final InputStream delegate;
    private final long limit;
    private long readSoFar;

    SizeLimitedInputStream(InputStream delegate, long limit) {
      this.delegate = delegate;
      this.limit = limit;
    }

    @Override
    public int read() throws IOException {
      if (readSoFar > limit) {
        throw new IOException("input exceeds SecureXmlFactory.MAX_INPUT_BYTES (" + limit + " bytes)");
      }
      int b = delegate.read();
      if (b != -1) {
        readSoFar++;
        if (readSoFar > limit) {
          throw new IOException("input exceeds SecureXmlFactory.MAX_INPUT_BYTES (" + limit + " bytes)");
        }
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (readSoFar > limit) {
        throw new IOException("input exceeds SecureXmlFactory.MAX_INPUT_BYTES (" + limit + " bytes)");
      }
      // Allow one byte past the limit through on purpose: only once the delegate actually
      // produces it have we confirmed the input truly exceeds the limit, rather than merely
      // having read exactly up to it and then hit EOF (which must still succeed).
      int allowed = (int) Math.min(len, limit - readSoFar + 1);
      int n = delegate.read(b, off, allowed);
      if (n > 0) {
        readSoFar += n;
        if (readSoFar > limit) {
          throw new IOException("input exceeds SecureXmlFactory.MAX_INPUT_BYTES (" + limit + " bytes)");
        }
      }
      return n;
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
