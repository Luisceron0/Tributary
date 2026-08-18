Feature: Reconciliation never invents a verdict (RF-008, ADR-003)
  # These scenarios state, in the language of the fiscal problem rather than of the code, the one
  # rule the whole reconciliation design exists to protect: a document whose fate is unknown must
  # never be recorded as one the authority ruled on.
  #
  # The rule has teeth because three of the reachable states are terminal. REJECTED in particular
  # has an empty transition set, so a document written into it is finished — there is no correction,
  # no retry and no operator action that moves it again. Every scenario below was reachable, and two
  # of them were genuinely wrong, before the audit that added this file.

  Background:
    Given an invoice "INV-2026-001" awaiting reconciliation

  Scenario: The regime confirms the document exists and was validated
    When the regime reports the document as validated with reference "SETP990015225"
    Then the invoice ends in state "ISSUED"
    And the recorded attempt references "SETP990015225"

  Scenario: A validated document carrying authority notices keeps them
    # The same fiscal document must not land in a different state merely because its original
    # response was lost and it had to be adopted through reconciliation instead.
    When the regime reports the document as validated with reference "SETP990015225" and notice "RUT01: contribuyente no obligado"
    Then the invoice ends in state "ISSUED_WITH_WARNINGS"
    And the recorded attempt carries 1 message

  Scenario: The regime confirms a real rejection, with its reasons
    When the regime reports the document as rejected with reason "FAJ44b: total no coincide"
    Then the invoice ends in state "REJECTED"
    And the recorded attempt carries 1 message

  Scenario: The regime has no record of the document at all
    # Not found is the one case where re-issuing is safe: the authority never saw it.
    When the regime reports the document as not found
    Then the regime is asked before any issuance is attempted
    And the invoice ends in state "ISSUED"

  Scenario: The regime cannot be reached
    When the regime reports the document as ambiguous
    Then the invoice remains in state "NEEDS_RECONCILIATION"
    And no issuance is attempted

  Scenario: Ambiguity that persists is escalated to a human, not resolved by guessing
    Given 2 consecutive ambiguous reconciliations have already occurred
    When the regime reports the document as ambiguous
    Then the invoice ends in state "MANUAL_REVIEW"
    And no issuance is attempted
