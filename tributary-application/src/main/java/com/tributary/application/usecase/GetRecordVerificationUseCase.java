package com.tributary.application.usecase;

import com.tributary.application.port.FiscalRecordPort;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * RF-003 / T-404 (ADR-009). The HTTP transport for this (an actual {@code @RestController}
 * serving it unauthenticated) is phase 7's job — SRS 6.2 assigns Spring Boot to {@code
 * tributary-api} specifically, and no earlier phase has pulled that framework in ahead of the
 * task that needs it. This use case is the part buildable now: given a record id, assemble
 * exactly ADR-009's six-field view, or nothing if the id doesn't resolve.
 */
public final class GetRecordVerificationUseCase {

  /**
   * Deliberately distinct from {@code VerifactuQrGenerator.NON_SUBMITTED_LEGEND}
   * (tributary-adapter-es-verifactu): that string is embedded in a QR under tight encoding
   * constraints; this one is a JSON field with room to be a full sentence. Both say the same
   * thing — nothing was submitted to the AEAT — worded for their own medium.
   */
  static final String NON_SUBMITTED_NOTICE =
      "This record was not submitted to the AEAT (ADR-005). It is verifiable only against this system.";

  private final FiscalRecordPort fiscalRecordPort;

  public GetRecordVerificationUseCase(FiscalRecordPort fiscalRecordPort) {
    this.fiscalRecordPort = Objects.requireNonNull(fiscalRecordPort, "fiscalRecordPort must not be null");
  }

  public Optional<RecordVerificationView> execute(UUID recordId) {
    Objects.requireNonNull(recordId, "recordId must not be null");
    return fiscalRecordPort
        .findById(recordId)
        .map(
            record ->
                new RecordVerificationView(
                    record.id(),
                    record.hash(),
                    record.previousHash(),
                    record.sequence(),
                    record.createdAt(),
                    NON_SUBMITTED_NOTICE));
  }
}
