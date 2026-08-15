package com.tributary.application.port;

/**
 * The result of asking a regime "was this business key issued?" (RF-008). {@link #AMBIGUOUS} is
 * not an error to retry past silently: three consecutive ambiguous reconciliations move the
 * document to {@code MANUAL_REVIEW}, which has no automatic exit transition (SRS 9C).
 */
public enum QueryOutcome {
  FOUND_VALIDATED,
  FOUND_REJECTED,
  NOT_FOUND,
  AMBIGUOUS
}
