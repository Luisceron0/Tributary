# Architecture Decision Records

Each ADR is one decision, the constraint that forced it, and what was deliberately given up —
not a design overview. `docs/SRS-tributary.md` (Spanish, the project's source of truth) is where
each of these originates; these are the English, standalone versions referenced from the main
[README](../../README.md).

| ADR | Decision |
|---|---|
| [001](ADR-001-domain-model-en16931.md) | Domain model built on EN 16931, not on Factus's payload shape |
| [002](ADR-002-chain-integrity-in-postgresql.md) | Chain integrity enforced in PostgreSQL, not the application |
| [003](ADR-003-idempotency-and-reconciliation.md) | Idempotency via a deterministic key, reconciliation before retry |
| [004](ADR-004-crypto-shredding.md) | Crypto-shredding reconciles GDPR erasure with fiscal retention |
| [005](ADR-005-es-adapter-does-not-remit.md) | ES adapter builds and chains records — never remits them |
| [006](ADR-006-no-user-interface.md) | No user interface — *superseded in part by 010* |
| [007](ADR-007-es-qr-points-to-self.md) | ES-regime QR points at this system's own verifier, never the AEAT |
| [008](ADR-008-kosit-validator.md) | XRechnung validation uses the official KoSIT validator |
| [009](ADR-009-public-verification-endpoint.md) | One public route: record verification, narrow response body |
| [010](ADR-010-web-frontend-demo-mode.md) | A web frontend, in demo authentication mode (supersedes 006 in part) |
