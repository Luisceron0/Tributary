# ADR-006: No user interface

**Status:** Superseded **in part** by [ADR-010](ADR-010-web-frontend-demo-mode.md) — the "no
interface" decision no longer holds; the consequences below about the empty CORS allowlist and
the absence of a login endpoint both still do. See ADR-010 for which reasoning expired and which
survived.

## Context

The intended reader is a backend/architecture reviewer, not an end user evaluating a product.

## Decision

The interface is the OpenAPI contract and a test suite written to read as executable
specification — no frontend, no admin panel.

## Consequences

The project produces no attractive screenshots beyond the §9A control evidence captured for
CV-01 through CV-12. That trade is accepted deliberately.

It also shapes two later, concrete decisions: CORS defaults to an empty allowlist rather than a
permissive one, because no browser origin needs cross-origin access unless a deployment
explicitly configures one (`tributary.security.cors-allowed-origins`, empty by default — see
`SecurityConfig`); and there is no login/token-issuance endpoint, because there is no browser
session to establish one for — the API is a pure OAuth2 resource server that verifies tokens
issued elsewhere. The `docker-compose` demo mints its own throwaway tokens for exactly this
reason (`scripts/demo/`).

## Alternatives considered

- **A minimal UI.** Consumes roughly two of the seven days budgeted for the whole project and
  shifts focus away from the parts of the system the thesis is actually about.
