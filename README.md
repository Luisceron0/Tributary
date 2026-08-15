# Tributary

A cross-border B2B sale creates simultaneous tax obligations in jurisdictions whose
technical models are incompatible: Colombia clears invoices before they legally exist,
Spain requires a hash-chained invoicing record, Germany requires a structured document
handed to the buyer. Tributary models the business fact once and delegates the
translation to per-regime adapters.

The thesis: a fiscal document is an immutable fact, correctable only by a later document
that references it — and that invariant belongs in the layer no application path can
bypass. In this system that layer is PostgreSQL, not Java (ADR-002).

> **Status: work in progress.** Phase 0 of 7. See [`tasks/todo.md`](tasks/todo.md).
>
> This is a reference implementation built against public specifications. It is **not
> certified** under any fiscal regime and must not be used to issue invoices in
> production. The ES adapter builds and chains records but does not submit them to the
> AEAT (ADR-005). The DE adapter builds and validates documents but does not transport
> them over Peppol.

## Documentation

| Document | Contents |
|---|---|
| [`docs/SRS-tributary.md`](docs/SRS-tributary.md) | Requirements, architecture, ADRs, threat model, verification matrix. **Source of truth.** |
| [`tasks/todo.md`](tasks/todo.md) | Build plan and task status |
| [`tasks/lessons.md`](tasks/lessons.md) | Design corrections and their derived rules |

The full README — thesis, tamper-detection evidence, ADR index and dependency
justifications — is written in T-706. This file is a placeholder that states the scope
limits, which is the part that must never be missing.

## License

Apache-2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
