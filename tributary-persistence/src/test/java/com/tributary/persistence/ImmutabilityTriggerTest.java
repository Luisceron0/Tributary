package com.tributary.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-202 / CV-02: {@code fiscal_record} rejects UPDATE and DELETE outright (V3 migration). Also
 * covers {@code audit_event}, which V3 protects with the same mechanism for the same reason
 * (SRS 5.3's append-only audit policy).
 */
class ImmutabilityTriggerTest extends AbstractPostgresTest {

  private JdbcClient jdbc;
  private UUID recordId;
  private UUID chainId;

  @BeforeEach
  void setUp() {
    jdbc = JdbcClient.create(dataSource);
    UUID issuerId = TestFixtures.insertIssuer(dataSource);
    UUID buyerId = TestFixtures.insertBuyer(dataSource);
    UUID invoiceId =
        TestFixtures.insertDraftInvoice(dataSource, issuerId, buyerId, "biz-" + UUID.randomUUID());

    chainId = UUID.randomUUID();
    recordId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO fiscal_record
              (id, invoice_id, regime, record_type, chain_id, sequence, hash, previous_hash, canonical_payload)
            VALUES (?, ?, 'ES', 'ISSUANCE', ?, 1, ?, NULL, '{"original":true}')
            """)
        .params(recordId, invoiceId, chainId, "a".repeat(64))
        .update();
  }

  @Test
  @DisplayName("CV-02: UPDATE on a chained fiscal_record row is rejected — ERROR, 0 rows affected")
  void rejectsUpdate() {
    assertThrows(
        UncategorizedSQLException.class,
        () -> jdbc.sql("UPDATE fiscal_record SET canonical_payload = '{\"tampered\":true}' WHERE id = ?")
            .param(recordId)
            .update());

    String payload =
        jdbc.sql("SELECT canonical_payload FROM fiscal_record WHERE id = ?")
            .param(recordId)
            .query(String.class)
            .single();
    // Exact byte match, no reformatting — canonical_payload is TEXT, not JSONB (see V1's comment).
    assertEquals("{\"original\":true}", payload, "the row must be exactly as inserted");
  }

  @Test
  @DisplayName("DELETE on a chained fiscal_record row is rejected — ERROR, 0 rows affected")
  void rejectsDelete() {
    assertThrows(
        UncategorizedSQLException.class,
        () -> jdbc.sql("DELETE FROM fiscal_record WHERE id = ?").param(recordId).update());

    Long count =
        jdbc.sql("SELECT count(*) FROM fiscal_record WHERE id = ?").param(recordId).query(Long.class).single();
    assertEquals(1L, count, "the row must still exist");
  }

  @Test
  @DisplayName("audit_event rejects UPDATE and DELETE the same way")
  void auditEventIsAlsoImmutable() {
    UUID eventId = UUID.randomUUID();
    jdbc.sql("INSERT INTO audit_event (id, actor, action, entity, result) VALUES (?, 'operator-1', 'ISSUE', 'invoice', 'SUCCESS')")
        .param(eventId)
        .update();

    assertThrows(
        UncategorizedSQLException.class,
        () -> jdbc.sql("UPDATE audit_event SET result = 'TAMPERED' WHERE id = ?").param(eventId).update());
    assertThrows(
        UncategorizedSQLException.class,
        () -> jdbc.sql("DELETE FROM audit_event WHERE id = ?").param(eventId).update());

    Long count =
        jdbc.sql("SELECT count(*) FROM audit_event WHERE id = ? AND result = 'SUCCESS'")
            .param(eventId)
            .query(Long.class)
            .single();
    assertEquals(1L, count);
  }
}
