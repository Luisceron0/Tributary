package com.tributary.domain;

/**
 * The seller (BG-4). Tributary models a single issuer — no multi-tenancy (SRS 3).
 *
 * @param name BT-27, Seller name
 * @param taxIdentifier BT-31, Seller VAT identifier
 * @param countryCode BT-40, Seller country code (ISO 3166-1 alpha-2)
 */
public record Issuer(String name, String taxIdentifier, String countryCode) {

  public Issuer {
    name = Preconditions.requireNonBlank(name, "name");
    taxIdentifier = Preconditions.requireNonBlank(taxIdentifier, "taxIdentifier");
    countryCode = Preconditions.requireNonBlank(countryCode, "countryCode");
  }
}
