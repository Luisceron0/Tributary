package com.tributary.adapter.de;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-506 / T-005: this module has no legitimate reason to make an outbound network call at all —
 * {@link KositValidator} shells out to a local JVM subprocess (T-504), and {@link
 * SecureXmlFactory} disables all external entity resolution outright (T-500/T-502) — so the real
 * allowlist is empty by design, not merely unconfigured. This proves the gate itself: a URL
 * sourced from an incoming document's content is never dereferenced, whatever it claims to be.
 */
class EgressAllowlistTest {

  @Test
  @DisplayName("T-005: a URL extracted from an incoming document's content is never dereferenced — the production allowlist is empty")
  void aUrlExtractedFromDocumentContentIsRejected() {
    // Not a hypothetical: this is exactly the shape T-005 describes — some free-text field in a
    // parsed third-party document happens to contain what looks like a URL, and something,
    // somewhere, is tempted to fetch it.
    URI urlFoundInsideADocument = URI.create("http://attacker.example.com/exfiltrate?doc=rc1");

    assertThrows(SecurityException.class, () -> EgressAllowlist.checkAllowed(urlFoundInsideADocument));
  }

  @Test
  @DisplayName("a host that IS on an explicit allowlist is permitted — the gate is a real filter, not a blanket refusal")
  void aHostOnAnExplicitAllowlistIsPermitted() {
    URI uri = URI.create("https://validator-config.example.org/scenarios.xml");

    assertDoesNotThrow(() -> EgressAllowlist.checkAllowed(uri, Set.of("validator-config.example.org")));
  }

  @Test
  @DisplayName("a host NOT on an explicit allowlist is refused, even a plausible-looking one")
  void aHostNotOnAnExplicitAllowlistIsRefused() {
    URI uri = URI.create("https://validator-config.example.org.attacker.net/scenarios.xml");

    assertThrows(
        SecurityException.class, () -> EgressAllowlist.checkAllowed(uri, Set.of("validator-config.example.org")));
  }

  @Test
  @DisplayName("a URI with no host at all is refused, not silently treated as a no-op")
  void aUriWithNoHostIsRefused() {
    URI uri = URI.create("file:///etc/passwd");

    assertThrows(SecurityException.class, () -> EgressAllowlist.checkAllowed(uri, Set.of("example.org")));
  }
}
