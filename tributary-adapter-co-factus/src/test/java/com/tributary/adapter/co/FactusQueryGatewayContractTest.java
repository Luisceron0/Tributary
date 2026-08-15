package com.tributary.adapter.co;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.tributary.application.port.QueryOutcome;
import com.tributary.application.port.RegimeQueryResult;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * RF-008's query, {@code GET /v2/bills?filter[reference_code]=...} — the shape confirmed live
 * against the real sandbox this session: {@code {status, message, data: {data: [...],
 * pagination: {...}}}}, an empty {@code data.data} array when nothing matches.
 */
class FactusQueryGatewayContractTest {

  @RegisterExtension
  static WireMockExtension wireMock =
      WireMockExtension.newInstance().options(WireMockConfiguration.wireMockConfig().dynamicPort()).build();

  private FactusCredentials credentials;
  private FactusQueryGateway gateway;

  @BeforeEach
  void setUp() {
    credentials = new FactusCredentials(wireMock.baseUrl(), "id", "secret", "user", "pass");
    gateway = new FactusQueryGateway();
    wireMock.resetAll();
  }

  private FactusToken aToken() {
    return new FactusToken("access-abc", "refresh-xyz", Instant.now().plusSeconds(3600));
  }

  @Test
  @DisplayName("a validated document found by reference_code -> FOUND_VALIDATED with its number (not cufe — see class note)")
  void foundValidated() {
    // The live sandbox (confirmed this session, T-307) never includes "cufe" on this endpoint's
    // list items — only "number". This response has neither field silently invented.
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/bills"))
            .withQueryParam("filter[reference_code]", equalTo("biz-key-1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"status":"OK","message":"Solicitud exitosa","data":{"data":[
                          {"reference_code":"biz-key-1","is_validated":true,"errors":{},"number":"SETP990015225"}
                        ],"pagination":{"total":1}}}
                        """)));

    RegimeQueryResult result = gateway.query(credentials, aToken(), "biz-key-1");

    assertEquals(QueryOutcome.FOUND_VALIDATED, result.outcome());
    assertEquals("SETP990015225", result.externalReference().orElseThrow());
  }

  @Test
  @DisplayName("nothing matches the reference_code -> NOT_FOUND, the exact shape confirmed live")
  void notFound() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/bills"))
            .withQueryParam("filter[reference_code]", equalTo("never-existed"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"status":"OK","message":"Solicitud exitosa","data":{"data":[],"pagination":{"total":0}}}
                        """)));

    RegimeQueryResult result = gateway.query(credentials, aToken(), "never-existed");

    assertEquals(QueryOutcome.NOT_FOUND, result.outcome());
    assertTrue(result.externalReference().isEmpty());
  }

  @Test
  @DisplayName("a found document with is_validated=false -> FOUND_REJECTED")
  void foundRejected() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/bills"))
            .withQueryParam("filter[reference_code]", equalTo("biz-key-2"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"status":"OK","message":"ok","data":{"data":[
                          {"reference_code":"biz-key-2","is_validated":false,"errors":{"CO01":"missing field"}}
                        ],"pagination":{"total":1}}}
                        """)));

    RegimeQueryResult result = gateway.query(credentials, aToken(), "biz-key-2");

    assertEquals(QueryOutcome.FOUND_REJECTED, result.outcome());
    assertTrue(result.externalReference().isEmpty());
  }

  @Test
  @DisplayName("more than one match for the same reference_code -> AMBIGUOUS, never guessed at")
  void ambiguousOnMultipleMatches() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/bills"))
            .withQueryParam("filter[reference_code]", equalTo("biz-key-3"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"status":"OK","message":"ok","data":{"data":[
                          {"reference_code":"biz-key-3","is_validated":true,"errors":{},"number":"SETP1"},
                          {"reference_code":"biz-key-3","is_validated":true,"errors":{},"number":"SETP2"}
                        ],"pagination":{"total":2}}}
                        """)));

    RegimeQueryResult result = gateway.query(credentials, aToken(), "biz-key-3");

    assertEquals(QueryOutcome.AMBIGUOUS, result.outcome());
  }

  @Test
  @DisplayName("a network/server failure -> AMBIGUOUS, never silently treated as NOT_FOUND")
  void serverErrorIsAmbiguousNotNotFound() {
    wireMock.stubFor(get(urlPathEqualTo("/v2/bills")).willReturn(aResponse().withStatus(500)));

    RegimeQueryResult result = gateway.query(credentials, aToken(), "biz-key-4");

    assertEquals(QueryOutcome.AMBIGUOUS, result.outcome());
  }

  @Test
  @DisplayName("queries by reference_code, using the exact confirmed query parameter shape")
  void usesTheConfirmedQueryParameterShape() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/bills"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""
                        {"status":"OK","message":"ok","data":{"data":[],"pagination":{"total":0}}}
                        """)));

    gateway.query(credentials, aToken(), "biz-key-5");

    wireMock.verify(getRequestedFor(urlPathEqualTo("/v2/bills")).withQueryParam("filter[reference_code]", equalTo("biz-key-5")));
  }
}
