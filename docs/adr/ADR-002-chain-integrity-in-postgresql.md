# ADR-002: The integrity chain is enforced in PostgreSQL, not in the application

**Status:** Accepted

## Context

Spanish RD 1007/2023 (Verifactu) requires tamper-evidence: once a fiscal record is written, it
must never be silently alterable, and any alteration must be detectable. A check written in Java
protects only the code path that happens to go through Java — a support script, a botched
migration, or a misused ORM bypasses it entirely.

## Decision

A `BEFORE INSERT` trigger validates that each new fiscal record correctly chains to the previous
one (hash, `previous_hash`, sequence), and a separate trigger rejects any `UPDATE` on an
already-chained record outright. The application computes the hash; the database is the sole
authority that verifies it and the sole enforcer that a written record cannot move.

## Consequences

Logic lives in PL/pgSQL, with the testing and portability cost that implies — it's tested against
real PostgreSQL via Testcontainers, never against an in-memory substitute (a trigger tested
against H2 is not a tested trigger). In exchange, the guarantee survives a support engineer with
`psql` access, a migration script that forgets to go through the application layer, and an ORM
that decides to "help" with a bulk update.

See it enforced live — a direct `UPDATE` against a chained record, rejected by name — in the
main [README](../../README.md#tamper-evidence-cv-02--cv-03).

## Alternatives considered

- **Application-only validation.** Trivially bypassed by anything with direct database access —
  which includes every future support script this project doesn't control yet.
- **Append-only by permission grant alone (`REVOKE UPDATE`).** Necessary but insufficient: it
  stops an ordinary write, but a superuser or a role misconfiguration still gets through, and
  permissions alone don't *detect* tampering after the fact — only the chain and its verifier do
  that (ADR-002 and ADR-007's own CV-03).
