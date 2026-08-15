package com.tributary.application.port;

/**
 * Persists a trace of one attempt against an external regime (SRS 6.4 {@code IssuanceAttempt}).
 *
 * <p>Takes the domain's own {@code businessKey}, never a persistence-layer row id — the domain
 * {@code Invoice} has no notion of a database identity, by design (ADR-001's separation applied to
 * identity, not just to types). The implementation resolves {@code businessKey} to whatever
 * internal id it needs.
 */
public interface IssuanceAttemptPort {

  void record(String businessKey, String regime, IssuanceResult result);
}
