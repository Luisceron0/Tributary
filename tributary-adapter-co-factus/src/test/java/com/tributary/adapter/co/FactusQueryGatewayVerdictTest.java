package com.tributary.adapter.co;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.tributary.application.port.QueryOutcome;
import com.tributary.application.port.RegimeQueryResult;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Audit findings on the reconciliation query path — the same class of mistake {@link
 * FactusBillGatewayHttpStatusTest} pins on the issuance path, found by asking the same question of
 * the other gateway.
 *
 * <p><b>Finding 1 — a bare {@code is_validated:false} was treated as a DIAN rejection.</b> {@code
 * ReconcileInvoiceUseCase.confirmRejected} turns {@link QueryOutcome#FOUND_REJECTED} into {@code
 * DocumentState.REJECTED}, whose transition set is {@code EnumSet.noneOf(...)} — terminal, forever.
 * But the list endpoint reports {@code is_validated:false} for any bill that is not validated
 * <i>yet</i>, not only for one DIAN refused, and reconciliation runs precisely when the original
 * response was lost — the moment a document is most likely to still be in flight. Without
 * accompanying {@code errors}, there is no evidence of a verdict, and ADR-003 forbids converting an
 * ambiguous state into a definitive one. {@link QueryOutcome#AMBIGUOUS} is the honest answer: it
 * routes to the three-strikes MANUAL_REVIEW path instead of an irreversible terminal state.
 *
 * <p><b>Finding 2 — {@code errors} was discarded entirely on this path.</b> The gateway always
 * returned {@code List.of()} for warnings. That silently broke two things at once: a confirmed
 * rejection reached the operator with no reason attached, and — because {@code
 * ReconcileInvoiceUseCase.adopt} already branches on {@code queryResult.warnings().isEmpty()} — a
 * document DIAN accepted WITH warnings was recorded as a clean {@code ISSUED} when adopted through
 * reconciliation, instead of {@code ISSUED_WITH_WARNINGS}. The same fiscal document therefore ended
 * in a different state depending only on whether its original HTTP response happened to arrive.
 * RF-002 is explicit that DIAN warnings are recorded and never discarded.
 */
class FactusQueryGatewayVerdictTest {

  @RegisterExtension
  static WireMockExtension wireMock =
      WireMockExtension.newInstance().options(WireMockConfiguration.wireMockConfig().dynamicPort()).build();

  private FactusCredentials credentials;
  private FactusQueryGateway gateway;

  @BeforeEach
  void setUp() {
    credentials = new FactusCredentials(wireMock.baseUrl(), "id", "secret", "user", "pass");
    gateway = new FactusQueryGateway(new FactusRateLimiter(60, Duration.ofSeconds(60)));
    wireMock.resetAll();
  }

  private FactusToken aToken() {
    return new FactusToken("access-abc", "refresh-xyz", Instant.now().plusSeconds(3600));
  }

  private RegimeQueryResult queryReturning(String bodyItem) {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/bills"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"OK\",\"message\":\"ok\",\"data\":{\"data\":["
                            + bodyItem
                            + "],\"pagination\":{\"total\":1}}}")));
    return gateway.query(credentials, aToken(), "biz-key");
  }

  @Test
  @DisplayName("is_validated=false with NO errors is not a verdict — AMBIGUOUS, never terminal REJECTED")
  void notValidatedWithoutErrorsIsAmbiguous() {
    RegimeQueryResult result =
        queryReturning("{\"reference_code\":\"biz-key\",\"is_validated\":false,\"errors\":{}}");

    assertEquals(
        QueryOutcome.AMBIGUOUS,
        result.outcome(),
        "no errors means DIAN has not ruled — confirming a rejection here is irreversible");
  }

  @Test
  @DisplayName("is_validated=false with the errors field absent altogether is equally not a verdict")
  void notValidatedWithAbsentErrorsIsAmbiguous() {
    RegimeQueryResult result = queryReturning("{\"reference_code\":\"biz-key\",\"is_validated\":false}");

    assertEquals(QueryOutcome.AMBIGUOUS, result.outcome());
  }

  @Test
  @DisplayName("is_validated=false WITH errors is a real rejection, and the reasons reach the caller")
  void notValidatedWithErrorsIsRejectedAndCarriesReasons() {
    RegimeQueryResult result =
        queryReturning(
            "{\"reference_code\":\"biz-key\",\"is_validated\":false,"
                + "\"errors\":{\"FAJ44b\":\"invalid total\",\"RUT01\":\"issuer not registered\"}}");

    assertEquals(QueryOutcome.FOUND_REJECTED, result.outcome());
    assertEquals(
        2,
        result.warnings().size(),
        "a permanently rejected document must carry the reason DIAN gave for it");
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("FAJ44b")));
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("issuer not registered")));
  }

  @Test
  @DisplayName("a validated document carrying errors keeps them — otherwise reconciliation downgrades ISSUED_WITH_WARNINGS to ISSUED")
  void validatedWithErrorsPropagatesWarnings() {
    RegimeQueryResult result =
        queryReturning(
            "{\"reference_code\":\"biz-key\",\"is_validated\":true,\"number\":\"SETP990015225\","
                + "\"errors\":{\"RUT01\":\"notificacion DIAN\"}}");

    assertEquals(QueryOutcome.FOUND_VALIDATED, result.outcome());
    assertEquals("SETP990015225", result.externalReference().orElseThrow());
    assertEquals(
        1,
        result.warnings().size(),
        "ReconcileInvoiceUseCase.adopt() branches on this list to choose ISSUED vs ISSUED_WITH_WARNINGS");
  }

  @Test
  @DisplayName("a clean validated document still reports no warnings")
  void validatedWithoutErrorsHasNoWarnings() {
    RegimeQueryResult result =
        queryReturning(
            "{\"reference_code\":\"biz-key\",\"is_validated\":true,\"number\":\"SETP1\",\"errors\":{}}");

    assertEquals(QueryOutcome.FOUND_VALIDATED, result.outcome());
    assertTrue(result.warnings().isEmpty());
  }
}
