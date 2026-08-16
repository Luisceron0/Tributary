# ADR-010: A web frontend, in demo authentication mode

**Status:** Accepted — supersedes [ADR-006](ADR-006-no-user-interface.md) **in part**

## Context

[ADR-006](ADR-006-no-user-interface.md) rejected a user interface on two grounds: the intended
reader was a backend/architecture reviewer, and a minimal UI would consume roughly two of the
seven days budgeted for the whole project.

The second ground has expired, not been overruled: Milestone 1 is complete, every phase 0–7 task
is closed with captured evidence, and the seven-day budget it protected is spent. The first
ground has narrowed rather than vanished — the portfolio goal now also includes demonstrating
full-stack capability, which the API-and-tests-only shape cannot show.

A frontend also closes one genuine functional gap that has existed since
[ADR-007](ADR-007-es-qr-points-to-self.md): the ES-regime QR is meant to be scanned by a human
and currently answers with raw JSON.

## Decision

Build a web frontend as a separate `tributary-web` module — React + TypeScript + Vite, served as
static assets from an origin separate from the API.

**Authentication is demo mode: there is no login.** The frontend carries pre-minted JWTs (the
same ones `scripts/demo/mint-token.sh` already produces) behind an explicit role selector,
labelled as a demo in the interface itself.

The TypeScript API client is **generated from `docs/openapi.json`**, not hand-written.

## Consequences

**What ADR-006 decided that still stands.** Demo mode means no browser session is ever
established, so ADR-006's consequence — *"there is no login/token-issuance endpoint... the API is
a pure OAuth2 resource server that verifies tokens issued elsewhere"* — remains literally true.
`SecurityConfig` does not change, §6.5 gains no endpoint, and the resource-server/authorization-
server split stays honest. Only the "no interface" decision itself is superseded.

**Risk R-05 goes up and must be actively managed.** The SRS names it: *"the project is perceived
as just another Java CRUD."* A CRUD interface pushes toward that perception. The mitigation is
editorial, not technical — the README keeps opening with the thesis and the CV-02/CV-03 tamper
evidence, and the frontend is presented as a consequence of the system, never as its headline.

**The CORS allowlist stops being theoretical.** ADR-006's empty-by-default
`tributary.security.cors-allowed-origins` was correct precisely because no origin existed. One
now does, and the allowlist becomes a live, exercised control rather than a defensive default.

**A second supply chain enters the project.** npm brings a dependency ecosystem the Maven-side
controls (trivy SCA, pinned versions, gitleaks) do not currently cover. CI must scan it with the
same blocking threshold, and the lockfile is pinned like every other version in this repository
(SRS 5.3).

**Demo tokens are readable by anyone who opens DevTools.** This is inherent to the decision, not
an oversight: a token shipped in a static bundle is public. On a publicly deployed instance that
means an anonymous visitor can exercise every role, including `DELETE
/api/v1/subjects/{subjectId}/personal-data` — the exact separation-of-duties boundary CV-08
verifies. "It is only a demo" does not make that acceptable on its own. It is made acceptable by
the deployment shape instead: a throwaway database with scheduled reset, synthetic data with no
real personal data (§10.5 already requires this), and an explicit notice in both the interface
and the README. CV-08 continues to be verified where it is actually meaningful — against the API,
in the integration suite — not against a demo instance whose tokens are published by design.

## Alternatives considered

- **Keycloak as a real authorization server.** Architecturally the strongest option and a genuine
  resource-server/authorization-server split rather than a described one. Rejected on cost: it
  roughly doubles the deployment's memory footprint, which rules out every small free tier and
  forces self-managed infrastructure for a project that explicitly must not run in production
  ([ADR-005](ADR-005-es-adapter-does-not-remit.md)).
- **A login endpoint inside `tributary-api`.** Cheaper to host, but it contradicts the documented
  design, requires amending §6.5, and introduces credential storage, password hashing and
  brute-force protection — a large, security-critical surface added to a project whose thesis is
  that security controls belong where they cannot be bypassed.
- **Server-rendered Thymeleaf inside `tributary-api`.** No JavaScript toolchain and a far smaller
  dependency surface, but it mixes the interface into the API module and contradicts the
  stateless, CSRF-disabled design that the bearer-token API depends on.
- **A single public verification page and nothing else.** The narrowest option that still closes
  the ADR-007 QR gap. Rejected as insufficient for the stated goal, but retained as a component:
  it becomes the one route of the frontend that needs no token at all, matching
  [ADR-009](ADR-009-public-verification-endpoint.md).
