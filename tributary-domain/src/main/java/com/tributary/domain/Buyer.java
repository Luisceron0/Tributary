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
 * <p>{@code address}/{@code email}/{@code phone} are RF-007's own PII scope (ADR-004, T-601) —
 * the fields AES-256-GCM encryption is required for — not a full EN 16931 mapping: {@code
 * address} simplifies BG-8's multi-line structure to one free-text field (the same scope call
 * the DE adapter already made for its own placeholder addresses, T-505), and EN 16931 has no
 * Buyer-side equivalent of BG-6's Seller contact group at all, so {@code email}/{@code phone}
 * have no BT to cite. All three are optional and absent by default; {@link #withPersonalData}
 * attaches them without disturbing any existing call site's factory method.
 *
 * @param name BT-44, Buyer name
 * @param taxIdentifier BT-48, Buyer VAT identifier
 * @param countryCode BT-55, Buyer country code (ISO 3166-1 alpha-2)
 * @param address RF-007's PII scope — see class note
 * @param email RF-007's PII scope — see class note
 * @param phone RF-007's PII scope — see class note
 */
public record Buyer(
    String name,
    Optional<String> taxIdentifier,
    String countryCode,
    Optional<String> address,
    Optional<String> email,
    Optional<String> phone) {

  public Buyer {
    name = Preconditions.requireNonBlank(name, "name");
    Objects.requireNonNull(taxIdentifier, "taxIdentifier must not be null — use Optional.empty()");
    countryCode = Preconditions.requireNonBlank(countryCode, "countryCode");
    Objects.requireNonNull(address, "address must not be null — use Optional.empty()");
    Objects.requireNonNull(email, "email must not be null — use Optional.empty()");
    Objects.requireNonNull(phone, "phone must not be null — use Optional.empty()");
  }

  public static Buyer withTaxIdentifier(String name, String taxIdentifier, String countryCode) {
    return new Buyer(
        name, Optional.of(taxIdentifier), countryCode, Optional.empty(), Optional.empty(), Optional.empty());
  }

  public static Buyer withoutTaxIdentifier(String name, String countryCode) {
    return new Buyer(name, Optional.empty(), countryCode, Optional.empty(), Optional.empty(), Optional.empty());
  }

  /** Returns a new {@code Buyer} with PII attached — everything else copied from this one. */
  public Buyer withPersonalData(Optional<String> address, Optional<String> email, Optional<String> phone) {
    return new Buyer(name, taxIdentifier, countryCode, address, email, phone);
  }
}
