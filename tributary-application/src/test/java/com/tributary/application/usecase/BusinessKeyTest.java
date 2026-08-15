package com.tributary.application.usecase;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BusinessKey}: ADR-003's idempotency key, decided 2026-08-15 as {@code
 * SHA-256(issuerTaxIdentifier | saleId)} — deliberately over the caller's own {@code saleId}, not
 * over the sale's content. Two genuinely different sales with identical line items on the same
 * day must stay two different documents; a content hash would collapse them into one and refuse
 * to let the second be issued.
 */
class BusinessKeyTest {

  @Test
  @DisplayName("the same inputs always derive the same key")
  void isDeterministic() {
    String first = BusinessKey.derive("ESB12345678", "sale-001");
    String second = BusinessKey.derive("ESB12345678", "sale-001");
    assertEquals(first, second);
  }

  @Test
  @DisplayName("a different saleId derives a different key, even for the same issuer")
  void differentSaleIdDerivesDifferentKey() {
    String a = BusinessKey.derive("ESB12345678", "sale-001");
    String b = BusinessKey.derive("ESB12345678", "sale-002");
    assertNotEquals(a, b);
  }

  @Test
  @DisplayName("a different issuer derives a different key, even for the same saleId")
  void differentIssuerDerivesDifferentKey() {
    String a = BusinessKey.derive("ESB12345678", "sale-001");
    String b = BusinessKey.derive("DE123456789", "sale-001");
    assertNotEquals(a, b);
  }

  @Test
  @DisplayName("the key is a lowercase hex-encoded SHA-256 digest")
  void looksLikeALowercaseHexSha256() {
    String key = BusinessKey.derive("ESB12345678", "sale-001");
    assertAll(
        () -> assertEquals(64, key.length()),
        () -> assertTrue(key.matches("[0-9a-f]{64}"), () -> "not lowercase hex: " + key));
  }

  @Test
  @DisplayName("rejects a blank saleId rather than deriving a key from nothing")
  void rejectsBlankSaleId() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> BusinessKey.derive("ESB12345678", "  ")),
        () -> assertThrows(NullPointerException.class, () -> BusinessKey.derive("ESB12345678", null)),
        () -> assertThrows(NullPointerException.class, () -> BusinessKey.derive(null, "sale-001")));
  }
}
