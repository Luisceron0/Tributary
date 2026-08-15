package com.tributary.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tributary.application.port.FiscalRecordPort;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-404 (ADR-009): {@code GetRecordVerificationUseCase} assembles the exact six-field response
 * ADR-009 specifies for the one unauthenticated public endpoint in the system — no PII, no
 * amounts, no fiscal identifiers. The HTTP transport (an actual {@code @RestController}) is
 * phase 7's job (SRS 6.2: {@code tributary-api}'s Spring Boot wiring); this is the part
 * buildable ahead of that without pulling Spring Boot into the dependency graph early.
 */
class GetRecordVerificationUseCaseTest {

  private static final class FakeFiscalRecordPort implements FiscalRecordPort {
    private RecordSummary summary;

    @Override
    public UUID append(
        UUID invoiceId, String regime, String recordType, UUID chainId,
        BiFunction<Optional<ChainHead>, Long, NewRecord> computeNewRecord) {
      throw new UnsupportedOperationException("not needed for this test");
    }

    @Override
    public Optional<RecordSummary> findById(UUID recordId) {
      return Optional.ofNullable(summary);
    }
  }

  @Test
  @DisplayName("assembles exactly ADR-009's six fields from a fiscal record")
  void assemblesTheAdr009View() {
    FakeFiscalRecordPort port = new FakeFiscalRecordPort();
    UUID recordId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-08-15T10:00:00Z");
    port.summary = new FiscalRecordPort.RecordSummary(recordId, "a".repeat(64), Optional.of("b".repeat(64)), 2, createdAt);

    GetRecordVerificationUseCase useCase = new GetRecordVerificationUseCase(port);
    Optional<RecordVerificationView> result = useCase.execute(recordId);

    assertTrue(result.isPresent());
    RecordVerificationView view = result.orElseThrow();
    assertEquals(recordId, view.recordId());
    assertEquals("a".repeat(64), view.hash());
    assertEquals(Optional.of("b".repeat(64)), view.previousHash());
    assertEquals(2, view.chainPosition());
    assertEquals(createdAt, view.issuedAt());
    assertFalse(view.nonSubmittedNotice().isBlank());
  }

  @Test
  @DisplayName("a genesis record's previousHash is empty, not a synthetic value")
  void genesisRecordHasNoPreviousHash() {
    FakeFiscalRecordPort port = new FakeFiscalRecordPort();
    UUID recordId = UUID.randomUUID();
    port.summary = new FiscalRecordPort.RecordSummary(recordId, "a".repeat(64), Optional.empty(), 1, Instant.now());

    RecordVerificationView view = new GetRecordVerificationUseCase(port).execute(recordId).orElseThrow();
    assertTrue(view.previousHash().isEmpty());
  }

  @Test
  @DisplayName("an unknown record id returns empty — the use case does not invent a 404 body")
  void unknownRecordReturnsEmpty() {
    FakeFiscalRecordPort port = new FakeFiscalRecordPort(); // summary left null
    Optional<RecordVerificationView> result = new GetRecordVerificationUseCase(port).execute(UUID.randomUUID());
    assertTrue(result.isEmpty());
  }
}
