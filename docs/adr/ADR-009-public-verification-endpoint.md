# ADR-009: One route is public — record verification, with a deliberately narrow body

**Status:** Accepted

> **Note on provenance.** This decision was made during implementation and is fully built, tested
> and enforced in code (`SecurityConfig`'s `permitAll()` rule, `RbacAndJwtIntegrationTest`,
> `RecordController`). It has not yet been folded back into `docs/SRS-tributary.md` itself as a
> versioned revision — that document still reads v1.0. Tracked as an open, low-severity,
> owner-assigned item (`tasks/todo.md`, blocker B-02). This file is the durable record of the
> decision either way; closing B-02 is a separate step for the SRS's own governance process.

## Context

Every other endpoint in this system requires an authenticated, role-checked JWT
([ADR-006](ADR-006-no-user-interface.md) explains why there's no login endpoint to issue one from
in the first place). But the QR generated for the ES regime ([ADR-007](ADR-007-es-qr-points-to-self.md))
points at a verification URL that has to be reachable by whoever scans the QR — a counterparty,
an auditor, anyone holding the physical or digital invoice — none of whom hold a Tributary token
or have any reason to.

## Decision

Exactly one route in the entire system is unauthenticated: `GET
/api/v1/records/{id}/verification`. Its response body is deliberately narrow — `{recordId, hash,
previousHash, chainPosition, issuedAt, nonSubmittedNotice}` — no PII, no monetary amounts, no tax
identifiers. It answers "is this specific record real and unbroken," nothing more.

## Consequences

This is the one place `anyRequest().denyAll()`'s fail-closed default is deliberately overridden,
which makes it the one route that needs its own explicit, standalone verification that it stays
exactly as public and exactly as narrow as intended — not broader by a future change that adds a
field without noticing what this route is allowed to leak. Covered directly:
`recordVerificationEndpointIsPublic` in `RbacAndJwtIntegrationTest` asserts a request with no
token at all never receives `401`, and the DTO shape itself is asserted field-by-field.

## Alternatives considered

- **Require authentication even here.** Breaks the QR's own purpose — a counterparty scanning an
  invoice has no Tributary account and no reason to need one just to confirm the document is
  real.
- **Return the full record.** With no authorization check on this route by design, anyone who
  obtains a record ID from the invoice itself (which is the whole point — that's how a
  counterparty verifies it) would also see tax identifiers and amounts, not just proof the record
  is unbroken. The narrow body is the actual control here, not the record ID's unguessability.
