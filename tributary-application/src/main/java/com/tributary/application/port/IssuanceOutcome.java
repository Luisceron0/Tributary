package com.tributary.application.port;

/**
 * The regime-agnostic result of an issuance attempt. Maps directly onto the {@code
 * DocumentState} transitions declared for {@code SUBMITTING} (RF-002's alternate flows): a clean
 * acceptance, an acceptance carrying warnings that must never be discarded, an explicit
 * rejection, or "the regime could not be reached" — which is NOT a rejection, and must never be
 * treated like one. A lost response means unknown state, not failed state (ADR-003); {@link
 * #UNREACHABLE} is what routes an issuance to {@code NEEDS_RECONCILIATION} rather than a retry.
 */
public enum IssuanceOutcome {
  ACCEPTED,
  ACCEPTED_WITH_WARNINGS,
  REJECTED,
  UNREACHABLE
}
