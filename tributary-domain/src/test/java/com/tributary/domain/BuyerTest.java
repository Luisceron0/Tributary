package com.tributary.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link Buyer} models BG-7 (Buyer). The tax identifier is optional at this layer: EN 16931 only
 * requires it under conditions (reverse charge, BR-AE-01) that {@link EN16931BusinessRules}
 * enforces, not this constructor.
 */
class BuyerTest {

  @Test
  void mapsToBt44Bt48Bt55() {
    Buyer buyer = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");
    assertAll(
        () -> assertEquals("Handel GmbH", buyer.name()),
        () -> assertTrue(buyer.taxIdentifier().isPresent()),
        () -> assertEquals("DE123456789", buyer.taxIdentifier().orElseThrow()),
        () -> assertEquals("DE", buyer.countryCode()));
  }

  @Test
  void allowsAMissingTaxIdentifier() {
    Buyer buyer = Buyer.withoutTaxIdentifier("Handel GmbH", "DE");
    assertFalse(buyer.taxIdentifier().isPresent());
  }

  @Test
  void rejectsNullOptionalRatherThanTreatingItAsAbsent() {
    assertThrows(NullPointerException.class, () -> new Buyer("Handel GmbH", null, "DE"));
  }

  @Test
  void rejectsBlankNameOrCountry() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> Buyer.withoutTaxIdentifier("  ", "DE")),
        () -> assertThrows(IllegalArgumentException.class, () -> Buyer.withoutTaxIdentifier("Handel", " ")));
  }
}
