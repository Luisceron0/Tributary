package com.tributary.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link Issuer} models BG-4 (Seller). */
class IssuerTest {

  @Test
  void mapsToBt27Bt31Bt40() {
    Issuer issuer = new Issuer("Acme Exports SL", "ESB12345678", "ES");
    assertAll(
        () -> assertEquals("Acme Exports SL", issuer.name()),
        () -> assertEquals("ESB12345678", issuer.taxIdentifier()),
        () -> assertEquals("ES", issuer.countryCode()));
  }

  @Test
  void stripsSurroundingWhitespace() {
    Issuer issuer = new Issuer("  Acme  ", " ESB12345678 ", " ES ");
    assertAll(
        () -> assertEquals("Acme", issuer.name()),
        () -> assertEquals("ESB12345678", issuer.taxIdentifier()),
        () -> assertEquals("ES", issuer.countryCode()));
  }

  @Test
  void rejectsNullOrBlankFields() {
    assertAll(
        () -> assertThrows(NullPointerException.class, () -> new Issuer(null, "ESB12345678", "ES")),
        () -> assertThrows(IllegalArgumentException.class, () -> new Issuer("  ", "ESB12345678", "ES")),
        () -> assertThrows(IllegalArgumentException.class, () -> new Issuer("Acme", "", "ES")),
        () -> assertThrows(IllegalArgumentException.class, () -> new Issuer("Acme", "ESB12345678", "")));
  }
}
