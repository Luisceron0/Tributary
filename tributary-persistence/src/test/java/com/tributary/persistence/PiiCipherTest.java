package com.tributary.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.AEADBadTagException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T-601: AES-256-GCM field encryption, ADR-004's "IV aleatorio por operación" made literal and testable. */
class PiiCipherTest {

  private static final SecureRandom RANDOM = new SecureRandom();

  private byte[] randomKey() {
    byte[] key = new byte[32];
    RANDOM.nextBytes(key);
    return key;
  }

  @Test
  @DisplayName("a round trip returns exactly the original plaintext")
  void roundTripsExactly() throws Exception {
    byte[] key = randomKey();

    byte[] blob = PiiCipher.encrypt(key, "Handel GmbH");
    String plaintext = PiiCipher.decrypt(key, blob);

    assertEquals("Handel GmbH", plaintext);
  }

  @Test
  @DisplayName("the stored blob never contains the plaintext bytes")
  void blobNeverContainsPlaintext() {
    byte[] key = randomKey();
    String secret = "buyer@handel.de";

    byte[] blob = PiiCipher.encrypt(key, secret);

    String blobAsLatin1 = new String(blob, StandardCharsets.ISO_8859_1);
    assertFalse(blobAsLatin1.contains(secret), "ciphertext must not leak the plaintext as a literal substring");
  }

  @Test
  @DisplayName("ADR-004: encrypting the SAME plaintext under the SAME key twice produces DIFFERENT ciphertext — a random IV per operation, not a fixed one")
  void sameInputTwiceProducesDifferentCiphertext() throws Exception {
    byte[] key = randomKey();

    byte[] first = PiiCipher.encrypt(key, "Handel GmbH");
    byte[] second = PiiCipher.encrypt(key, "Handel GmbH");

    assertNotEquals(
        java.util.Base64.getEncoder().encodeToString(first),
        java.util.Base64.getEncoder().encodeToString(second),
        "a fixed IV would make every encryption of the same plaintext identical — a real confidentiality leak");
    // Both must still decrypt to the same plaintext despite differing ciphertext bytes.
    assertEquals("Handel GmbH", PiiCipher.decrypt(key, first));
    assertEquals("Handel GmbH", PiiCipher.decrypt(key, second));
  }

  @Test
  @DisplayName("RF-007: without the right key, the ciphertext is not decryptable at all — this is what makes destroying the key equivalent to suppression")
  void wrongKeyCannotDecrypt() {
    byte[] realKey = randomKey();
    byte[] wrongKey = randomKey();
    byte[] blob = PiiCipher.encrypt(realKey, "Handel GmbH");

    assertThrows(AEADBadTagException.class, () -> PiiCipher.decrypt(wrongKey, blob));
  }

  @Test
  @DisplayName("a tampered ciphertext byte is detected, not silently decrypted into garbage — GCM's authentication tag doing its job")
  void tamperedCiphertextIsDetected() {
    byte[] key = randomKey();
    byte[] blob = PiiCipher.encrypt(key, "Handel GmbH");
    blob[blob.length - 1] ^= 0x01; // flip one bit in the authentication tag

    assertThrows(AEADBadTagException.class, () -> PiiCipher.decrypt(key, blob));
  }

  @Test
  @DisplayName("a 24-byte (AES-192) key is rejected — this port is AES-256 or nothing")
  void rejectsAKeyOfTheWrongLength() {
    byte[] shortKey = new byte[24];
    RANDOM.nextBytes(shortKey);

    assertThrows(IllegalArgumentException.class, () -> PiiCipher.encrypt(shortKey, "Handel GmbH"));
  }

  @Test
  @DisplayName("Unicode plaintext (e.g. German umlauts) round-trips byte-exact")
  void unicodePlaintextRoundTrips() throws Exception {
    byte[] key = randomKey();
    String withUmlauts = "Hauptstraße 1, München";

    byte[] blob = PiiCipher.encrypt(key, withUmlauts);

    assertTrue(blob.length > 0);
    assertEquals(withUmlauts, PiiCipher.decrypt(key, blob));
  }
}
