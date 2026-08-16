package com.tributary.application.port;

/**
 * T-603: append-only audit trail (SRS 6.4's {@code AuditEvent}). {@code actor} is a parameter
 * here, not derived by this port — callers are expected to supply the actor extracted from a
 * validated JWT subject, never from a request body (T-009's repudiation threat, closed for real
 * once phase 7 wires a real token into the actor a caller passes here). Until then, any use case
 * calling this port passes its actor explicitly, the same way {@code IssueInvoiceUseCase} (T-304)
 * takes its dependencies as constructor parameters rather than reaching for ambient state.
 */
public interface AuditEventPort {

  /**
   * Appends one immutable event. {@code result} is free text (e.g. {@code "SUCCESS"}, {@code
   * "DENIED"}) — RF-007 in particular requires this to record WHO/WHEN/WHICH SUBJECT/WHY without
   * ever including the suppressed PII itself; enforcing that is the caller's responsibility, not
   * this port's, since only the caller knows which fields are personal data.
   */
  void record(String actor, String action, String entity, String result);
}
