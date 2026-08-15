package com.tributary.adapter.co;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-309: the service refuses to start against Factus PRODUCTION unless an explicit enablement
 * variable is set, and the production secret lives under a name distinct from the sandbox one —
 * SRS 5.3's fail-closed environment guard, never made implicit or convenient.
 */
class FactusEnvironmentTest {

  private static Map<String, String> sandboxEnv() {
    Map<String, String> env = new HashMap<>();
    env.put("FACTUS_SANDBOX_BASE_URL", "https://api-sandbox.factus.com.co");
    env.put("FACTUS_SANDBOX_CLIENT_ID", "sandbox-id");
    env.put("FACTUS_SANDBOX_CLIENT_SECRET", "sandbox-secret");
    env.put("FACTUS_SANDBOX_USERNAME", "sandbox-user");
    env.put("FACTUS_SANDBOX_PASSWORD", "sandbox-pass");
    return env;
  }

  private static Map<String, String> productionEnv() {
    Map<String, String> env = new HashMap<>();
    env.put("FACTUS_PRODUCTION_BASE_URL", "https://api.factus.com.co");
    env.put("FACTUS_PRODUCTION_CLIENT_ID", "prod-id");
    env.put("FACTUS_PRODUCTION_CLIENT_SECRET", "prod-secret");
    env.put("FACTUS_PRODUCTION_USERNAME", "prod-user");
    env.put("FACTUS_PRODUCTION_PASSWORD", "prod-pass");
    return env;
  }

  @Test
  @DisplayName("with no enablement variable, resolves sandbox credentials by default")
  void defaultsToSandbox() {
    FactusCredentials credentials = FactusEnvironment.resolve(sandboxEnv()::get);
    assertEquals("https://api-sandbox.factus.com.co", credentials.baseUrl());
    assertEquals("sandbox-secret", credentials.clientSecret());
  }

  @Test
  @DisplayName("production variables alone, without FACTUS_ENABLE_PRODUCTION, are refused — fail-closed")
  void refusesProductionWithoutExplicitEnablement() {
    Map<String, String> env = new HashMap<>(sandboxEnv());
    env.putAll(productionEnv());
    // FACTUS_ENABLE_PRODUCTION intentionally absent.

    assertThrows(IllegalStateException.class, () -> FactusEnvironment.resolveProduction(env::get));
  }

  @Test
  @DisplayName("FACTUS_ENABLE_PRODUCTION=true resolves the distinctly-named production credentials")
  void explicitEnablementResolvesProduction() {
    Map<String, String> env = new HashMap<>(productionEnv());
    env.put("FACTUS_ENABLE_PRODUCTION", "true");

    FactusCredentials credentials = FactusEnvironment.resolveProduction(env::get);
    assertEquals("https://api.factus.com.co", credentials.baseUrl());
    assertEquals("prod-secret", credentials.clientSecret());
  }

  @Test
  @DisplayName("a production base URL reached through the sandbox resolver path is refused outright")
  void sandboxResolverRefusesAProductionLookingUrl() {
    Map<String, String> env = new HashMap<>();
    env.put("FACTUS_SANDBOX_BASE_URL", "https://api.factus.com.co"); // misconfigured: prod host, sandbox variable
    env.put("FACTUS_SANDBOX_CLIENT_ID", "x");
    env.put("FACTUS_SANDBOX_CLIENT_SECRET", "y");
    env.put("FACTUS_SANDBOX_USERNAME", "u");
    env.put("FACTUS_SANDBOX_PASSWORD", "p");

    assertThrows(IllegalStateException.class, () -> FactusEnvironment.resolve(env::get));
  }

  @Test
  @DisplayName("a missing required variable fails with a clear message, not a NullPointerException")
  void missingVariableFailsClearly() {
    Map<String, String> incomplete = new HashMap<>(sandboxEnv());
    incomplete.remove("FACTUS_SANDBOX_CLIENT_SECRET");

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, () -> FactusEnvironment.resolve(incomplete::get));
    assertEquals(true, exception.getMessage().contains("FACTUS_SANDBOX_CLIENT_SECRET"));
  }
}
