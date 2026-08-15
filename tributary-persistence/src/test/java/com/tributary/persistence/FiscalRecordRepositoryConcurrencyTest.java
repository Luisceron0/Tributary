package com.tributary.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * T-205: 20 threads appending to the same chain must produce a sequence without gaps or
 * duplicates. Hashes here are random 64-hex-char strings, not real SHA-256 — canonicalization
 * (T-400/T-401) is phase 4's job; this proves the LOCKING is race-free, independent of what the
 * hash function actually computes.
 */
class FiscalRecordRepositoryConcurrencyTest extends AbstractPostgresTest {

  private static final SecureRandom RANDOM = new SecureRandom();

  private FiscalRecordRepository repository;
  private JdbcClient jdbc;
  private UUID invoiceId;

  @BeforeEach
  void setUp() {
    jdbc = JdbcClient.create(dataSource);
    repository =
        new FiscalRecordRepository(dataSource, new JdbcTransactionManager(dataSource));
    UUID issuerId = TestFixtures.insertIssuer(dataSource);
    UUID buyerId = TestFixtures.insertBuyer(dataSource);
    invoiceId = TestFixtures.insertDraftInvoice(dataSource, issuerId, buyerId, "biz-" + UUID.randomUUID());
  }

  private static String randomHash() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  @Test
  @DisplayName("20 concurrent appends to the same chain produce a gapless, duplicate-free sequence")
  void twentyThreadsProduceAGaplessSequence() throws InterruptedException {
    UUID chainId = UUID.randomUUID();
    int threadCount = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    CountDownLatch readyLatch = new CountDownLatch(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    List<Exception> failures = java.util.Collections.synchronizedList(new ArrayList<>());

    for (int i = 0; i < threadCount; i++) {
      pool.submit(
          () -> {
            readyLatch.countDown();
            try {
              startLatch.await(10, TimeUnit.SECONDS);
              repository.append(
                  invoiceId,
                  "ES",
                  "ISSUANCE",
                  chainId,
                  (head, sequence) -> new FiscalRecordRepository.NewRecord(randomHash(), "{}"));
            } catch (Exception e) {
              failures.add(e);
            } finally {
              doneLatch.countDown();
            }
          });
    }

    readyLatch.await(10, TimeUnit.SECONDS); // every thread past setup, maximising real contention
    startLatch.countDown();
    boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
    pool.shutdown();

    assertTrue(completed, "all threads must finish within the timeout");
    assertTrue(failures.isEmpty(), () -> "no thread should fail: " + failures);

    List<Long> sequences =
        jdbc.sql("SELECT sequence FROM fiscal_record WHERE chain_id = ? ORDER BY sequence")
            .param(chainId)
            .query(Long.class)
            .list();

    List<Long> expected = java.util.stream.LongStream.rangeClosed(1, threadCount).boxed().toList();
    assertEquals(expected, sequences, "sequence must be exactly 1..20, no gaps, no duplicates");

    // Every non-genesis record's previous_hash must resolve to exactly one row in this chain —
    // an unbroken walk from the tail back to the single genesis record. Map.entry() rejects null
    // values, and the genesis record's previous_hash IS null by design, so this is built with a
    // plain mutable map (which allows null values) instead of Collectors.toMap.
    Map<String, String> hashToPreviousHash = new java.util.HashMap<>();
    jdbc.sql("SELECT hash, previous_hash FROM fiscal_record WHERE chain_id = ?")
        .param(chainId)
        .query((rs, rowNum) -> hashToPreviousHash.put(rs.getString("hash"), rs.getString("previous_hash")))
        .list();

    long genesisCount = hashToPreviousHash.values().stream().filter(java.util.Objects::isNull).count();
    assertEquals(1, genesisCount, "exactly one genesis record");

    long resolvableLinks =
        hashToPreviousHash.values().stream()
            .filter(java.util.Objects::nonNull)
            .filter(hashToPreviousHash::containsKey)
            .count();
    assertEquals(threadCount - 1, resolvableLinks, "every non-genesis record's predecessor must exist in this chain");
  }
}
