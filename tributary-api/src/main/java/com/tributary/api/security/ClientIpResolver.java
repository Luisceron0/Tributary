package com.tributary.api.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * T-901 / SRS §5.3 and §10.5: {@code X-Forwarded-For} is accepted <b>only</b> from a trusted
 * proxy, and the client-supplied value is otherwise discarded.
 *
 * <p>The header is hostile input like any other. Anyone can send {@code X-Forwarded-For:
 * 1.2.3.4}, so a rate limiter or audit trail that believes it unconditionally is not merely
 * imprecise — it is trivially defeated by rotating a forged header. The rule is therefore
 * positional, not syntactic: the value is considered only when the connection itself arrives
 * from an address the operator has declared as a proxy.
 *
 * <p><b>Empty trusted set means never trust the header.</b> That is the default, and it is the
 * fail-closed choice: a deployment that has not yet declared its proxy gets the direct socket
 * address, which is always true even if less informative behind a load balancer. Being wrong in
 * the direction of "attributes traffic to the proxy" costs accuracy; being wrong in the other
 * direction costs the control.
 */
public final class ClientIpResolver {

  private final Set<String> trustedProxies;

  public ClientIpResolver(Set<String> trustedProxies) {
    this.trustedProxies = Set.copyOf(trustedProxies);
  }

  public Optional<String> resolve(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    if (remoteAddr == null) {
      return Optional.empty();
    }
    if (!trustedProxies.contains(remoteAddr)) {
      // Not behind a declared proxy: the socket address is the only trustworthy answer.
      return Optional.of(remoteAddr);
    }

    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded == null || forwarded.isBlank()) {
      return Optional.of(remoteAddr);
    }

    // X-Forwarded-For is client, proxy1, proxy2... — appended left to right. Walk from the right
    // and take the first entry that is NOT one of our own proxies: everything further left was
    // supplied by whoever spoke to the outermost proxy and can be forged freely.
    List<String> hops = Arrays.stream(forwarded.split(",")).map(String::strip).filter(h -> !h.isBlank()).toList();
    for (int i = hops.size() - 1; i >= 0; i--) {
      if (!trustedProxies.contains(hops.get(i))) {
        return Optional.of(hops.get(i));
      }
    }
    // Every hop was a trusted proxy of ours: the nearest real client is the proxy itself.
    return Optional.of(remoteAddr);
  }
}
