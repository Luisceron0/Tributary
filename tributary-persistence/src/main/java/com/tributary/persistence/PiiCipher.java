package com.tributary.persistence;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * T-601 / ADR-004: AES-256-GCM for buyer PII (name/address/email/phone), with a fresh random IV
 * on every call — never a fixed or derived one, which would make every encryption of the same
 * plaintext under the same key produce identical ciphertext, a real confidentiality leak GCM's
 * design otherwise prevents. The IV (12 bytes, GCM's standard size) is prepended to the returned
 * blob; a 16-byte authentication tag is appended by {@link Cipher} itself as part of GCM's output
 * — nothing here manages the tag separately, so there is nothing to get wrong about it.
 *
 * <p>Deliberately stateless and package-private: this is a mechanism {@code JdbcInvoiceRepository}
 * uses, not a port — {@code KeyVaultPort} is the port (T-600); how a key gets used to encrypt one
 * field is an implementation detail of this module, not something {@code tributary-application}
 * needs to know exists.
 */
final class PiiCipher {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final String KEY_ALGORITHM = "AES";
  private static final int KEY_LENGTH_BYTES = 32; // AES-256
  private static final int IV_LENGTH_BYTES = 12; // GCM standard
  private static final int TAG_LENGTH_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private PiiCipher() {}

  /** Encrypts {@code plaintext} under {@code key}, returning {@code IV || ciphertext || tag}. */
  static byte[] encrypt(byte[] key, String plaintext) {
    requireValidKeyLength(key);
    byte[] iv = new byte[IV_LENGTH_BYTES];
    RANDOM.nextBytes(iv);

    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

      byte[] blob = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, blob, 0, iv.length);
      System.arraycopy(ciphertext, 0, blob, iv.length, ciphertext.length);
      return blob;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-256-GCM encryption failed", e);
    }
  }

  /**
   * Decrypts a blob produced by {@link #encrypt}. Declares {@link
   * javax.crypto.AEADBadTagException} explicitly (wrong key or a tampered ciphertext) rather than
   * wrapping it — callers are expected to distinguish "this subject's key is gone" (RF-007's own
   * point) from a genuinely broken environment, and a wrapper would just make them unwrap it again.
   */
  static String decrypt(byte[] key, byte[] blob) throws javax.crypto.AEADBadTagException {
    requireValidKeyLength(key);
    byte[] iv = Arrays.copyOfRange(blob, 0, IV_LENGTH_BYTES);
    byte[] ciphertext = Arrays.copyOfRange(blob, IV_LENGTH_BYTES, blob.length);

    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(
          Cipher.DECRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] plaintext = cipher.doFinal(ciphertext);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (javax.crypto.AEADBadTagException e) {
      throw e;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-256-GCM decryption failed", e);
    }
  }

  private static void requireValidKeyLength(byte[] key) {
    if (key.length != KEY_LENGTH_BYTES) {
      throw new IllegalArgumentException(
          "AES-256 requires a " + KEY_LENGTH_BYTES + "-byte key, got " + key.length + " bytes");
    }
  }
}
