package com.tributary.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * T-801: the response body of {@code GET /api/v1/chains/{chainId}/verification}, previously an
 * inline {@code Map} — which meant the generated OpenAPI contract could say nothing about it.
 *
 * <p>{@code NON_NULL} is load-bearing, not cosmetic: an {@code INTACT} answer must stay exactly
 * {@code {"status":"INTACT","recordsVerified":N}} on the wire, with no null tamper-detail fields
 * appended. That exact shape is the evidence quoted in the README and captured in the §9B
 * protocol run, so changing it would invalidate published evidence to satisfy a type.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChainVerificationView(
    String status,
    long recordsVerified,
    UUID brokenRecordId,
    String storedHash,
    String recomputedHash,
    Long totalMismatches) {

  public static ChainVerificationView intact(long recordsVerified) {
    return new ChainVerificationView("INTACT", recordsVerified, null, null, null, null);
  }

  public static ChainVerificationView broken(
      UUID brokenRecordId, String storedHash, String recomputedHash, long totalMismatches, long recordsVerified) {
    return new ChainVerificationView(
        "BROKEN", recordsVerified, brokenRecordId, storedHash, recomputedHash, totalMismatches);
  }
}
