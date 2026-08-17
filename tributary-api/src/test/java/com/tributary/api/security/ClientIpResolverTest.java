package com.tributary.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * T-901: the whole point of this class is that it must be impossible to change the answer by
 * sending a header, unless the connection genuinely comes from a declared proxy. Each test below
 * is one way an attacker would try exactly that.
 */
class ClientIpResolverTest {

  private static MockHttpServletRequest requestFrom(String remoteAddr, String forwardedFor) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(remoteAddr);
    if (forwardedFor != null) {
      request.addHeader("X-Forwarded-For", forwardedFor);
    }
    return request;
  }

  @Test
  @DisplayName("with no trusted proxy configured, a forged X-Forwarded-For is ignored entirely — the fail-closed default")
  void headerIsIgnoredWhenNoProxyIsTrusted() {
    ClientIpResolver resolver = new ClientIpResolver(Set.of());

    assertThat(resolver.resolve(requestFrom("203.0.113.7", "1.2.3.4"))).contains("203.0.113.7");
  }

  @Test
  @DisplayName("a direct caller cannot promote itself by sending X-Forwarded-For")
  void untrustedCallerCannotSpoofItsAddress() {
    ClientIpResolver resolver = new ClientIpResolver(Set.of("10.0.0.1"));

    // The connection is NOT from the proxy, so the header carries no authority at all.
    assertThat(resolver.resolve(requestFrom("203.0.113.7", "1.2.3.4"))).contains("203.0.113.7");
  }

  @Test
  @DisplayName("behind the declared proxy, the real client address is recovered from the header")
  void trustedProxyYieldsTheRealClient() {
    ClientIpResolver resolver = new ClientIpResolver(Set.of("10.0.0.1"));

    assertThat(resolver.resolve(requestFrom("10.0.0.1", "198.51.100.9"))).contains("198.51.100.9");
  }

  @Test
  @DisplayName("a client that prepends a fake hop cannot hide behind it — the rightmost untrusted entry wins")
  void prependedForgedHopsAreDiscarded() {
    ClientIpResolver resolver = new ClientIpResolver(Set.of("10.0.0.1"));

    // The attacker sent "X-Forwarded-For: 1.2.3.4" themselves; the proxy appended their real
    // address to the right. Taking the leftmost entry — the common mistake — would return the
    // forgery.
    assertThat(resolver.resolve(requestFrom("10.0.0.1", "1.2.3.4, 198.51.100.9"))).contains("198.51.100.9");
  }

  @Test
  @DisplayName("chained trusted proxies are skipped over, back to the first address we did not add ourselves")
  void chainedTrustedProxiesAreSkipped() {
    ClientIpResolver resolver = new ClientIpResolver(Set.of("10.0.0.1", "10.0.0.2"));

    assertThat(resolver.resolve(requestFrom("10.0.0.1", "198.51.100.9, 10.0.0.2"))).contains("198.51.100.9");
  }

  @Test
  @DisplayName("a trusted proxy that sends no header falls back to its own address rather than to nothing")
  void trustedProxyWithoutHeaderFallsBack() {
    ClientIpResolver resolver = new ClientIpResolver(Set.of("10.0.0.1"));

    assertThat(resolver.resolve(requestFrom("10.0.0.1", null))).contains("10.0.0.1");
  }
}
