package com.tributary.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The fiscal document lifecycle (RF-002, RF-008; SRS 9C task T-102).
 *
 * <p>Every transition not declared in {@link #allowedTransitions()} throws. That is the whole
 * point of this class: an irreversible side effect (issuing a fiscal document) must never be
 * reachable through a state change nobody decided on.
 *
 * <ul>
 *   <li>{@link #DRAFT} -&gt; {@link #SUBMITTING} only — RF-002 step 1: the transition is
 *       committed in its own transaction before any network I/O.
 *   <li>{@link #SUBMITTING} -&gt; a terminal outcome ({@link #ISSUED}, {@link
 *       #ISSUED_WITH_WARNINGS}, {@link #REJECTED}) or {@link #NEEDS_RECONCILIATION} on a lost
 *       response — never a retry (RF-002 alternate flows).
 *   <li>{@link #NEEDS_RECONCILIATION} -&gt; back to {@link #SUBMITTING} to retry issuance if the
 *       regime has no record of it, or straight to {@link #ISSUED}/{@link
 *       #ISSUED_WITH_WARNINGS} if the regime confirms it was issued after all, or to {@link
 *       #MANUAL_REVIEW} (RF-008). Counting the three ambiguous attempts that trigger the last one
 *       is the reconciler's job (T-306), not this state machine's.
 *   <li>{@link #ISSUED}, {@link #ISSUED_WITH_WARNINGS}, {@link #REJECTED} and {@link
 *       #MANUAL_REVIEW} are terminal. A correction is a NEW document referencing this one
 *       (RF-004) — the original's state never changes again. MANUAL_REVIEW has no automatic exit
 *       (SRS 9C).
 * </ul>
 */
public enum DocumentState {
  DRAFT,
  SUBMITTING,
  ISSUED,
  ISSUED_WITH_WARNINGS,
  REJECTED,
  NEEDS_RECONCILIATION,
  MANUAL_REVIEW;

  private static final Map<DocumentState, Set<DocumentState>> TRANSITIONS = buildTransitionTable();

  private static Map<DocumentState, Set<DocumentState>> buildTransitionTable() {
    Map<DocumentState, Set<DocumentState>> table = new EnumMap<>(DocumentState.class);
    table.put(DRAFT, EnumSet.of(SUBMITTING));
    table.put(SUBMITTING, EnumSet.of(ISSUED, ISSUED_WITH_WARNINGS, REJECTED, NEEDS_RECONCILIATION));
    table.put(
        NEEDS_RECONCILIATION, EnumSet.of(SUBMITTING, ISSUED, ISSUED_WITH_WARNINGS, MANUAL_REVIEW));
    table.put(ISSUED, EnumSet.noneOf(DocumentState.class));
    table.put(ISSUED_WITH_WARNINGS, EnumSet.noneOf(DocumentState.class));
    table.put(REJECTED, EnumSet.noneOf(DocumentState.class));
    table.put(MANUAL_REVIEW, EnumSet.noneOf(DocumentState.class));
    return Collections.unmodifiableMap(table);
  }

  private Set<DocumentState> allowedTransitions() {
    return TRANSITIONS.get(this);
  }

  public boolean canTransitionTo(DocumentState target) {
    Objects.requireNonNull(target, "target must not be null");
    return allowedTransitions().contains(target);
  }

  /** @throws IllegalStateException if this transition was not declared above. */
  public DocumentState transitionTo(DocumentState target) {
    if (!canTransitionTo(target)) {
      throw new IllegalStateException("illegal transition: " + this + " -> " + target);
    }
    return target;
  }

  public boolean isTerminal() {
    return allowedTransitions().isEmpty();
  }
}
