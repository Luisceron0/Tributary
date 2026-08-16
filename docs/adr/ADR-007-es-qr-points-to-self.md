# ADR-007: The ES-regime QR points at this system's own verifier, never at the AEAT

**Status:** Accepted

## Context

The Verifactu QR normally contains a verification URL at the AEAT's own electronic site. Given
[ADR-005](ADR-005-es-adapter-does-not-remit.md) — no record is ever remitted — a QR pointing
there would tell whoever scans it that the invoice is registered with the tax authority when it
is not. That isn't a missing feature; it's a false claim printed on a legal document.

## Decision

The QR keeps the regime's own field structure, but the URL points at
`GET /api/v1/records/{id}/verification` — this system's own verifier — and the legend explicitly
states non-remitted mode.

## Consequences

The QR is not the official AEAT-verifiable one, and this is documented rather than left for
someone to discover. In exchange, every claim the QR makes is checkable against the system that
made it — nothing asserted, nothing assumed.

Verified directly, not just asserted: `VerifactuQrGeneratorTest` asserts the generated QR content
never contains an AEAT hostname (`agenciatributaria.es`, `agenciatributaria.gob.es`, `aeat.es`)
and always carries the non-remitted legend — this is CV-12 in the SRS's own verification matrix.
A dedicated Semgrep rule (`tributary-no-aeat-hostname`, see `.semgrep/tributary-rules.yml`)
additionally guards against an AEAT hostname literal appearing anywhere else in the codebase.

## Alternatives considered

- **Point at the AEAT anyway.** Produces a QR that fails validation when scanned against the real
  AEAT verifier, and in the meantime asserts something false.
- **Omit the QR.** Drops a central, genuinely implementable requirement of the regime along with
  the part that couldn't be implemented honestly.
