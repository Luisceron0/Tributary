# ADR-003: Idempotency via a deterministic key, reconciliation before any retry

**Status:** Accepted

## Context

A lost response after a real issuance leaves the system unable to tell whether the document was
actually issued upstream. Both obvious responses are wrong: retrying risks a duplicate fiscal
document (irreversible — a second CUFE for the same sale is a real, separate legal fact), and not
retrying risks silently losing a legitimate issuance.

## Decision

A `businessKey` deterministically derived from the sale itself is used as the `reference_code`
sent to the regime, so a retry with the same key is recognizable as the same attempt, not a new
one. A dedicated `NEEDS_RECONCILIATION` state forces a query against the regime — "did this
already happen?" — before any retry is ever attempted. After three ambiguous outcomes, the system
stops retrying automatically and requires a human decision.

## Consequences

More states in the issuance state machine, and a reconciliation job that has to exist and be
tested on its own. In exchange, fiscal duplication becomes structurally impossible rather than
merely unlikely — verified by a real chaos test (CV-10: kill the process mid-flight, restart,
list by `reference_code` against the real Factus sandbox, confirm exactly one document exists).

## Alternatives considered

- **Retry with backoff.** Risks a genuine duplicate — the exact failure mode this decision exists
  to close off.
- **Never retry.** Loses legitimate issuances on any transient network failure.
- **Trust the regime's own duplicate rejection.** Delegates correctness to a third party the
  project doesn't control, and doesn't cover the case where the network itself is what failed
  (no request ever reached the regime to reject).
