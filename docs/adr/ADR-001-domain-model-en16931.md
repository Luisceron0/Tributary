# ADR-001: The domain model is built on EN 16931, not on Factus's payload shape

**Status:** Accepted

## Context

The first adapter implemented is the Colombian one (Factus), and the obvious shortcut is to
model the domain around the shape of Factus's own JSON. That would be faster for exactly one
regime and wrong for every regime after it.

## Decision

The domain uses EN 16931 semantics (Business Terms / Business Groups) as the canonical model.
Factus, Verifactu and XRechnung are all projections *out of* that model, never the model itself.

## Consequences

More upfront mapping work in the CO adapter, which has to translate EN 16931 concepts into
Factus's own vocabulary. In exchange, adding a European regime is writing a translator, not
redesigning the core — and the domain reads the same way to a European reviewer as to a Colombian
one, which is the decision that keeps the project legible across all three regimes at once.

Enforced structurally, not just by convention: `tributary-domain` has zero framework
dependencies and never imports a regime-specific vocabulary — verified by ArchUnit
(`ArchitectureTest`, CV-07) on every build. See the domain isolation evidence in the main
[README](../../README.md#domain-isolation-cv-07).

## Alternatives considered

- **Model on Factus's payload shape.** Couples the core to one vendor's API design; every later
  regime would need to be bent to fit it.
- **An ad-hoc common denominator.** Reinvents a standard that already exists, with worse
  documentation and no external reviewer who already knows it.
