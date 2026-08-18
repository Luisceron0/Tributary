package com.tributary.adapter.co;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * Audit finding: {@code toIssuanceResult} only ever special-cased HTTP 429. Every other status was
 * interpreted as if it carried a DIAN verdict, so a transport- or server-level failure that
 * happened to include a {@code data} object was mapped to {@link IssuanceOutcome#REJECTED}.
 *
 * <p>That mapping is irreversible in a way no other wrong answer here is: {@code
 * IssueInvoiceUseCase} turns REJECTED into {@code DocumentState.REJECTED}, and that state's
 * transition set is {@code EnumSet.noneOf(...)} — nothing leaves it. An invoice DIAN never saw
 * would have been permanently recorded as one DIAN refused, which is precisely the class of
 * mistake ADR-003 exists to prevent (never convert an ambiguous failure into a definitive verdict).
 *
 * <p>These tests pin the corrected behaviour: only a 2xx response is a verdict; anything else is
 * {@link IssuanceOutcome#UNREACHABLE}, which routes to {@code NEEDS_RECONCILIATION} and forces a
 * query before any retry.
 */
class FactusBillGatewayHttpStatusTest {

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

  private IssuanceResult callWith(int status, String body) {
    wireMock.stubFor(
        post(urlEqualTo("/v2/bills/validate"))
            .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    return gateway.validate(credentials, aToken(), samplePayload);
  }

  @Test
  @DisplayName("a 500 carrying a data object is NOT a DIAN rejection — the server failed, DIAN never ruled")
  void serverErrorWithDataObjectIsUnreachableNotRejected() {
    IssuanceResult result = callWith(500, "{\"status\":\"error\",\"data\":{\"message\":\"internal error\"}}");

    assertEquals(
        IssuanceOutcome.UNREACHABLE,
        result.outcome(),
        "a 5xx must route to NEEDS_RECONCILIATION, never to the terminal REJECTED state");
  }

  @Test
  @DisplayName("an expired token (401) is a transport failure, not a verdict on the invoice")
  void unauthorizedIsUnreachableNotRejected() {
    IssuanceResult result = callWith(401, "{\"data\":{\"is_validated\":false},\"message\":\"Unauthenticated.\"}");

    assertEquals(IssuanceOutcome.UNREACHABLE, result.outcome());
  }

  @Test
  @DisplayName("a 502 from a gateway in front of Factus is unreachable, even with a JSON envelope")
  void badGatewayIsUnreachable() {
    IssuanceResult result = callWith(502, "{\"data\":{\"is_validated\":false,\"errors\":{}}}");

    assertEquals(IssuanceOutcome.UNREACHABLE, result.outcome());
  }

  @Test
  @DisplayName("a genuine 201 rejection is still REJECTED — the fix must not swallow real DIAN verdicts")
  void genuineRejectionIsStillRejected() {
    IssuanceResult result =
        callWith(
            201,
            "{\"status\":\"Created\",\"data\":{\"is_validated\":false,\"errors\":{\"FAJ44b\":\"invalid total\"}}}");

    assertEquals(IssuanceOutcome.REJECTED, result.outcome());
    assertEquals(1, result.warnings().size());
  }

  @Test
  @DisplayName("a genuine 201 acceptance still yields the CUFE")
  void genuineAcceptanceStillWorks() {
    IssuanceResult result =
        callWith(201, "{\"data\":{\"is_validated\":true,\"errors\":{},\"cufe\":\"abc123\"}}");

    assertEquals(IssuanceOutcome.ACCEPTED, result.outcome());
    assertEquals("abc123", result.externalReference().orElseThrow());
  }
}
