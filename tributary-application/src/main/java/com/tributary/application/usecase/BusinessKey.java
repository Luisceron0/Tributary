package com.tributary.application.usecase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * ADR-003's idempotency key: {@code SHA-256(issuerTaxIdentifier | saleId)}, hex-encoded.
 *
 * <p>Decided 2026-08-15 (RF-001 names "derived deterministically from the sale" without saying
 * from which fields): over the caller's own {@code saleId}, deliberately NOT over the sale's
 * content. A content hash would collapse two genuinely different sales with identical line items
 * on the same day into one document, and the system would refuse to let the second be issued with
 * no way to force it — the identity of a sale is declared by the caller, not inferred from its
 * shape. {@code saleId} became a required field on {@link RegisterInvoiceRequest} as a direct
 * consequence.
 */
final class BusinessKey {

  private BusinessKey() {}

  static String derive(String issuerTaxIdentifier, String saleId) {
    Objects.requireNonNull(issuerTaxIdentifier, "issuerTaxIdentifier must not be null");
    Objects.requireNonNull(saleId, "saleId must not be null");
    if (saleId.isBlank()) {
      throw new IllegalArgumentException("saleId must not be blank");
    }
    byte[] hash = sha256().digest((issuerTaxIdentifier + "|" + saleId).getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hash);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is a mandatory algorithm under the Java Cryptography Architecture standard names
      // (JDK spec) — every compliant JVM supports it. This is unreachable, not a real failure mode.
      throw new IllegalStateException("SHA-256 must be available on every JVM", e);
    }
  }
}
