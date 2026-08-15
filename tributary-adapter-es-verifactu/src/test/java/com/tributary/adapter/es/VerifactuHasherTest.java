package com.tributary.adapter.es;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tributary.domain.Buyer;
import com.tributary.domain.Invoice;
import com.tributary.domain.InvoiceLine;
import com.tributary.domain.Issuer;
import com.tributary.domain.Money;
import com.tributary.domain.Quantity;
import com.tributary.domain.TaxRate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-400 (canonicalization) and T-401 (SHA-256 incorporating the previous hash).
 *
 * <p>The canonical form is plain, explicitly delimited text — never JSON. A JSONB round-trip in
 * PostgreSQL reformats its input (lesson L-017: {@code '{"n":1}'} comes back as {@code '{"n":
 * 1}'}) and even a JSON library alone can vary key order or number formatting between versions.
 * A fixed field order with explicit separators has no such degrees of freedom to drift on.
 */
class VerifactuHasherTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Issuer ISSUER = new Issuer("Acme Exports SL", "ESB12345678", "ES");
  private static final Buyer BUYER = Buyer.withTaxIdentifier("Handel GmbH", "DE123456789", "DE");
  private static final Instant GENERATED_AT = Instant.parse("2026-08-15T10:00:00Z");

  private static Invoice sampleInvoice() {
    InvoiceLine line =
        InvoiceLine.standardRate(
            "1", "Widgets", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            TaxRate.ofPercent("19"));
    return Invoice.draft(
        "biz-key-1", ISSUER, BUYER, EUR, LocalDate.of(2026, 8, 15), List.of(line), Money.zero(EUR));
  }

  @Test
  @DisplayName("T-400: the canonical form is an exact, literal string — not just 'equals itself'")
  void canonicalizationProducesTheExactExpectedString() {
    String canonical = VerifactuHasher.canonicalizeAlta(sampleInvoice(), GENERATED_AT);

    // Pinned literal, not a round-trip check (lesson L-012/L-017: a property that only compares
    // a value against itself can't catch a wrong-but-consistent format).
    assertEquals(
        "ISSUER_TAX_ID=ESB12345678|BUSINESS_KEY=biz-key-1|ISSUE_DATE=2026-08-15|"
            + "BUYER_TAX_ID=DE123456789|CURRENCY=EUR|TAX_EXCLUSIVE_AMOUNT=100.00|"
            + "TAX_TOTAL=19.00|TAX_INCLUSIVE_AMOUNT=119.00|GENERATED_AT=2026-08-15T10:00:00Z",
        canonical);
  }

  @Test
  @DisplayName("T-400: a missing buyer tax identifier canonicalizes to an explicit empty field, not an omission")
  void missingBuyerTaxIdentifierIsExplicit() {
    Buyer buyerWithoutTaxId = Buyer.withoutTaxIdentifier("Handel GmbH", "DE");
    InvoiceLine line =
        InvoiceLine.reverseCharge(
            "1", "Consulting", Quantity.of("1", "C62"), Money.of("100.00", EUR), Money.zero(EUR),
            "Intra-Community supply — Art. 138 VAT Directive 2006/112/EC");
    Invoice invoice =
        Invoice.draft(
            "biz-key-2", ISSUER, buyerWithoutTaxId, EUR, LocalDate.of(2026, 8, 15), List.of(line),
            Money.zero(EUR));

    String canonical = VerifactuHasher.canonicalizeAlta(invoice, GENERATED_AT);

    assertTrue(canonical.contains("BUYER_TAX_ID=|"), () -> "expected an explicit empty field: " + canonical);
  }

  @Test
  @DisplayName("T-401: hashing the same canonical form and previous hash is deterministic")
  void hashingIsDeterministic() {
    String canonical = VerifactuHasher.canonicalizeAlta(sampleInvoice(), GENERATED_AT);
    String first = VerifactuHasher.hash(canonical, Optional.empty());
    String second = VerifactuHasher.hash(canonical, Optional.empty());
    assertEquals(first, second);
  }

  @Test
  @DisplayName("T-401: hash is a lowercase hex SHA-256 digest, pinned to an independently computed literal value")
  void hashMatchesAKnownLiteralValue() {
    // Independently computed: `printf 'GENESIS|PREVIOUS_HASH=' | sha256sum`. A property that only
    // asserts "64 hex chars" or "recomputing gives the same value" cannot tell a correct SHA-256
    // implementation from a consistently wrong one (lesson L-012) — this pins the actual algorithm
    // against a value this test did not derive from the code under test.
    String result = VerifactuHasher.hash("GENESIS", Optional.empty());
    assertEquals("5fae5f3bda1dc75fc6161bdcfc58761497f4ec774294df805d82ea9d628e9df5", result);
  }

  @Test
  @DisplayName("T-401: incorporates the previous hash — same fields, different predecessor, different hash")
  void incorporatesThePreviousHash() {
    String canonical = VerifactuHasher.canonicalizeAlta(sampleInvoice(), GENERATED_AT);
    String hashA = VerifactuHasher.hash(canonical, Optional.of("a".repeat(64)));
    String hashB = VerifactuHasher.hash(canonical, Optional.of("b".repeat(64)));
    assertNotEquals(hashA, hashB);
  }

  @Test
  @DisplayName("T-401: a genesis hash (no predecessor) differs from a chained hash of the same fields")
  void genesisHashDiffersFromChainedHash() {
    String canonical = VerifactuHasher.canonicalizeAlta(sampleInvoice(), GENERATED_AT);
    String genesisHash = VerifactuHasher.hash(canonical, Optional.empty());
    String chainedHash = VerifactuHasher.hash(canonical, Optional.of("a".repeat(64)));
    assertNotEquals(genesisHash, chainedHash);
  }

  @Test
  @DisplayName("T-401: output shape is always a lowercase 64-character hex string")
  void outputIsLowercaseHexSixtyFourChars() {
    String hash = VerifactuHasher.hash("anything", Optional.empty());
    assertAll(
        () -> assertEquals(64, hash.length()),
        () -> assertTrue(hash.matches("[0-9a-f]{64}")));
  }

  @Test
  @DisplayName("T-402: cancellation canonicalization produces the exact expected string, referencing the alta record")
  void cancellationCanonicalizationProducesTheExactExpectedString() {
    String altaHash = "a".repeat(64);
    String canonical =
        VerifactuHasher.canonicalizeAnulacion(altaHash, "duplicate line item", GENERATED_AT);

    assertEquals(
        "REFERENCED_ALTA_HASH=" + "a".repeat(64) + "|REASON=duplicate line item|GENERATED_AT=2026-08-15T10:00:00Z",
        canonical);
  }

  @Test
  @DisplayName("T-402: a cancellation of a different alta record canonicalizes differently")
  void cancellationCanonicalizationDependsOnTheReferencedRecord() {
    String canonicalA =
        VerifactuHasher.canonicalizeAnulacion("a".repeat(64), "reason", GENERATED_AT);
    String canonicalB =
        VerifactuHasher.canonicalizeAnulacion("b".repeat(64), "reason", GENERATED_AT);
    assertNotEquals(canonicalA, canonicalB);
  }

  @Test
  @DisplayName("RF-003: reproducible — recomputing from the same persisted inputs gives the same value")
  void reproducibleFromPersistedData() {
    Invoice invoice = sampleInvoice();
    String canonicalAtWriteTime = VerifactuHasher.canonicalizeAlta(invoice, GENERATED_AT);
    String hashAtWriteTime = VerifactuHasher.hash(canonicalAtWriteTime, Optional.of("seed".repeat(16).substring(0, 64)));

    // Simulate reading the SAME canonical text back from storage (TEXT column, byte-exact) and
    // recomputing.
    String canonicalReadBack = canonicalAtWriteTime;
    String recomputed = VerifactuHasher.hash(canonicalReadBack, Optional.of("seed".repeat(16).substring(0, 64)));

    assertEquals(hashAtWriteTime, recomputed);
  }
}
