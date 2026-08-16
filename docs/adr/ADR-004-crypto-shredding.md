# ADR-004: Crypto-shredding reconciles GDPR erasure with fiscal retention

**Status:** Accepted

## Context

GDPR Article 17 (right to erasure) and multi-year fiscal retention obligations both apply to the
same underlying data, and they contradict each other directly: one requires deleting personal
data on request, the other requires keeping the fiscal record intact for years.

## Decision

Personal data is stored separately from the fiscal record itself, encrypted with AES-256-GCM
under a per-subject key with a random IV per operation. "Erasure" destroys the key, not the row —
the fiscal record (amounts, tax identifiers, the hash chain) remains intact and verifiable; the
personal data it once decrypted to becomes permanently unrecoverable ciphertext.

## Consequences

Key management becomes a critical path: losing a key is functionally identical to an
unauthorized erasure, so it needs backup and access control at the same level as the erasure
operation itself — restricted to the `ADMIN` role, distinct from the `OPERATOR` role that issues
documents (ADR-006's separation of duties applies here directly).

## Alternatives considered

- **Physically delete the row.** Breaks the hash chain (a chain member vanishing invalidates
  every record after it) and destroys the accounting record along with the personal data —
  over-broad; the fiscal obligation survives the GDPR request, the personal data doesn't need to.
- **Overwrite/anonymize in place.** Irreversible in the wrong direction: it also breaks the
  record's own hash (computed over the original bytes), and there's no way to distinguish "this
  was legitimately anonymized" from "this was tampered with" after the fact.
