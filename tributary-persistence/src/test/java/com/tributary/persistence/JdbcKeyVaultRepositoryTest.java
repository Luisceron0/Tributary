package com.tributary.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-600: the real, PostgreSQL-backed {@code KeyVaultPort} — subject_key (V6), ADR-004. */
class JdbcKeyVaultRepositoryTest extends AbstractPostgresTest {

  private JdbcKeyVaultRepository repository() {
    return new JdbcKeyVaultRepository(dataSource);
  }

  private UUID newBuyerId() {
    // subject_key.subject_id REFERENCES buyer(id) — a real row is needed for the FK, not just a
    // random UUID that happens to look right.
    UUID id = UUID.randomUUID();
    dataSourceInsertBuyer(id);
    return id;
  }

  private void dataSourceInsertBuyer(UUID id) {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "INSERT INTO buyer (id, name_encrypted, country_code) VALUES (?, ?, 'DE')")) {
      statement.setObject(1, id);
      statement.setBytes(2, new byte[0]); // placeholder — this test never decrypts it
      statement.executeUpdate();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  @DisplayName("a key generated for a new subject is a real 256-bit (32-byte) key")
  void generatesA256BitKey() {
    UUID subjectId = newBuyerId();
    JdbcKeyVaultRepository repo = repository();

    byte[] key = repo.getOrCreateKey(subjectId);

    assertEquals(32, key.length, "AES-256 needs a 32-byte key");
  }

  @Test
  @DisplayName("getOrCreateKey is idempotent: the same subject always gets back the SAME key")
  void idempotentAcrossRepeatedCalls() {
    UUID subjectId = newBuyerId();
    JdbcKeyVaultRepository repo = repository();

    byte[] first = repo.getOrCreateKey(subjectId);
    byte[] second = repo.getOrCreateKey(subjectId);

    assertArrayEquals(first, second);
  }

  @Test
  @DisplayName("two different subjects get two different keys")
  void differentSubjectsGetDifferentKeys() {
    JdbcKeyVaultRepository repo = repository();
    byte[] keyA = repo.getOrCreateKey(newBuyerId());
    byte[] keyB = repo.getOrCreateKey(newBuyerId());

    assertFalse(java.util.Arrays.equals(keyA, keyB), "two subjects must never share a key");
  }

  @Test
  @DisplayName("hasKey reflects reality before and after destruction")
  void hasKeyReflectsRealState() {
    UUID subjectId = newBuyerId();
    JdbcKeyVaultRepository repo = repository();

    assertFalse(repo.hasKey(subjectId), "no key exists yet — nothing has requested one");
    repo.getOrCreateKey(subjectId);
    assertTrue(repo.hasKey(subjectId));
    repo.destroyKey(subjectId);
    assertFalse(repo.hasKey(subjectId));
  }

  @Test
  @DisplayName("RF-007: destroying a key and creating a new one for the same subject never revives the old key")
  void destroyThenRecreateNeverRevivesTheOldKey() {
    UUID subjectId = newBuyerId();
    JdbcKeyVaultRepository repo = repository();

    byte[] original = repo.getOrCreateKey(subjectId);
    repo.destroyKey(subjectId);
    byte[] afterRecreate = repo.getOrCreateKey(subjectId);

    assertNotEquals(
        java.util.Base64.getEncoder().encodeToString(original),
        java.util.Base64.getEncoder().encodeToString(afterRecreate),
        "a subject who lost their key must never accidentally get it back");
  }

  @Test
  @DisplayName("destroying a key that never existed is idempotent, not an error — RF-007's own alternative flow")
  void destroyingANonexistentKeyIsIdempotent() {
    UUID subjectId = newBuyerId();
    JdbcKeyVaultRepository repo = repository();

    repo.destroyKey(subjectId); // must not throw
    repo.destroyKey(subjectId); // twice — still must not throw

    assertFalse(repo.hasKey(subjectId));
  }
}
