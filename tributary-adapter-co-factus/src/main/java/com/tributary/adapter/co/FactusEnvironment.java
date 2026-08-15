package com.tributary.adapter.co;

import java.util.Objects;
import java.util.function.Function;

/**
 * T-309: the fail-closed environment guard. {@link #resolve} is the normal entry point and
 * defaults to sandbox credentials; reaching production requires calling {@link #resolveProduction}
 * AND setting {@code FACTUS_ENABLE_PRODUCTION=true} — never implicit, never a side effect of which
 * variables happen to be set. The production secret lives under a name ({@code
 * FACTUS_PRODUCTION_CLIENT_SECRET}) distinct from the sandbox one ({@code
 * FACTUS_SANDBOX_CLIENT_SECRET}), so the two can never be confused by a copy-paste.
 *
 * <p>Takes {@code Function<String, String>} rather than reading {@code System.getenv()} directly,
 * so this stays testable with a plain {@link java.util.Map} and carries no hidden dependency on
 * process environment state.
 */
public final class FactusEnvironment {

  private static final String PRODUCTION_HOST_MARKER = "api.factus.com.co";
  private static final String SANDBOX_HOST_MARKER = "api-sandbox.factus.com.co";

  private FactusEnvironment() {}

  /** Resolves sandbox credentials. Refuses outright if the configured URL looks like production. */
  public static FactusCredentials resolve(Function<String, String> env) {
    Objects.requireNonNull(env, "env must not be null");
    FactusCredentials credentials =
        new FactusCredentials(
            require(env, "FACTUS_SANDBOX_BASE_URL"),
            require(env, "FACTUS_SANDBOX_CLIENT_ID"),
            require(env, "FACTUS_SANDBOX_CLIENT_SECRET"),
            require(env, "FACTUS_SANDBOX_USERNAME"),
            require(env, "FACTUS_SANDBOX_PASSWORD"));

    if (looksLikeProduction(credentials.baseUrl())) {
      throw new IllegalStateException(
          "FACTUS_SANDBOX_BASE_URL (\"" + credentials.baseUrl() + "\") looks like the Factus PRODUCTION host — "
              + "refusing to start. Production requires FactusEnvironment.resolveProduction() and "
              + "FACTUS_ENABLE_PRODUCTION=true explicitly, never a sandbox variable pointed at it.");
    }
    return credentials;
  }

  /** @throws IllegalStateException unless {@code FACTUS_ENABLE_PRODUCTION} is exactly {@code "true"} */
  public static FactusCredentials resolveProduction(Function<String, String> env) {
    Objects.requireNonNull(env, "env must not be null");
    if (!"true".equals(env.apply("FACTUS_ENABLE_PRODUCTION"))) {
      throw new IllegalStateException(
          "refusing to start against Factus PRODUCTION without FACTUS_ENABLE_PRODUCTION=true set explicitly (SRS 5.3 fail-closed guard)");
    }
    return new FactusCredentials(
        require(env, "FACTUS_PRODUCTION_BASE_URL"),
        require(env, "FACTUS_PRODUCTION_CLIENT_ID"),
        require(env, "FACTUS_PRODUCTION_CLIENT_SECRET"),
        require(env, "FACTUS_PRODUCTION_USERNAME"),
        require(env, "FACTUS_PRODUCTION_PASSWORD"));
  }

  private static boolean looksLikeProduction(String baseUrl) {
    return baseUrl.contains(PRODUCTION_HOST_MARKER) && !baseUrl.contains(SANDBOX_HOST_MARKER);
  }

  private static String require(Function<String, String> env, String name) {
    String value = env.apply(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("required environment variable " + name + " is not set");
    }
    return value;
  }
}
