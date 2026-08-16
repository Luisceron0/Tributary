# ADR-008: XRechnung validation uses the official KoSIT validator, not embedded rules

**Status:** Accepted

## Context

XRechnung conformance can be checked with an XSD and Schematron ruleset embedded in this project,
or against the official KoSIT validator and its own scenario files.

## Decision

Use the official KoSIT validator: a fixed version, checksum-verified before use, run in CI.

## Consequences

This adds a genuine supply-chain dependency and a new integrity vector — a tampered validator
artifact would accredit any document as conformant. Mitigated the same way the project treats any
externally-fetched artifact it trusts: the version and its SHA-256 are pinned in the repository
(`scripts/install-kosit-validator.sh`, `validator/*.sha256`), and a mismatch aborts the build
before the artifact is ever unpacked — CV-11 in the SRS's verification matrix.

In exchange, CV-05 stops being "my own implementation validates itself" (a tautology) and becomes
evidence against the actual reference tool the German ecosystem uses — the exact audience this
project is written for.

## Alternatives considered

- **Embedded XSD/Schematron rules, self-maintained.** Validating against rules the project itself
  transcribed from the specification is not independent verification; a transcription error would
  validate itself as correct.
