package com.tributary.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-204: {@code tributary_app} can INSERT/SELECT everywhere but never UPDATE or DELETE {@code
 * fiscal_record}/{@code audit_event}. T-206: {@code tributary_verifier} can only SELECT.
 *
 * <p>{@code SET ROLE} inside the test's own (superuser) session is enough to prove the GRANT/
 * REVOKE state without provisioning a second real login credential — Testcontainers' generated
 * connection user is already a member of every role it creates, so it can assume either one for
 * the duration of a statement and {@code RESET ROLE} afterwards.
 */
class ApplicationRoleGrantsTest extends AbstractPostgresTest {

  private JdbcClient jdbc;
  private UUID recordId;
  private UUID invoiceId;

  @BeforeEach
  void setUp() {
    jdbc = JdbcClient.create(dataSource);
    UUID issuerId = TestFixtures.insertIssuer(dataSource);
    UUID buyerId = TestFixtures.insertBuyer(dataSource);
    invoiceId = TestFixtures.insertDraftInvoice(dataSource, issuerId, buyerId, "biz-" + UUID.randomUUID());

    recordId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO fiscal_record
              (id, invoice_id, regime, record_type, chain_id, sequence, hash, previous_hash, canonical_payload)
            VALUES (?, ?, 'ES', 'ISSUANCE', ?, 1, ?, NULL, '{}')
            """)
        .params(recordId, invoiceId, UUID.randomUUID(), "a".repeat(64))
        .update();
  }

  @Test
  @DisplayName("tributary_app: UPDATE on fiscal_record is denied by GRANT, independent of the trigger")
  void applicationRoleCannotUpdateFiscalRecord() {
    assertThrows(
        BadSqlGrammarException.class, // Spring's translation of "permission denied" (SQLSTATE 42501)
        () -> withRole("tributary_app", () ->
            jdbc.sql("UPDATE fiscal_record SET canonical_payload = '{}' WHERE id = ?").param(recordId).update()));
  }

  @Test
  @DisplayName("tributary_app: DELETE on fiscal_record is denied by GRANT")
  void applicationRoleCannotDeleteFiscalRecord() {
    assertThrows(
        BadSqlGrammarException.class,
        () -> withRole("tributary_app", () ->
            jdbc.sql("DELETE FROM fiscal_record WHERE id = ?").param(recordId).update()));
  }

  @Test
  @DisplayName("tributary_app: INSERT on fiscal_record is allowed — the application must still be able to append")
  void applicationRoleCanInsertFiscalRecord() {
    UUID chainId = UUID.randomUUID();
    assertDoesNotThrow(
        () -> withRole("tributary_app", () ->
            jdbc.sql(
                    """
                    INSERT INTO fiscal_record
                      (invoice_id, regime, record_type, chain_id, sequence, hash, previous_hash, canonical_payload)
                    VALUES (?, 'ES', 'ISSUANCE', ?, 1, ?, NULL, '{}')
                    """)
                .params(invoiceId, chainId, "b".repeat(64))
                .update()));
  }

  @Test
  @DisplayName("tributary_verifier: SELECT on fiscal_record is allowed")
  void verifierRoleCanSelect() {
    assertDoesNotThrow(
        () -> withRole("tributary_verifier", () ->
            jdbc.sql("SELECT count(*) FROM fiscal_record").query(Long.class).single()));
  }

  @Test
  @DisplayName("tributary_verifier: INSERT on fiscal_record is denied — read-only means read-only")
  void verifierRoleCannotInsert() {
    assertThrows(
        BadSqlGrammarException.class,
        () -> withRole("tributary_verifier", () ->
            jdbc.sql(
                    "INSERT INTO fiscal_record (invoice_id, regime, record_type, chain_id, sequence, hash, canonical_payload) "
                        + "VALUES (?, 'ES', 'ISSUANCE', ?, 1, ?, '{}')")
                .params(invoiceId, UUID.randomUUID(), "c".repeat(64))
                .update()));
  }

  @Test
  @DisplayName("tributary_verifier: UPDATE on fiscal_record is denied")
  void verifierRoleCannotUpdate() {
    assertThrows(
        BadSqlGrammarException.class,
        () -> withRole("tributary_verifier", () ->
            jdbc.sql("UPDATE fiscal_record SET canonical_payload = '{}' WHERE id = ?").param(recordId).update()));
  }

  private void withRole(String role, Runnable action) {
    jdbc.sql("SET ROLE " + role).update();
    try {
      action.run();
    } finally {
      jdbc.sql("RESET ROLE").update();
    }
  }
}
