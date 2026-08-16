package com.tributary.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
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
    assertThrows(
        NullPointerException.class,
        () -> new Buyer("Handel GmbH", null, "DE", Optional.empty(), Optional.empty(), Optional.empty()));
  }

  @Test
  void rejectsBlankNameOrCountry() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> Buyer.withoutTaxIdentifier("  ", "DE")),
        () -> assertThrows(IllegalArgumentException.class, () -> Buyer.withoutTaxIdentifier("Handel", " ")));
  }

  @Test
  void bothFactoriesLeavePersonalDataAbsentByDefault() {
    Buyer withTaxId = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");
    Buyer withoutTaxId = Buyer.withoutTaxIdentifier("Handel GmbH", "DE");
    assertAll(
        () -> assertFalse(withTaxId.address().isPresent()),
        () -> assertFalse(withTaxId.email().isPresent()),
        () -> assertFalse(withTaxId.phone().isPresent()),
        () -> assertFalse(withoutTaxId.address().isPresent()),
        () -> assertFalse(withoutTaxId.email().isPresent()),
        () -> assertFalse(withoutTaxId.phone().isPresent()));
  }

  @Test
  void withPersonalDataAttachesPiiWithoutDisturbingEverythingElse() {
    Buyer base = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");

    Buyer withPii =
        base.withPersonalData(
            Optional.of("Hauptstraße 1, 10115 Berlin"), Optional.of("buyer@handel.de"), Optional.of("+49 30 1234567"));

    assertAll(
        () -> assertEquals("Handel GmbH", withPii.name()),
        () -> assertEquals("DE123456789", withPii.taxIdentifier().orElseThrow()),
        () -> assertEquals("DE", withPii.countryCode()),
        () -> assertEquals("Hauptstraße 1, 10115 Berlin", withPii.address().orElseThrow()),
        () -> assertEquals("buyer@handel.de", withPii.email().orElseThrow()),
        () -> assertEquals("+49 30 1234567", withPii.phone().orElseThrow()));
  }
}
