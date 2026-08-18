package com.tributary.adapter.co;

import com.fasterxml.jackson.databind.JsonNode;
import com.tributary.application.port.CancellationResult;
import com.tributary.application.port.FiscalRegimePort;
import com.tributary.application.port.IssuanceResult;
import com.tributary.application.port.RegimeQueryResult;
import com.tributary.domain.Invoice;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The CO/Factus implementation of {@link FiscalRegimePort} (ADR-001: one port, three regimes).
 * Ties together the token cache (T-300), the rate limiter shared across issue/query calls
 * (T-301), and the payload mapper (T-303).
 */
public final class FactusFiscalRegimeAdapter implements FiscalRegimePort {

  private final FactusCredentials credentials;
  private final FactusOAuth2Client oauth2Client;
  private final FactusBillGateway billGateway;
  private final FactusQueryGateway queryGateway;
  private final FactusPayloadMapper payloadMapper;

  public FactusFiscalRegimeAdapter(FactusCredentials credentials, int numberingRangeId) {
    this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
    FactusAuthGateway authGateway = new FactusAuthGateway();
    this.oauth2Client = new FactusOAuth2Client(() -> authGateway.fetchToken(credentials), Instant::now);
    // One limiter instance, genuinely shared: Factus's quota is per account, so issuance and
    // reconciliation queries draw from the same 60/min budget (T-301, threat T-010).
    FactusRateLimiter rateLimiter = new FactusRateLimiter(60, Duration.ofSeconds(60));
    this.billGateway = new FactusBillGateway(rateLimiter);
    this.queryGateway = new FactusQueryGateway(rateLimiter);
    this.payloadMapper = new FactusPayloadMapper(numberingRangeId);
  }

  @Override
  public IssuanceResult issue(Invoice invoice) {
    Objects.requireNonNull(invoice, "invoice must not be null");
    FactusToken token = oauth2Client.currentToken();
    JsonNode payload = payloadMapper.toPayload(invoice);
    return billGateway.validate(credentials, token, payload);
  }

  @Override
  public CancellationResult cancel(Invoice original, String correctionReason) {
    // RF-004 (credit notes) is out of T-3xx's scope — phase 3's tasks (T-300..T-309) cover
    // issue/query only. Left unimplemented rather than faked, so a caller that reaches this
    // fails loudly instead of silently no-op'ing a fiscal correction.
    throw new UnsupportedOperationException("Factus credit notes (RF-004) are not implemented — out of phase 3's scope");
  }

  @Override
  public RegimeQueryResult query(String businessKey) {
    Objects.requireNonNull(businessKey, "businessKey must not be null");
    FactusToken token = oauth2Client.currentToken();
    return queryGateway.query(credentials, token, businessKey);
  }
}
