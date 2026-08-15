package com.tributary.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * RF-006 (T-206) and CV-03 (T-207): {@link ChainVerifier} walks a chain, recomputing hashes and
 * comparing against what is stored. The hash function used here — {@code
 * SHA-256(previousHash + canonicalPayload)} — is a placeholder standing in for T-401's real
 * canonicalization, which does not exist yet (phase 4); {@link ChainVerifier} takes it as a
 * dependency for exactly this reason, so these tests exercise the verification MECHANICS, not a
 * specific algorithm.
 */
class ChainVerifierTest extends AbstractPostgresTest {

  private JdbcClient jdbc;
  private FiscalRecordRepository repository;
  private ChainVerifier verifier;
  private UUID invoiceId;

  @BeforeEach
  void setUp() {
    jdbc = JdbcClient.create(dataSource);
    repository = new FiscalRecordRepository(dataSource, new JdbcTransactionManager(dataSource));
    verifier = new ChainVerifier(dataSource, ChainVerifierTest::placeholderHash);
    UUID issuerId = TestFixtures.insertIssuer(dataSource);
    UUID buyerId = TestFixtures.insertBuyer(dataSource);
    invoiceId = TestFixtures.insertDraftInvoice(dataSource, issuerId, buyerId, "biz-" + UUID.randomUUID());
  }

  /** Placeholder only — T-401 defines the real canonicalization + hash formula (phase 4). */
  private static String placeholderHash(Optional<String> previousHash, String canonicalPayloadJson) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      String input = previousHash.orElse("") + canonicalPayloadJson;
      return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 must be available on every JVM", e);
    }
  }

  private UUID appendValidRecord(UUID chainId, String payloadJson) {
    return repository.append(
        invoiceId,
        "ES",
        "ISSUANCE",
        chainId,
        (head, sequence) ->
            new FiscalRecordRepository.NewRecord(
                placeholderHash(head.map(FiscalRecordRepository.ChainHead::hash), payloadJson), payloadJson));
  }

  @Test
  @DisplayName("a healthy chain verifies INTACT")
  void healthyChainIsIntact() {
    UUID chainId = UUID.randomUUID();
    appendValidRecord(chainId, "{\"n\":1}");
    appendValidRecord(chainId, "{\"n\":2}");
    appendValidRecord(chainId, "{\"n\":3}");

    ChainVerifier.VerificationResult result = verifier.verify(chainId);

    assertInstanceOf(ChainVerifier.VerificationResult.Intact.class, result);
    assertEquals(3, ((ChainVerifier.VerificationResult.Intact) result).recordsVerified());
  }

  @Test
  @DisplayName("CV-03: tampering an intermediate record (trigger disabled) is caught, naming that exact record")
  void tamperDetection() {
    UUID chainId = UUID.randomUUID();
    UUID first = appendValidRecord(chainId, "{\"n\":1}");
    UUID second = appendValidRecord(chainId, "{\"n\":2}");
    UUID third = appendValidRecord(chainId, "{\"n\":3}");

    // T-207's own scenario: the trigger is deliberately disabled to simulate the direct-DB-access
    // threat (T-001) the trigger normally blocks (see V3's header comment) — proving the SEPARATE
    // verifier catches what got through, rather than proving the trigger works (T-202 already did).
    jdbc.sql("ALTER TABLE fiscal_record DISABLE TRIGGER fiscal_record_before_update_reject").update();
    try {
      jdbc.sql("UPDATE fiscal_record SET canonical_payload = '{\"n\":999,\"tampered\":true}' WHERE id = ?")
          .param(second)
          .update();
    } finally {
      jdbc.sql("ALTER TABLE fiscal_record ENABLE TRIGGER fiscal_record_before_update_reject").update();
    }

    ChainVerifier.VerificationResult result = verifier.verify(chainId);

    assertInstanceOf(ChainVerifier.VerificationResult.Broken.class, result);
    ChainVerifier.VerificationResult.Broken broken = (ChainVerifier.VerificationResult.Broken) result;
    assertEquals(second, broken.brokenRecordId(), "must name the tampered record exactly, not first or third");
    assertEquals(first, broken.predecessorId());
    assertEquals(1, broken.totalMismatches());
    assertEquals(3, broken.recordsVerified());
  }

  @Test
  @DisplayName("RF-006 performance: 1,000 healthy records verify INTACT in under 2 seconds")
  void performanceOnAThousandRecords() {
    UUID chainId = UUID.randomUUID();
    for (int i = 0; i < 1000; i++) {
      appendValidRecord(chainId, "{\"n\":" + i + "}");
    }

    long startNanos = System.nanoTime();
    ChainVerifier.VerificationResult result = verifier.verify(chainId);
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
    System.out.println("ChainVerifier.verify() on 1000 records: " + elapsedMillis + "ms (budget 2000ms)");

    assertInstanceOf(ChainVerifier.VerificationResult.Intact.class, result);
    assertEquals(1000, ((ChainVerifier.VerificationResult.Intact) result).recordsVerified());
    assertTrue(elapsedMillis < 2000, () -> "verify() took " + elapsedMillis + "ms, budget is 2000ms");
  }
}
