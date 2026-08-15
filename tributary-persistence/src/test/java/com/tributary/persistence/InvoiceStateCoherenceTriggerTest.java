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
 * T-203: an invoice cannot become ISSUED/ISSUED_WITH_WARNINGS without a matching accepted
 * issuance_attempt (V4 migration) — the regime-agnostic replacement for the SRS's literal
 * "CHECK cufe IS NOT NULL", explained in V4's own header comment.
 */
class InvoiceStateCoherenceTriggerTest extends AbstractPostgresTest {

  private JdbcClient jdbc;
  private UUID invoiceId;

  @BeforeEach
  void setUp() {
    jdbc = JdbcClient.create(dataSource);
    UUID issuerId = TestFixtures.insertIssuer(dataSource);
    UUID buyerId = TestFixtures.insertBuyer(dataSource);
    invoiceId = TestFixtures.insertDraftInvoice(dataSource, issuerId, buyerId, "biz-" + UUID.randomUUID());
  }

  @Test
  @DisplayName("DRAFT -> ISSUED without a prior issuance_attempt is rejected")
  void rejectsIssuedWithoutProof() {
    assertThrows(
        UncategorizedSQLException.class,
        () -> jdbc.sql("UPDATE invoice SET state = 'ISSUED' WHERE id = ?").param(invoiceId).update());

    String state = jdbc.sql("SELECT state FROM invoice WHERE id = ?").param(invoiceId).query(String.class).single();
    assertEquals("DRAFT", state, "a rejected transition must not have taken effect");
  }

  @Test
  @DisplayName("DRAFT -> ISSUED succeeds once a matching issuance_attempt exists (RF-002's own order)")
  void acceptsIssuedWithProof() {
    TestFixtures.insertAcceptedIssuanceAttempt(dataSource, invoiceId, "CUFE-EXAMPLE-123");

    jdbc.sql("UPDATE invoice SET state = 'ISSUED' WHERE id = ?").param(invoiceId).update();

    String state = jdbc.sql("SELECT state FROM invoice WHERE id = ?").param(invoiceId).query(String.class).single();
    assertEquals("ISSUED", state);
  }

  @Test
  @DisplayName("an issuance_attempt with a null external_reference does not count as proof")
  void aNullExternalReferenceDoesNotSatisfyTheInvariant() {
    UUID attemptId = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO issuance_attempt (id, invoice_id, regime, outcome, external_reference, raw_response) "
                + "VALUES (?, ?, 'CO', 'ACCEPTED', NULL, '{}')")
        .params(attemptId, invoiceId)
        .update();

    assertThrows(
        UncategorizedSQLException.class,
        () -> jdbc.sql("UPDATE invoice SET state = 'ISSUED' WHERE id = ?").param(invoiceId).update());
  }

  @Test
  @DisplayName("a REJECTED issuance_attempt does not count as proof")
  void aRejectedAttemptDoesNotSatisfyTheInvariant() {
    UUID attemptId = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO issuance_attempt (id, invoice_id, regime, outcome, external_reference, raw_response) "
                + "VALUES (?, ?, 'CO', 'REJECTED', 'ref-1', '{}')")
        .params(attemptId, invoiceId)
        .update();

    assertThrows(
        UncategorizedSQLException.class,
        () -> jdbc.sql("UPDATE invoice SET state = 'ISSUED' WHERE id = ?").param(invoiceId).update());
  }

  @Test
  @DisplayName("transitions that never touch ISSUED, like DRAFT -> SUBMITTING, are unaffected")
  void unrelatedTransitionsAreUnaffected() {
    jdbc.sql("UPDATE invoice SET state = 'SUBMITTING' WHERE id = ?").param(invoiceId).update();
    String state = jdbc.sql("SELECT state FROM invoice WHERE id = ?").param(invoiceId).query(String.class).single();
    assertEquals("SUBMITTING", state);
  }
}
