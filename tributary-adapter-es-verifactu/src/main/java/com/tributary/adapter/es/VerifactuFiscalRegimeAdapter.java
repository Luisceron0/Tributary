package com.tributary.adapter.es;

import com.tributary.application.port.CancellationResult;
import com.tributary.application.port.FiscalRecordPort;
import com.tributary.application.port.FiscalRegimePort;
import com.tributary.application.port.InvoiceRepository;
import com.tributary.application.port.IssuanceOutcome;
import com.tributary.application.port.IssuanceResult;
import com.tributary.application.port.QueryOutcome;
import com.tributary.application.port.RegimeQueryResult;
import com.tributary.domain.Invoice;
import com.tributary.domain.Issuer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The real {@code FiscalRegimePort} for ES (phase 7) — RD 1007/2023's "registro de alta" ({@link
 * #issue}) and "registro de anulación" ({@link #cancel}, RF-004), both real chained rows via
 * {@link FiscalRecordPort} (T-205) and the canonicalization/hashing T-400/T-401 already built.
 * Unlike CO (a real HTTP clearance authority), "the regime" for ES <b>is</b> this system's own
 * database — {@link #query} answers from {@code fiscal_record} directly rather than trusting
 * local {@code invoice.state}, because a crash can leave the two disagreeing (the same reasoning
 * RF-008's reconciler already applies to CO).
 *
 * <p>The chain is per-issuer, not per-invoice: RD 1007/2023's real design is one continuous ledger
 * per obligated party (SRS 3: a single issuer, no multi-tenancy, so exactly one chain exists in
 * any given deployment), not an isolated chain per document — {@link #chainIdFor} derives it
 * deterministically from the issuer's own tax id, so the same issuer always resumes the same
 * chain across restarts without needing separate bookkeeping for "which chain is this."
 */
public final class VerifactuFiscalRegimeAdapter implements FiscalRegimePort {

  private static final String RECORD_TYPE_ISSUANCE = "ISSUANCE";
  private static final String RECORD_TYPE_ANULACION = "ANULACION";
  private static final String REGIME = "ES";

  private final FiscalRecordPort fiscalRecordPort;
  private final InvoiceRepository invoiceRepository;
  private final Clock clock;

  public VerifactuFiscalRegimeAdapter(FiscalRecordPort fiscalRecordPort, InvoiceRepository invoiceRepository, Clock clock) {
    this.fiscalRecordPort = Objects.requireNonNull(fiscalRecordPort, "fiscalRecordPort must not be null");
    this.invoiceRepository = Objects.requireNonNull(invoiceRepository, "invoiceRepository must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public IssuanceResult issue(Invoice invoice) {
    Objects.requireNonNull(invoice, "invoice must not be null");
    UUID invoiceId = requireInvoiceId(invoice.businessKey());
    Instant generatedAt = clock.instant();

    UUID recordId =
        fiscalRecordPort.append(
            invoiceId,
            REGIME,
            RECORD_TYPE_ISSUANCE,
            chainIdFor(invoice.issuer()),
            (head, sequence) -> {
              String canonical = VerifactuHasher.canonicalizeAlta(invoice, generatedAt);
              String hash = VerifactuHasher.hash(canonical, head.map(FiscalRecordPort.ChainHead::hash));
              return new FiscalRecordPort.NewRecord(hash, canonical);
            });

    return new IssuanceResult(
        IssuanceOutcome.ACCEPTED, Optional.of(recordId.toString()), List.of(), "ES local chain record " + recordId);
  }

  @Override
  public CancellationResult cancel(Invoice original, String correctionReason) {
    Objects.requireNonNull(original, "original must not be null");
    Objects.requireNonNull(correctionReason, "correctionReason must not be null");
    UUID invoiceId = requireInvoiceId(original.businessKey());

    FiscalRecordPort.RecordSummary altaRecord =
        fiscalRecordPort
            .findLatestByInvoiceIdAndRecordType(invoiceId, RECORD_TYPE_ISSUANCE)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "cannot cancel businessKey=" + original.businessKey() + " — no ISSUANCE record exists to reference"));
    Instant generatedAt = clock.instant();

    UUID recordId =
        fiscalRecordPort.append(
            invoiceId,
            REGIME,
            RECORD_TYPE_ANULACION,
            chainIdFor(original.issuer()),
            (head, sequence) -> {
              String canonical = VerifactuHasher.canonicalizeAnulacion(altaRecord.hash(), correctionReason, generatedAt);
              String hash = VerifactuHasher.hash(canonical, head.map(FiscalRecordPort.ChainHead::hash));
              return new FiscalRecordPort.NewRecord(hash, canonical);
            });

    return new CancellationResult(true, Optional.of(recordId.toString()), "ES local chain record " + recordId);
  }

  @Override
  public RegimeQueryResult query(String businessKey) {
    Objects.requireNonNull(businessKey, "businessKey must not be null");

    Optional<UUID> invoiceId = invoiceRepository.findIdByBusinessKey(businessKey);
    if (invoiceId.isEmpty()) {
      return new RegimeQueryResult(QueryOutcome.NOT_FOUND, Optional.empty(), List.of());
    }

    Optional<FiscalRecordPort.RecordSummary> record =
        fiscalRecordPort.findLatestByInvoiceIdAndRecordType(invoiceId.orElseThrow(), RECORD_TYPE_ISSUANCE);
    if (record.isEmpty()) {
      return new RegimeQueryResult(QueryOutcome.NOT_FOUND, Optional.empty(), List.of());
    }

    return new RegimeQueryResult(
        QueryOutcome.FOUND_VALIDATED, Optional.of(record.orElseThrow().id().toString()), List.of());
  }

  private UUID requireInvoiceId(String businessKey) {
    return invoiceRepository
        .findIdByBusinessKey(businessKey)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "businessKey=" + businessKey + " has no persisted invoice — issue()/cancel() require the "
                        + "invoice to already be saved (IssueInvoiceUseCase's own DRAFT->SUBMITTING commit happens "
                        + "before this is ever called)"));
  }

  private static UUID chainIdFor(Issuer issuer) {
    return UUID.nameUUIDFromBytes(("verifactu-chain:" + issuer.taxIdentifier()).getBytes(StandardCharsets.UTF_8));
  }
}
