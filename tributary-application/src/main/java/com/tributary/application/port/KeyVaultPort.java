package com.tributary.application.port;

import java.util.UUID;

/**
 * ADR-004 / T-600: one AES-256 key per PII subject. This is what makes crypto-shredding (RF-007)
 * possible — the subject's PII stays encrypted at rest under a key only this port can produce or
 * destroy, so destroying the key (not the PII rows themselves, which would break the fiscal
 * record's own immutability) is what "suppression" means.
 *
 * <p>Deliberately minimal: no rotation, no listing, no export. A key is created once, used to
 * encrypt/decrypt on demand, and eventually destroyed — there is no legitimate reason for this
 * port to expose more surface than that, and every extra operation would be one more way a raw
 * key could leave this boundary.
 */
public interface KeyVaultPort {

  /**
   * Returns {@code subjectId}'s key, generating and persisting a new one on first use. Idempotent
   * after that: repeated calls for the same subject return the SAME key, never a fresh one — a
   * key that changed silently on every call would make previously-encrypted fields unreadable
   * without ever going through {@link #destroyKey}, which is exactly the "loss equals
   * unrequested suppression" risk A-004 names.
   */
  byte[] getOrCreateKey(UUID subjectId);

  /**
   * Destroys {@code subjectId}'s key. Idempotent: destroying an already-destroyed (or never
   * created) key succeeds without error — RF-007's own alternative flow ("clave ya destruida →
   * operación idempotente, 200 con estado ya alcanzado").
   */
  void destroyKey(UUID subjectId);

  /** Whether {@code subjectId} currently has a live key — never true again after {@link #destroyKey}. */
  boolean hasKey(UUID subjectId);
}
