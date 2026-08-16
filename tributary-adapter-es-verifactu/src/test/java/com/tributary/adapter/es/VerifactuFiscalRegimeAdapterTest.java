package com.tributary.adapter.es;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tributary.application.port.CancellationResult;
import com.tributary.application.port.FiscalRecordPort;
import com.tributary.application.port.IssuanceOutcome;
import com.tributary.application.port.IssuanceResult;
import com.tributary.application.port.InvoiceRepository;
import com.tributary.application.port.QueryOutcome;
import com.tributary.application.port.RegimeQueryResult;
import com.tributary.domain.Buyer;
import com.tributary.domain.DocumentState;
import com.tributary.domain.Invoice;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.Issuer;
import com.tributary.domain.Money;
import com.tributary.domain.Quantity;
import com.tributary.domain.TaxRate;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-706 (phase 7): the real {@code FiscalRegimePort} for ES — RD 1007/2023's "registro de alta"
 * (issue) and "registro de anulación" (cancel, RF-004), both real chained rows via {@link
 * FiscalRecordPort}. Uses a hand-written fake for that port (this module depends only on {@code
 * tributary-application}, never on {@code tributary-persistence} — ADR-001's dependency
 * direction), the same pattern {@code FactusFiscalRegimeAdapterTest} uses WireMock for on the CO
 * side. Full real-database chain semantics (the actual point of RF-003/RF-004) are proven
 * separately in {@code VerifactuChainIntegrationTest}, already committed in phase 4/7 — this test
 * is about the adapter's own logic: what it asks the port to store and how it interprets the
 * result, not whether PostgreSQL's chain trigger holds.
 */
class VerifactuFiscalRegimeAdapterTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");
  private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC);

  private static Invoice sampleInvoice(String businessKey, DocumentState state) {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    Invoice draft =
        Invoice.draft(businessKey, ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));
    return state == DocumentState.DRAFT ? draft : draft.transitionTo(state);
  }

  /** Records every append() call and lets a test control the ChainHead each one sees. */
  private static final class FakeFiscalRecordPort implements FiscalRecordPort {
    final List<Object[]> appendCalls = new ArrayList<>();
    final Map<UUID, RecordSummary> recordsById = new LinkedHashMap<>();
    Optional<ChainHead> currentHead = Optional.empty();

    @Override
    public UUID append(
        UUID invoiceId, String regime, String recordType, UUID chainId,
        BiFunction<Optional<ChainHead>, Long, NewRecord> computeNewRecord) {
      appendCalls.add(new Object[] {invoiceId, regime, recordType, chainId});
      long nextSequence = currentHead.map(ChainHead::sequence).orElse(0L) + 1;
      NewRecord newRecord = computeNewRecord.apply(currentHead, nextSequence);
      UUID id = UUID.randomUUID();
      RecordSummary summary =
          new RecordSummary(id, newRecord.hash(), currentHead.map(ChainHead::hash), nextSequence, Instant.now());
      recordsById.put(id, summary);
      latestByInvoiceAndType.computeIfAbsent(invoiceId, k -> new LinkedHashMap<>()).put(recordType, summary);
      currentHead = Optional.of(new ChainHead(newRecord.hash(), nextSequence));
      return id;
    }

    @Override
    public Optional<RecordSummary> findById(UUID recordId) {
      return Optional.ofNullable(recordsById.get(recordId));
    }

    final Map<UUID, Map<String, RecordSummary>> latestByInvoiceAndType = new LinkedHashMap<>();

    @Override
    public Optional<RecordSummary> findLatestByInvoiceIdAndRecordType(UUID invoiceId, String recordType) {
      return Optional.ofNullable(latestByInvoiceAndType.getOrDefault(invoiceId, Map.of()).get(recordType));
    }
  }

  private static final class FakeInvoiceRepository implements InvoiceRepository {
    private final Map<String, UUID> ids = new HashMap<>();

    UUID registerBusinessKey(String businessKey) {
      return ids.computeIfAbsent(businessKey, k -> UUID.randomUUID());
    }

    @Override
    public Optional<Invoice> findByBusinessKey(String businessKey) {
      throw new UnsupportedOperationException("not needed by this test");
    }

    @Override
    public void save(Invoice invoice) {
      throw new UnsupportedOperationException("not needed by this test");
    }

    @Override
    public long countByBusinessKey(String businessKey) {
      throw new UnsupportedOperationException("not needed by this test");
    }

    @Override
    public boolean tryTransition(String businessKey, DocumentState from, DocumentState to) {
      throw new UnsupportedOperationException("not needed by this test");
    }

    @Override
    public Optional<UUID> findIdByBusinessKey(String businessKey) {
      return Optional.ofNullable(ids.get(businessKey));
    }
  }

  @Test
  @DisplayName("issue() appends a real chained ISSUANCE record and reports ACCEPTED with the chain position as externalReference")
  void issueAppendsAnIssuanceRecord() {
    FakeFiscalRecordPort fiscalRecordPort = new FakeFiscalRecordPort();
    FakeInvoiceRepository invoiceRepository = new FakeInvoiceRepository();
    Invoice invoice = sampleInvoice("biz-key-1", DocumentState.SUBMITTING);
    invoiceRepository.registerBusinessKey(invoice.businessKey());
    VerifactuFiscalRegimeAdapter adapter =
        new VerifactuFiscalRegimeAdapter(fiscalRecordPort, invoiceRepository, FIXED_CLOCK);

    IssuanceResult result = adapter.issue(invoice);

    assertEquals(IssuanceOutcome.ACCEPTED, result.outcome());
    assertTrue(result.externalReference().isPresent());
    assertEquals(1, fiscalRecordPort.appendCalls.size());
    Object[] call = fiscalRecordPort.appendCalls.get(0);
    assertEquals("ES", call[1]);
    assertEquals("ISSUANCE", call[2]);
  }

  @Test
  @DisplayName("the chainId is deterministic from the issuer's tax id — the same issuer always resumes the same chain")
  void chainIdIsDeterministicPerIssuer() {
    FakeFiscalRecordPort fiscalRecordPort = new FakeFiscalRecordPort();
    FakeInvoiceRepository invoiceRepository = new FakeInvoiceRepository();
    VerifactuFiscalRegimeAdapter adapter =
        new VerifactuFiscalRegimeAdapter(fiscalRecordPort, invoiceRepository, FIXED_CLOCK);

    Invoice first = sampleInvoice("biz-key-1", DocumentState.SUBMITTING);
    invoiceRepository.registerBusinessKey(first.businessKey());
    adapter.issue(first);
    Invoice second = sampleInvoice("biz-key-2", DocumentState.SUBMITTING);
    invoiceRepository.registerBusinessKey(second.businessKey());
    adapter.issue(second);

    UUID chainIdFirst = (UUID) fiscalRecordPort.appendCalls.get(0)[3];
    UUID chainIdSecond = (UUID) fiscalRecordPort.appendCalls.get(1)[3];
    assertEquals(chainIdFirst, chainIdSecond, "the same issuer's two invoices must land on the SAME chain, not one each");
  }

  @Test
  @DisplayName("RF-004: cancel() appends an ANULACIÓN record referencing the original alta's hash")
  void cancelAppendsAnAnulacionRecord() {
    FakeFiscalRecordPort fiscalRecordPort = new FakeFiscalRecordPort();
    FakeInvoiceRepository invoiceRepository = new FakeInvoiceRepository();
    Invoice invoice = sampleInvoice("biz-key-1", DocumentState.SUBMITTING);
    invoiceRepository.registerBusinessKey(invoice.businessKey());
    VerifactuFiscalRegimeAdapter adapter =
        new VerifactuFiscalRegimeAdapter(fiscalRecordPort, invoiceRepository, FIXED_CLOCK);
    adapter.issue(invoice);

    CancellationResult result = adapter.cancel(invoice, "customer requested a refund");

    assertTrue(result.accepted());
    assertEquals(2, fiscalRecordPort.appendCalls.size());
    assertEquals("ANULACION", fiscalRecordPort.appendCalls.get(1)[2]);
  }

  @Test
  @DisplayName("RF-004's own alternative flow: cancelling an already-cancelled invoice is refused, not a duplicate ANULACIÓN")
  void cancellingAnAlreadyCancelledInvoiceIsRefused() {
    FakeFiscalRecordPort fiscalRecordPort = new FakeFiscalRecordPort();
    FakeInvoiceRepository invoiceRepository = new FakeInvoiceRepository();
    Invoice invoice = sampleInvoice("biz-key-1", DocumentState.SUBMITTING);
    invoiceRepository.registerBusinessKey(invoice.businessKey());
    VerifactuFiscalRegimeAdapter adapter =
        new VerifactuFiscalRegimeAdapter(fiscalRecordPort, invoiceRepository, FIXED_CLOCK);
    adapter.issue(invoice);
    adapter.cancel(invoice, "first cancellation");

    CancellationResult secondAttempt = adapter.cancel(invoice, "second cancellation attempt");

    assertFalse(secondAttempt.accepted());
    assertEquals(2, fiscalRecordPort.appendCalls.size(), "no third record should ever be appended");
  }

  @Test
  @DisplayName("query(): FOUND_VALIDATED when the businessKey resolves to a real record, NOT_FOUND otherwise")
  void queryReflectsWhetherARecordExists() {
    FakeFiscalRecordPort fiscalRecordPort = new FakeFiscalRecordPort();
    FakeInvoiceRepository invoiceRepository = new FakeInvoiceRepository();
    Invoice invoice = sampleInvoice("biz-key-1", DocumentState.SUBMITTING);
    invoiceRepository.registerBusinessKey(invoice.businessKey());
    VerifactuFiscalRegimeAdapter adapter =
        new VerifactuFiscalRegimeAdapter(fiscalRecordPort, invoiceRepository, FIXED_CLOCK);

    RegimeQueryResult beforeIssue = adapter.query(invoice.businessKey());
    adapter.issue(invoice);
    RegimeQueryResult afterIssue = adapter.query(invoice.businessKey());

    assertEquals(QueryOutcome.NOT_FOUND, beforeIssue.outcome());
    assertEquals(QueryOutcome.FOUND_VALIDATED, afterIssue.outcome());
  }

  @Test
  @DisplayName("query() for a businessKey this deployment never registered (no persistence id at all) is NOT_FOUND, not an error")
  void queryForAnUnknownBusinessKeyIsNotFound() {
    VerifactuFiscalRegimeAdapter adapter =
        new VerifactuFiscalRegimeAdapter(new FakeFiscalRecordPort(), new FakeInvoiceRepository(), FIXED_CLOCK);

    RegimeQueryResult result = adapter.query("never-existed");

    assertEquals(QueryOutcome.NOT_FOUND, result.outcome());
  }
}
