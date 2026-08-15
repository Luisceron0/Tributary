package com.tributary.domain;

/**
 * EN 16931 VAT category code (BT-118 in a VAT breakdown group, BT-151 on an invoice line).
 *
 * <p>Only the two categories the reference cases RC-1/RC-2/RC-3 (see {@code tasks/todo.md})
 * require are modelled. EN 16931 defines more (zero-rated, exempt, export outside the EU and
 * others); adding one is adding a case here and to {@code EN16931BusinessRules}, not a redesign.
 */
public enum TaxCategory {

  /** Standard rate (code {@code "S"}). RC-1 and RC-2. */
  STANDARD("S"),

  /** VAT reverse charge (code {@code "AE"}). RC-3: the buyer accounts for the VAT, not the seller. */
  REVERSE_CHARGE("AE");

  private final String code;

  TaxCategory(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
