# ADR-005: The ES adapter builds and chains records — it does not remit them to the AEAT

**Status:** Accepted

## Context

Actually submitting Verifactu records to Spain's tax authority (AEAT) requires a qualified
electronic certificate and a formal *declaración responsable* as a software producer — a legal
and organizational commitment this project, as a reference implementation, is not positioned to
make truthfully.

## Decision

Implement exactly the part that is specifiable and independently verifiable: record generation,
canonical hashing, hash-chaining, and QR generation — and state the limit in the README in these
words, not softer ones.

## Consequences

The project cannot claim regulatory compliance, and does not. This is treated as a feature of the
project's honesty, not a shortfall to talk around: claiming compliance without the *declaración
responsable* that compliance actually requires would be a false claim, and a technical reviewer
who catches a compliance overstatement stops trusting everything else in the document — see risk
R-03 in the SRS. See also [ADR-007](ADR-007-es-qr-points-to-self.md), which exists specifically
because a QR pointing at the AEAT would silently contradict this decision.

## Alternatives considered

- **Simulate an AEAT response.** Fabricates evidence of something that never happened — worse
  than not implementing the regime at all.
- **Omit the ES regime entirely.** Loses the piece of the project most directly aligned with its
  own thesis (an immutable fiscal fact, enforced at the layer no application path can bypass).
