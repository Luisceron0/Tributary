package com.tributary.persistence;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-201: the {@code BEFORE INSERT} trigger on {@code fiscal_record} (V2 migration). Formalizes
 * the five cases already exercised by hand against a throwaway container during development —
 * this is what makes them regression-proof.
 *
 * <p>Two different exception types on purpose, both from real runs, not assumed: a bare {@code
 * RAISE EXCEPTION} in PL/pgSQL carries SQLSTATE P0001, which Spring's error-code translator does
 * not classify as a constraint violation — it surfaces as {@link UncategorizedSQLException}, not
 * {@link DataIntegrityViolationException}. Only the genuine {@code UNIQUE(chain_id,
 * previous_hash)} violation (SQLSTATE 23505, two records claiming the same predecessor) gets the
 * latter. Getting this wrong doesn't fail loudly — {@code assertThrows} with the wrong exception
 * type still reports a failure, but a plain {@code catch (DataIntegrityViolationException e)} in
 * real repository code would silently let a trigger-raised rejection propagate as an unhandled
 * exception instead of the intended one.
 */
class FiscalRecordChainTriggerTest extends AbstractPostgresTest {

  private JdbcClient jdbc;
  private UUID invoiceId;

  @BeforeEach
  void setUp() {
    jdbc = JdbcClient.create(dataSource);
    UUID issuerId = TestFixtures.insertIssuer(dataSource);
    UUID buyerId = TestFixtures.insertBuyer(dataSource);
    invoiceId = TestFixtures.insertDraftInvoice(dataSource, issuerId, buyerId, "biz-" + UUID.randomUUID());
  }

  private void insertRecord(UUID chainId, long sequence, String hash, String previousHash) {
    jdbc.sql(
            """
            INSERT INTO fiscal_record
              (invoice_id, regime, record_type, chain_id, sequence, hash, previous_hash, canonical_payload)
            VALUES (?, 'ES', 'ISSUANCE', ?, ?, ?, ?, '{}')
            """)
        .params(invoiceId, chainId, sequence, hash, previousHash)
        .update();
  }

  @Test
  @DisplayName("the first record of a chain must have previous_hash NULL")
  void firstRecordRejectsNonNullPreviousHash() {
    UUID chainId = UUID.randomUUID();
    assertThrows(
        UncategorizedSQLException.class,
        () -> insertRecord(chainId, 1, "a".repeat(64), "b".repeat(64)));
  }

  @Test
  @DisplayName("the first record of a chain must have sequence 1")
  void firstRecordRejectsWrongSequence() {
    UUID chainId = UUID.randomUUID();
    assertThrows(
        UncategorizedSQLException.class, () -> insertRecord(chainId, 2, "a".repeat(64), null));
  }

  @Test
  @DisplayName("a well-formed genesis record is accepted")
  void firstRecordAccepted() {
    UUID chainId = UUID.randomUUID();
    insertRecord(chainId, 1, "a".repeat(64), null);
    Long count =
        jdbc.sql("SELECT count(*) FROM fiscal_record WHERE chain_id = ?").param(chainId).query(Long.class).single();
    org.junit.jupiter.api.Assertions.assertEquals(1L, count);
  }

  @Test
  @DisplayName("a later record whose previous_hash matches no existing row is rejected")
  void rejectsAnUnmatchedPredecessor() {
    UUID chainId = UUID.randomUUID();
    insertRecord(chainId, 1, "a".repeat(64), null);
    assertThrows(
        UncategorizedSQLException.class,
        () -> insertRecord(chainId, 2, "c".repeat(64), "d".repeat(64) /* matches nothing */));
  }

  @Test
  @DisplayName("a correct predecessor with a sequence gap is rejected")
  void rejectsASequenceGap() {
    UUID chainId = UUID.randomUUID();
    insertRecord(chainId, 1, "a".repeat(64), null);
    assertThrows(
        UncategorizedSQLException.class,
        () -> insertRecord(chainId, 3 /* should be 2 */, "c".repeat(64), "a".repeat(64)));
  }

  @Test
  @DisplayName("a correctly chained second record is accepted")
  void acceptsACorrectlyChainedRecord() {
    UUID chainId = UUID.randomUUID();
    insertRecord(chainId, 1, "a".repeat(64), null);
    insertRecord(chainId, 2, "c".repeat(64), "a".repeat(64));

    Long count =
        jdbc.sql("SELECT count(*) FROM fiscal_record WHERE chain_id = ?").param(chainId).query(Long.class).single();
    org.junit.jupiter.api.Assertions.assertEquals(2L, count);
  }

  @Test
  @DisplayName("two records claiming the same predecessor are rejected — never two heads on one chain")
  void rejectsTwoRecordsWithTheSamePredecessor() {
    UUID chainId = UUID.randomUUID();
    insertRecord(chainId, 1, "a".repeat(64), null);
    insertRecord(chainId, 2, "c".repeat(64), "a".repeat(64));

    assertThrows(
        DataIntegrityViolationException.class,
        () -> insertRecord(chainId, 2, "e".repeat(64), "a".repeat(64) /* same predecessor as above */));
  }
}
