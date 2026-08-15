package com.tributary.persistence;

import com.tributary.application.port.FiscalRecordPort;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Appends rows to a {@code fiscal_record} chain (T-205, RF-003's concurrency requirement).
 *
 * <p>Concurrent inserts targeting the SAME {@code chainId} are serialized by a transaction-scoped
 * PostgreSQL advisory lock, so two threads never both read "the current tail is sequence N" and
 * race to insert N+1. The chain-validation trigger (V2, T-201) would reject the loser of that race
 * anyway, but that means real work thrown away and a retry; the lock avoids the race outright,
 * which is what SRS 6.5's "20 threads produce a sequence without gaps or duplicates" asks for.
 *
 * <p>What hash a new record gets is deliberately NOT this class's decision: canonicalization and
 * SHA-256 (T-400/T-401) are the ES adapter's job (phase 4). This class hands the caller the
 * current chain tail (if any) and lets it compute the new row's hash from that, inside the SAME
 * lock-protected transaction — so "read the tail" and "compute against it" can never be split
 * across two different, potentially stale, views of the chain.
 */
public final class FiscalRecordRepository implements FiscalRecordPort {

  /**
   * A fixed namespace for the two-key advisory lock ({@code pg_advisory_xact_lock(int, int)}).
   * Postgres's single-int advisory lock space (the one-argument overload) is shared by anything in
   * the whole database that uses it; namespacing keeps a future, unrelated advisory-lock user from
   * ever colliding with a chain lock by coincidence. Arbitrary but stable — never change it once
   * anything has shipped, or two deploys could stop seeing each other's locks.
   */
  private static final int CHAIN_LOCK_NAMESPACE = 0x46_52_00_00; // "FR" for fiscal_record, padded

  private final JdbcClient jdbc;
  private final TransactionTemplate transactionTemplate;

  public FiscalRecordRepository(DataSource dataSource, PlatformTransactionManager transactionManager) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    this.transactionTemplate =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
  }

  @Override
  public UUID append(
      UUID invoiceId,
      String regime,
      String recordType,
      UUID chainId,
      BiFunction<Optional<ChainHead>, Long, NewRecord> computeNewRecord) {
    return transactionTemplate.execute(
        status -> {
          // pg_advisory_xact_lock is a SELECT (it returns a void-typed row), not DML — .update()
          // rejects it with "a result was returned when none was expected". Consume the one row
          // via .query() instead; its content is meaningless, only that the call blocked matters.
          jdbc.sql("SELECT pg_advisory_xact_lock(?, hashtext(?))")
              .params(CHAIN_LOCK_NAMESPACE, chainId.toString())
              .query((rs, rowNum) -> true)
              .single();

          Optional<ChainHead> head =
              jdbc.sql(
                      "SELECT hash, sequence FROM fiscal_record WHERE chain_id = ? ORDER BY sequence DESC LIMIT 1")
                  .param(chainId)
                  .query((rs, rowNum) -> new ChainHead(rs.getString("hash"), rs.getLong("sequence")))
                  .optional();

          long nextSequence = head.map(ChainHead::sequence).orElse(0L) + 1;
          NewRecord newRecord = computeNewRecord.apply(head, nextSequence);

          UUID id = UUID.randomUUID();
          jdbc.sql(
                  """
                  INSERT INTO fiscal_record
                    (id, invoice_id, regime, record_type, chain_id, sequence, hash, previous_hash, canonical_payload)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                  """)
              .params(
                  id,
                  invoiceId,
                  regime,
                  recordType,
                  chainId,
                  nextSequence,
                  newRecord.hash(),
                  head.map(ChainHead::hash).orElse(null),
                  newRecord.canonicalPayload())
              .update();
          return id;
        });
  }
}
