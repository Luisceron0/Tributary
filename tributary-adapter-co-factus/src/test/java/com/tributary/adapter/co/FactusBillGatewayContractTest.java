package com.tributary.adapter.co;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.tributary.application.port.IssuanceOutcome;
import com.tributary.application.port.IssuanceResult;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * T-305: the four outcomes {@code POST /v2/bills/validate} can produce, mapped onto T-103's
 * IssuanceResult. T-302: a {@code 429} is retried after {@code Retry-After} plus jitter, not
 * treated as any of the four normal outcomes. Every response body shape here is the one confirmed
 * live against the real sandbox this session (see FactusPayloadMapperTest and tasks/todo.md).
 */
class FactusBillGatewayContractTest {

  @RegisterExtension
  static WireMockExtension wireMock =
      WireMockExtension.newInstance().options(WireMockConfiguration.wireMockConfig().dynamicPort()).build();

  private static final ObjectMapper JSON = new ObjectMapper();

  private FactusCredentials credentials;
  private FactusBillGateway gateway;
  private ObjectNode samplePayload;

  @BeforeEach
  void setUp() {
    credentials = new FactusCredentials(wireMock.baseUrl(), "id", "secret", "user", "pass");
    gateway = new FactusBillGateway(new FactusRateLimiter(60, Duration.ofSeconds(60)));
    samplePayload = JSON.createObjectNode();
    samplePayload.put("reference_code", "biz-key-1");
    wireMock.resetAll();
  }

  private FactusToken aToken() {
    return new FactusToken("access-abc", "refresh-xyz", Instant.now().plusSeconds(3600));
  }

  @Test
  @DisplayName("clean 201, is_validated=true, empty errors -> ACCEPTED")
  void cleanAcceptance() {
    wireMock.stubFor(
        post(urlEqualTo("/v2/bills/validate"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"status":"Created","message":"ok","data":{"reference_code":"biz-key-1",
                        "number":"SETP990015225","is_validated":true,"errors":{},
                        "cufe":"70006549f238e38e220c03e54ae9dc43a66df1dfa54876ebcbd22e86a8d034a"}}
                        """)));

    IssuanceResult result = gateway.validate(credentials, aToken(), samplePayload);

    assertEquals(IssuanceOutcome.ACCEPTED, result.outcome());
    assertEquals("70006549f238e38e220c03e54ae9dc43a66df1dfa54876ebcbd22e86a8d034a", result.externalReference().orElseThrow());
    assertTrue(result.warnings().isEmpty());
  }

  @Test
  @DisplayName("201, is_validated=true, non-empty errors -> ACCEPTED_WITH_WARNINGS, warnings preserved whole")
  void acceptedWithWarnings() {
    // The exact shape observed live: DIAN notifications alongside is_validated=true.
    wireMock.stubFor(
        post(urlEqualTo("/v2/bills/validate"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"status":"Created","message":"ok","data":{"reference_code":"biz-key-1",
                        "number":"SETP990015225","is_validated":true,
                        "errors":{"RUT01":"Regla: RUT01, Notificacion: la validacion del RUT proximamente estara disponible",
                                  "FAJ44b":"Regla: FAJ44b, Notificacion: Nit informado no corresponde al RUT"},
                        "cufe":"70006549f238e38e220c03e54ae9dc43a66df1dfa54876ebcbd22e86a8d034a"}}
                        """)));

    IssuanceResult result = gateway.validate(credentials, aToken(), samplePayload);

    assertEquals(IssuanceOutcome.ACCEPTED_WITH_WARNINGS, result.outcome());
    assertTrue(result.externalReference().isPresent());
    assertEquals(2, result.warnings().size());
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("RUT01")));
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("FAJ44b")));
  }

  @Test
  @DisplayName("is_validated=false -> REJECTED, no external reference")
  void rejected() {
    wireMock.stubFor(
        post(urlEqualTo("/v2/bills/validate"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"status":"Created","message":"ok","data":{"reference_code":"biz-key-1",
                        "is_validated":false,
                        "errors":{"CO01":"campo obligatorio faltante"}}}
                        """)));

    IssuanceResult result = gateway.validate(credentials, aToken(), samplePayload);

    assertEquals(IssuanceOutcome.REJECTED, result.outcome());
    assertTrue(result.externalReference().isEmpty());
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("CO01")));
  }

  @Test
  @DisplayName("a connection failure -> UNREACHABLE, never treated as REJECTED")
  void unreachableOnConnectionFailure() {
    // No stub registered at all — WireMock resets to "connection reset" territory is inconsistent
    // across environments, so this simulates unreachability via a fault directly.
    wireMock.stubFor(
        post(urlEqualTo("/v2/bills/validate"))
            .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

    IssuanceResult result = gateway.validate(credentials, aToken(), samplePayload);

    assertEquals(IssuanceOutcome.UNREACHABLE, result.outcome());
    assertTrue(result.externalReference().isEmpty());
  }

  @Test
  @DisplayName("T-302: a 429 is retried after Retry-After plus jitter, not surfaced as any of the four outcomes")
  void retriesAfter429() {
    wireMock.stubFor(
        post(urlEqualTo("/v2/bills/validate"))
            .inScenario("rate-limit-then-success")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
            .willSetStateTo("retried"));
    wireMock.stubFor(
        post(urlEqualTo("/v2/bills/validate"))
            .inScenario("rate-limit-then-success")
            .whenScenarioStateIs("retried")
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"status":"Created","message":"ok","data":{"reference_code":"biz-key-1",
                        "is_validated":true,"errors":{},"cufe":"cufe-after-retry"}}
                        """)));

    Instant before = Instant.now();
    IssuanceResult result = gateway.validate(credentials, aToken(), samplePayload);
    Duration elapsed = Duration.between(before, Instant.now());

    assertEquals(IssuanceOutcome.ACCEPTED, result.outcome());
    assertEquals("cufe-after-retry", result.externalReference().orElseThrow());
    assertTrue(elapsed.compareTo(Duration.ofSeconds(1)) >= 0, () -> "retry happened after only " + elapsed);

    wireMock.verify(2, postRequestedFor(urlEqualTo("/v2/bills/validate")));
  }
}
