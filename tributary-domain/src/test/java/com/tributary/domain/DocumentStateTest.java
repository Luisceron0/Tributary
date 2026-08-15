package com.tributary.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive test of {@link DocumentState}: RF-002, RF-008 and SRS 9C (T-102) require every
 * undeclared transition to throw, so this asserts the full cross product of states rather than a
 * handful of examples — the same discipline as CV-07's "0 violations", not a sample.
 */
class DocumentStateTest {

  /**
   * The contract, restated independently of the implementation: RF-002's flow (DRAFT ->
   * SUBMITTING -> a terminal outcome or NEEDS_RECONCILIATION) plus RF-008's reconciliation flow
   * (NEEDS_RECONCILIATION -> retry via SUBMITTING, or adopt the regime's answer, or escalate to
   * MANUAL_REVIEW after three ambiguous attempts — that counting itself is a T-306 concern, not
   * this state machine's). MANUAL_REVIEW, ISSUED, ISSUED_WITH_WARNINGS and REJECTED are terminal:
   * SRS 9C says MANUAL_REVIEW has no automatic exit, and RF-004 corrects an issued or rejected
   * document with a NEW document rather than transitioning the original.
   */
  private static final Map<DocumentState, Set<DocumentState>> EXPECTED =
      new EnumMap<>(DocumentState.class);

  static {
    EXPECTED.put(DocumentState.DRAFT, EnumSet.of(DocumentState.SUBMITTING));
    EXPECTED.put(
        DocumentState.SUBMITTING,
        EnumSet.of(
            DocumentState.ISSUED,
            DocumentState.ISSUED_WITH_WARNINGS,
            DocumentState.REJECTED,
            DocumentState.NEEDS_RECONCILIATION));
    EXPECTED.put(
        DocumentState.NEEDS_RECONCILIATION,
        EnumSet.of(
            DocumentState.SUBMITTING,
            DocumentState.ISSUED,
            DocumentState.ISSUED_WITH_WARNINGS,
            DocumentState.REJECTED,
            DocumentState.MANUAL_REVIEW));
    EXPECTED.put(DocumentState.ISSUED, EnumSet.noneOf(DocumentState.class));
    EXPECTED.put(DocumentState.ISSUED_WITH_WARNINGS, EnumSet.noneOf(DocumentState.class));
    EXPECTED.put(DocumentState.REJECTED, EnumSet.noneOf(DocumentState.class));
    EXPECTED.put(DocumentState.MANUAL_REVIEW, EnumSet.noneOf(DocumentState.class));
  }

  @Test
  @DisplayName("every (from, to) pair matches the declared contract — allowed succeeds, undeclared throws")
  void exhaustiveTransitionMatrix() {
    for (DocumentState from : DocumentState.values()) {
      for (DocumentState to : DocumentState.values()) {
        boolean expectedAllowed = EXPECTED.get(from).contains(to);
        boolean actualAllowed = from.canTransitionTo(to);
        assertEquals(
            expectedAllowed,
            actualAllowed,
            () -> "canTransitionTo mismatch for " + from + " -> " + to);

        if (expectedAllowed) {
          assertEquals(to, from.transitionTo(to), () -> from + " -> " + to + " should succeed");
        } else {
          assertThrows(
              IllegalStateException.class,
              () -> from.transitionTo(to),
              () -> from + " -> " + to + " should throw, it is not a declared transition");
        }
      }
    }
  }

  @Test
  @DisplayName("every state covered by the expected table above — nothing silently excluded")
  void expectedTableCoversEveryState() {
    assertEquals(EnumSet.allOf(DocumentState.class), EXPECTED.keySet());
  }

  @Test
  @DisplayName("MANUAL_REVIEW, ISSUED, ISSUED_WITH_WARNINGS and REJECTED are terminal")
  void terminalStatesHaveNoOutgoingTransitions() {
    assertAll(
        () -> assertTrue(DocumentState.MANUAL_REVIEW.isTerminal()),
        () -> assertTrue(DocumentState.ISSUED.isTerminal()),
        () -> assertTrue(DocumentState.ISSUED_WITH_WARNINGS.isTerminal()),
        () -> assertTrue(DocumentState.REJECTED.isTerminal()),
        () -> assertFalse(DocumentState.DRAFT.isTerminal()),
        () -> assertFalse(DocumentState.SUBMITTING.isTerminal()),
        () -> assertFalse(DocumentState.NEEDS_RECONCILIATION.isTerminal()));
  }

  @Test
  @DisplayName("a null target is rejected, not treated as a no-op")
  void rejectsNullTarget() {
    assertThrows(NullPointerException.class, () -> DocumentState.DRAFT.canTransitionTo(null));
    assertThrows(NullPointerException.class, () -> DocumentState.DRAFT.transitionTo(null));
  }
}
