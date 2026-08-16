package com.tributary.adapter.de;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

/**
 * T-502 / CV-04, the SRS's literal two-part verification criterion: given a document with an
 * external entity pointing at {@code file:///etc/passwd}, parsing must throw <b>and</b> produce
 * neither a file read nor an observed outbound connection. The file-read half restates T-500's
 * own {@code SecureXmlFactoryTest} coverage (kept here too so this file stands alone as CV-04's
 * evidence artifact); the connection half is new — SSRF via an XXE entity uses the same DOCTYPE
 * mechanism but a different payload scheme ({@code http://} instead of {@code file://}), and
 * nothing in T-500 previously proved zero outbound connections specifically.
 */
class XxeProbeTest {

  @Test
  @DisplayName("CV-04 (file read): the real /etc/passwd is never read — rejected at the DOCTYPE token itself")
  void noFileReadIsObserved() {
    String payload =
        "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>"
            + "<foo>&xxe;</foo>";

    SAXException thrown =
        assertThrows(
            SAXException.class,
            () -> SecureXmlFactory.parse(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8))));

    // The rejection message names DOCTYPE, not a file-access or permission problem — proving the
    // parser never got far enough to even attempt opening the referenced file. A message about
    // e.g. "access denied" or "file not found" would mean the opposite: an open was attempted.
    assertTrue(thrown.getMessage().contains("DOCTYPE"), "expected a DOCTYPE-stage rejection, got: " + thrown.getMessage());
    System.out.println(
        "T-502/CV-04 evidence (file read) — parser log: " + thrown.getClass().getSimpleName() + ": " + thrown.getMessage());
  }

  @Test
  @DisplayName("CV-04 (outbound connection): an entity pointing at a real local listener never connects to it")
  void noOutboundConnectionIsObserved() throws Exception {
    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      int port = listener.getLocalPort();
      AtomicBoolean connectionReceived = new AtomicBoolean(false);

      // Blocks in accept() until either a real connection arrives (leak) or this test closes the
      // socket itself below (proving none ever arrived) — no sleep, no timing race either way.
      Thread acceptor =
          new Thread(
              () -> {
                try (var socket = listener.accept()) {
                  // Closing immediately (rather than leaving it open) forces a fast connection
                  // reset on the client side instead of a hang waiting for an HTTP response this
                  // listener never sends — the test only cares whether a connection arrived at
                  // all, not what happens on it afterward.
                  connectionReceived.set(true);
                } catch (IOException expectedOnDeliberateClose) {
                  // listener.close() below is what unblocks this — the expected, no-leak path.
                }
              });
      acceptor.start();

      String payload =
          "<?xml version=\"1.0\"?>"
              + "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"http://127.0.0.1:" + port + "/xxe-callback\"> ]>"
              + "<foo>&xxe;</foo>";

      SAXException thrown =
          assertThrows(
              SAXException.class,
              () -> SecureXmlFactory.parse(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8))));

      listener.close();
      acceptor.join(2000);

      assertFalse(connectionReceived.get(), "the parser must never attempt an outbound connection at all");
      System.out.println(
          "T-502/CV-04 evidence (outbound) — parser log: " + thrown.getClass().getSimpleName() + ": " + thrown.getMessage()
              + " — traffic capture on 127.0.0.1:" + port + " is empty, connectionReceived=" + connectionReceived.get());
    }
  }
}
