package com.tributary.application.usecase;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-009's exact response shape for {@code GET /api/v1/records/{id}/verification} — the ONE
 * unauthenticated public endpoint in the system. Six fields, no more: no PII, no amounts, no
 * fiscal identifiers. Every field beyond these six is a PII leak with prior sign-off (ADR-009),
 * not a convenience.
 */
public record RecordVerificationView(
    UUID recordId,
    String hash,
    Optional<String> previousHash,
    long chainPosition,
    Instant issuedAt,
    String nonSubmittedNotice) {

  public RecordVerificationView {
    Objects.requireNonNull(recordId, "recordId must not be null");
    Objects.requireNonNull(hash, "hash must not be null");
    Objects.requireNonNull(previousHash, "previousHash must not be null — use Optional.empty()");
    Objects.requireNonNull(issuedAt, "issuedAt must not be null");
    Objects.requireNonNull(nonSubmittedNotice, "nonSubmittedNotice must not be null");
  }
}
