package com.tributary.domain;

/**
 * One EN 16931 business rule violation. RF-005: rejection carries the rule identifier ({@code
 * BR-xx}), never a silently invalid document.
 *
 * @param ruleId the EN 16931 business rule identifier, e.g. {@code "BR-AE-01"}
 * @param message a human-readable explanation, including which line it concerns when applicable
 */
public record RuleViolation(String ruleId, String message) {

  public RuleViolation {
    ruleId = Preconditions.requireNonBlank(ruleId, "ruleId");
    message = Preconditions.requireNonBlank(message, "message");
  }
}
