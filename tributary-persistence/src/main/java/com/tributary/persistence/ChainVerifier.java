package com.tributary.persistence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * RF-006: walks a chain, recomputing every record's hash and comparing it against the stored one.
 *
 * <p>The actual hash formula — canonicalization of fields plus the previous hash (T-400/T-401) —
 * is the ES adapter's job, phase 4, and does not exist yet. This class does not hardcode one: it
 * takes a {@code (previousHash, canonicalPayloadJson) -> hash} function as a dependency, so phase
 * 2 can prove the VERIFICATION MECHANICS (walk in order, recompute, compare, report the first
 * discrepancy with its predecessor and both hashes, keep going to size the damage) independently
 * of an algorithm that phase 4 hasn't defined yet. Whatever T-401 lands on gets passed in here
 * unchanged — this class does not need to change when it does.
 *
 * <p>Connects with a read-only role (T-206: {@code tributary_verifier}, granted by V5) — enforced
 * by the {@link DataSource} the caller supplies, not by this class, which has no way to tell what
 * privileges its connection carries. See {@code ApplicationRoleGrantsTest} for that guarantee.
 */
public final class ChainVerifier {

  public sealed interface VerificationResult {

    record Intact(int recordsVerified) implements VerificationResult {}

    /**
     * @param brokenRecordId the first record whose recomputed hash did not match
     * @param predecessorId that record's predecessor in the chain (null if it was the genesis record)
     * @param storedHash the hash actually persisted for {@code brokenRecordId}
     * @param recomputedHash what the hash function produced from the record's own data
     * @param totalMismatches how many records in the whole chain failed to match — RF-006:
     *     "the traversal continues to quantify the scope," not just to find the first break
     * @param recordsVerified total records walked
     */
    record Broken(
        UUID brokenRecordId,
        UUID predecessorId,
        String storedHash,
        String recomputedHash,
        int totalMismatches,
        int recordsVerified)
        implements VerificationResult {}
  }

  private record Row(UUID id, String hash, String canonicalPayload) {}

  private final JdbcClient jdbc;
  private final BiFunction<Optional<String>, String, String> hashFunction;

  public ChainVerifier(DataSource dataSource, BiFunction<Optional<String>, String, String> hashFunction) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    this.hashFunction = Objects.requireNonNull(hashFunction, "hashFunction must not be null");
  }

  public VerificationResult verify(UUID chainId) {
    Objects.requireNonNull(chainId, "chainId must not be null");

    List<Row> rows =
        jdbc.sql(
                "SELECT id, hash, canonical_payload FROM fiscal_record WHERE chain_id = ? ORDER BY sequence")
            .param(chainId)
            .query((rs, rowNum) -> new Row(
                (UUID) rs.getObject("id"), rs.getString("hash"), rs.getString("canonical_payload")))
            .list();

    UUID firstBrokenId = null;
    UUID firstBrokenPredecessorId = null;
    String firstBrokenStoredHash = null;
    String firstBrokenRecomputedHash = null;
    int mismatches = 0;

    String previousHash = null;
    UUID previousId = null;

    for (Row row : rows) {
      String recomputed = hashFunction.apply(Optional.ofNullable(previousHash), row.canonicalPayload());
      if (!recomputed.equals(row.hash())) {
        mismatches++;
        if (firstBrokenId == null) {
          firstBrokenId = row.id();
          firstBrokenPredecessorId = previousId;
          firstBrokenStoredHash = row.hash();
          firstBrokenRecomputedHash = recomputed;
        }
      }
      // Chain-walk against what is actually stored, not against the recomputed value: a
      // discrepancy at this row must not cascade into false positives for every row after it.
      previousHash = row.hash();
      previousId = row.id();
    }

    if (mismatches == 0) {
      return new VerificationResult.Intact(rows.size());
    }
    return new VerificationResult.Broken(
        firstBrokenId, firstBrokenPredecessorId, firstBrokenStoredHash, firstBrokenRecomputedHash,
        mismatches, rows.size());
  }
}
