package com.tributary.application.port;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Appends a row to a hash-chained fiscal record (SRS 6.4's {@code FiscalRecord} entity; RF-003).
 * The real, PostgreSQL-backed implementation lives in {@code tributary-persistence} (T-205: it
 * serializes concurrent appends to the same chain with an advisory lock) — this port is what lets
 * {@code tributary-adapter-es-verifactu} depend on that capability without depending on the
 * persistence module directly (SRS 6.2: adapters depend on {@code application}, not on each
 * other's siblings).
 *
 * <p>What hash a new record gets is deliberately not this port's decision: the caller receives the
 * chain's current tail (if any) and computes the new record's hash and canonical payload from it.
 */
public interface FiscalRecordPort {

  /** The chain's current last record — what a new record's previous_hash and sequence derive from. */
  record ChainHead(String hash, long sequence) {}

  /** What the caller wants the new row to contain, computed from the {@link ChainHead}. */
  record NewRecord(String hash, String canonicalPayload) {}

  /**
   * @param computeNewRecord given the current chain head (empty for a brand-new chain) and the
   *     sequence the new record will occupy, returns the hash and canonicalized payload to insert
   * @return the id of the inserted row
   */
  UUID append(
      UUID invoiceId,
      String regime,
      String recordType,
      UUID chainId,
      BiFunction<Optional<ChainHead>, Long, NewRecord> computeNewRecord);
}
