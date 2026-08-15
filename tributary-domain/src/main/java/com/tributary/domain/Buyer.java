package com.tributary.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * The buyer (BG-7).
 *
 * <p>{@code taxIdentifier} is optional at this layer: EN 16931 only makes it mandatory under a
 * specific condition — reverse charge, BR-AE-01 — and that is a business rule, enforced by
 * {@code EN16931BusinessRules}, not a structural one enforced here.
 *
 * @param name BT-44, Buyer name
 * @param taxIdentifier BT-48, Buyer VAT identifier
 * @param countryCode BT-55, Buyer country code (ISO 3166-1 alpha-2)
 */
public record Buyer(String name, Optional<String> taxIdentifier, String countryCode) {

  public Buyer {
    name = Preconditions.requireNonBlank(name, "name");
    Objects.requireNonNull(taxIdentifier, "taxIdentifier must not be null — use Optional.empty()");
    countryCode = Preconditions.requireNonBlank(countryCode, "countryCode");
  }

  public static Buyer withTaxIdentifier(String name, String taxIdentifier, String countryCode) {
    return new Buyer(name, Optional.of(taxIdentifier), countryCode);
  }

  public static Buyer withoutTaxIdentifier(String name, String countryCode) {
    return new Buyer(name, Optional.empty(), countryCode);
  }
}
