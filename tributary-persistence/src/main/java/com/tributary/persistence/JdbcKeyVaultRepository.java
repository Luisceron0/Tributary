package com.tributary.persistence;

import com.tributary.application.port.KeyVaultPort;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T-600: the real {@link KeyVaultPort} — {@code subject_key} (V6), ADR-004. {@link #destroyKey}
 * is a real {@code DELETE}, not a soft-delete flag: a flag would leave the key material sitting
 * in the database, one bug away from being read again, which is exactly the "loss/theft of a
 * per-subject key" risk A-004 already treats as high-severity in the opposite direction — this
 * removes the material outright.
 */
public final class JdbcKeyVaultRepository implements KeyVaultPort {

  private static final int KEY_LENGTH_BYTES = 32; // AES-256
  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcClient jdbc;

  public JdbcKeyVaultRepository(DataSource dataSource) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
  }

  @Override
  public byte[] getOrCreateKey(UUID subjectId) {
    Objects.requireNonNull(subjectId, "subjectId must not be null");

    Optional<byte[]> existing =
        jdbc.sql("SELECT key_material FROM subject_key WHERE subject_id = ?")
            .param(subjectId)
            .query(byte[].class)
            .optional();
    if (existing.isPresent()) {
      return existing.orElseThrow();
    }

    byte[] key = new byte[KEY_LENGTH_BYTES];
    RANDOM.nextBytes(key);
    // A concurrent first-caller racing this same subject would violate the PRIMARY KEY — in that
    // rare case, defer to whichever row actually landed rather than erroring, so two racing
    // "create the key" calls still converge on ONE consistent key (this port's own idempotence
    // guarantee), not a duplicate-key exception surfacing from a plain INSERT.
    jdbc.sql("INSERT INTO subject_key (subject_id, key_material) VALUES (?, ?) ON CONFLICT (subject_id) DO NOTHING")
        .params(subjectId, key)
        .update();

    return jdbc.sql("SELECT key_material FROM subject_key WHERE subject_id = ?")
        .param(subjectId)
        .query(byte[].class)
        .single();
  }

  @Override
  public void destroyKey(UUID subjectId) {
    Objects.requireNonNull(subjectId, "subjectId must not be null");
    jdbc.sql("DELETE FROM subject_key WHERE subject_id = ?").param(subjectId).update();
  }

  @Override
  public boolean hasKey(UUID subjectId) {
    Objects.requireNonNull(subjectId, "subjectId must not be null");
    return jdbc.sql("SELECT count(*) FROM subject_key WHERE subject_id = ?")
        .param(subjectId)
        .query(Long.class)
        .single()
        > 0;
  }
}
