package com.tributary.adapter.es;

import com.tributary.domain.Invoice;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * T-400 (canonicalization) and T-401 (SHA-256 incorporating the previous hash) for RD 1007/2023's
 * "registro de facturación de alta."
 *
 * <p>The canonical form is a fixed-order, explicitly delimited plain string — deliberately not
 * JSON. PostgreSQL's {@code JSONB} type reformats its input on storage (lesson L-017: {@code
 * '{"n":1}'} round-trips as {@code '{"n": 1}'}), and even a JSON library alone can vary key order
 * or number formatting between versions. A hand-built string with a fixed field order and
 * explicit separators has no such degree of freedom to drift on — which is the whole point of a
 * value that gets persisted once and must hash identically forever after.
 *
 * <p>Minimal field set (RF-003: "los campos mínimos del RD 1007/2023") chosen to be legally
 * meaningful without transcribing the full official schema, which this reference implementation
 * does not submit anywhere (ADR-005) and is not certified against (README, ADR-005).
 */
public final class VerifactuHasher {

  private VerifactuHasher() {}

  /**
   * @param generatedAt when this record was generated — supplied by the caller (a system clock
   *     read in the domain would make this class untestable and non-deterministic), not read from
   *     {@code Instant.now()} here
   */
  public static String canonicalizeAlta(Invoice invoice, Instant generatedAt) {
    Objects.requireNonNull(invoice, "invoice must not be null");
    Objects.requireNonNull(generatedAt, "generatedAt must not be null");

    return "ISSUER_TAX_ID=" + invoice.issuer().taxIdentifier()
        + "|BUSINESS_KEY=" + invoice.businessKey()
        + "|ISSUE_DATE=" + invoice.issueDate()
        + "|BUYER_TAX_ID=" + invoice.buyer().taxIdentifier().orElse("")
        + "|CURRENCY=" + invoice.currency().getCurrencyCode()
        + "|TAX_EXCLUSIVE_AMOUNT=" + invoice.totals().taxExclusiveAmount().amount().toPlainString()
        + "|TAX_TOTAL=" + invoice.totals().taxTotal().amount().toPlainString()
        + "|TAX_INCLUSIVE_AMOUNT=" + invoice.totals().taxInclusiveAmount().amount().toPlainString()
        + "|GENERATED_AT=" + generatedAt;
  }

  /**
   * T-402: the "registro de anulación" — a cancellation always references the alta record it
   * cancels by that record's hash (RF-004: a correction is a new record that references the
   * original, never an edit). It joins the SAME chain as any other record: {@link
   * #hash(String, Optional)} and the chain trigger (V2) don't distinguish alta from anulación —
   * only {@code fiscal_record.record_type} does, which this method doesn't set (the caller does,
   * when it calls {@code FiscalRecordPort.append}).
   */
  public static String canonicalizeAnulacion(String referencedAltaHash, String reason, Instant generatedAt) {
    Objects.requireNonNull(referencedAltaHash, "referencedAltaHash must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    Objects.requireNonNull(generatedAt, "generatedAt must not be null");

    return "REFERENCED_ALTA_HASH=" + referencedAltaHash + "|REASON=" + reason + "|GENERATED_AT=" + generatedAt;
  }

  /** SHA-256(canonicalFields + "|PREVIOUS_HASH=" + previousHash), lowercase hex. */
  public static String hash(String canonicalFields, Optional<String> previousHash) {
    Objects.requireNonNull(canonicalFields, "canonicalFields must not be null");
    Objects.requireNonNull(previousHash, "previousHash must not be null — use Optional.empty()");

    String input = canonicalFields + "|PREVIOUS_HASH=" + previousHash.orElse("");
    byte[] digest = sha256().digest(input.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 must be available on every JVM", e);
    }
  }
}
