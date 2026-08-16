package com.tributary.adapter.de;

import java.net.URI;
import java.util.Set;

/**
 * T-506 / T-005: the single gate any future outbound network call in this module must pass
 * through before connecting — no code in this package may dereference a URI without consulting
 * it first (T-501-style ArchUnit rule in {@code ArchitectureTest} bans raw {@code
 * java.net.http.HttpClient}/{@code java.net.Socket} construction here for exactly that reason).
 *
 * <p>{@link #PRODUCTION_ALLOWED_HOSTS} is empty by design, not merely unconfigured: this module
 * has no legitimate reason to make an outbound network call at all today. {@link KositValidator}
 * (T-504) shells out to a local JVM subprocess — no network. {@link SecureXmlFactory} (T-500/
 * T-502) disables all external entity resolution outright, so no document it parses can trigger
 * one either. If a genuine future need for egress arises, its host is added here explicitly,
 * reviewed at the point of the change that needs it — never derived from untrusted document
 * content, which is exactly the SSRF path T-005 describes.
 */
public final class EgressAllowlist {

  private static final Set<String> PRODUCTION_ALLOWED_HOSTS = Set.of();

  private EgressAllowlist() {}

  /** Checks {@code uri} against the real production allowlist (empty — see class note). */
  public static void checkAllowed(URI uri) {
    checkAllowed(uri, PRODUCTION_ALLOWED_HOSTS);
  }

  /** Checks {@code uri} against an explicit {@code allowedHosts} set — the testable core. */
  public static void checkAllowed(URI uri, Set<String> allowedHosts) {
    String host = uri.getHost();
    if (host == null || !allowedHosts.contains(host)) {
      throw new SecurityException(
          "egress to \"" + uri + "\" is not on the allowlist (T-506/T-005) — refusing to connect");
    }
  }
}
