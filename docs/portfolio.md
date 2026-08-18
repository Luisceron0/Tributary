# Tributary

**A multi-regime e-invoicing engine that treats "this fiscal document cannot be altered" as a
database guarantee, not an application promise.**

Reference implementation · Colombia (Factus/DIAN) · Spain (Verifactu) · Germany (XRechnung) ·
built in 7 days against public specifications and real sandboxes.

## The problem

A single cross-border sale can trigger three fiscal obligations with incompatible technical
models: Colombia clears the invoice before it legally exists (a government API call is part of
issuance itself), Spain requires a tamper-evident hash chain kept by the issuer, Germany requires
a structured document format handed to the buyer. Most systems that touch more than one of these
either bolt each regime on as a special case or quietly model the domain after whichever regime
was implemented first — the second regime always reveals which one that was.

## The approach

The domain models the fiscal fact once, using EN 16931 (the European invoicing standard) as the
canonical vocabulary even for the Colombian regime, which doesn't require it. Each regime is a
thin translator at the edge, not a fork of the core. The result: adding a fourth regime is writing
a new adapter against an existing port, not touching the model every other regime already
depends on. See [ADR-001](adr/ADR-001-domain-model-en16931.md).

The part most worth a reviewer's ten minutes: **the immutability guarantee lives in PostgreSQL,
not in application code.** A `BEFORE INSERT` trigger validates hash-chaining on every write; a
separate trigger rejects any `UPDATE` on an already-written fiscal record outright — enforced at
the database layer, so it holds even against a support engineer with direct `psql` access, not
just against requests that happen to go through the Spring Boot application. [ADR-002](adr/ADR-002-chain-integrity-in-postgresql.md)
explains why, and the [README](../README.md#tamper-evidence-cv-02--cv-03) shows it live: a direct
`UPDATE` rejected by name, then a deliberately corrupted record caught by the system's own
verifier, which names the exact broken row.

## What's verifiable, not just claimed

| | |
|---|---|
| SQL injection | `sqlmap` (level 3–5, risk 2–3) against real JSON body fields, path parameters, and headers — not injectable, on every write path including audit logging |
| Tamper detection | Live: disable the chain trigger, corrupt a record directly, ask the verifier — `BROKEN`, naming the exact row |
| JWT algorithm confusion | An `alg:none` token and an HS256 token signed with the RSA public key's own bytes — both rejected |
| Separation of duties | The role that issues documents cannot also erase evidence of having issued them — enforced by RBAC, checked by a negative test per role × endpoint |
| Supply chain | The one external validator this project trusts (Germany's official KoSIT tool) is checksum-pinned; a mismatch aborts the build before the artifact is ever unpacked |
| CI | Four independent gates on every push: full test suite + architecture rules, SAST, dependency scanning (blocking on HIGH/CRITICAL), secret scanning across full history |

Thirteen controls in total, each with a tool, a command, and a binary pass/fail criterion — the
full matrix is §9A of the [SRS](SRS-tributary.md#9a-matriz-de-verificación-de-controles).

![Chain verification reporting BROKEN, naming the exact record whose stored hash no longer matches the recomputed one](evidence/chain-broken.png)

## Engineering decisions that mattered

- **Idempotency by deterministic key, not by retry logic.** A lost network response after a real
  issuance can't be resolved by "retry" (risks a duplicate legal document) or "don't retry"
  (risks losing a real one). The fix is a `businessKey` derived from the sale itself and a
  mandatory reconciliation query before any retry is attempted — verified with a real chaos test
  against the live sandbox: kill the process mid-issuance, restart, confirm exactly one document
  exists. [ADR-003](adr/ADR-003-idempotency-and-reconciliation.md)
- **Crypto-shredding to reconcile GDPR erasure with fiscal retention.** The two obligations
  contradict each other on the same row. Personal data is encrypted under a per-subject key;
  "erasure" destroys the key, not the row — the fiscal record survives intact and verifiable, the
  personal data becomes permanently unrecoverable ciphertext. [ADR-004](adr/ADR-004-crypto-shredding.md)
- **Saying no to a live AEAT submission.** The Spanish adapter builds and hash-chains records and
  generates the regulator's QR — but doesn't submit anything, because that requires a qualified
  certificate this project doesn't hold. The QR points at the system's own verifier instead of
  the tax authority, with an explicit non-submitted notice, so nothing the system produces claims
  something that didn't happen. [ADR-005](adr/ADR-005-es-adapter-does-not-remit.md) /
  [ADR-007](adr/ADR-007-es-qr-points-to-self.md)

## Interface

A React frontend ([ADR-010](adr/ADR-010-web-frontend-demo-mode.md)) covers all eight endpoints,
one panel per role, with no login — pre-minted demo tokens instead, disclosed as such on the page
itself. The public build ships **no administrator credential**, and that's a cryptographic fact,
not a UI restriction: tokens are RS256-signed, so reading the operator/auditor tokens in the page
source cannot mint an admin one without the private key, which never leaves the build environment.

![The administrator panel explaining that this deployment carries no administrator credential, and why that is a cryptographic guarantee rather than a UI restriction](evidence/admin-unavailable.png)

## Scope, stated plainly

Not certified under any fiscal regime. Not for production issuance. Colombia issues real sandbox
invoices; Spain builds and chains but does not remit; Germany builds and validates but does not
transport over Peppol. Full detail in the [README](../README.md#scope-and-honesty).

## Stack

Java 21 · Spring Boot (API/persistence layer only — the domain has zero framework dependency,
enforced by ArchUnit) · PostgreSQL 16 · Flyway · Testcontainers · jqwik (property-based testing)
· React 19 · TypeScript · Vite (API client generated from the OpenAPI contract, never hand-written)
· Docker Compose · GitHub Actions.

**[Full README →](../README.md) · [Architecture decisions →](adr/) · [Try it in under 3 minutes →](../README.md#quickstart)**
