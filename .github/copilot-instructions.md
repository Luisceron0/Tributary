# Copilot instructions — Tributary

Derived from `docs/SRS-tributary.md` v1.0 (Approved). **The SRS is the source of truth.** If anything here contradicts the SRS, the SRS wins and this file is wrong — say so instead of silently following it.

---

## Session protocol

**At the start of every session, read in this order:**

1. `docs/SRS-tributary.md` — which milestone is active, which RFs are in scope.
2. `tasks/todo.md` — continue the active plan. Do not invent a new one.
3. `tasks/lessons.md` — do not repeat a documented mistake.

**At the end of every session, update:**

1. `tasks/todo.md` — mark what is done, record blockers.
2. `tasks/lessons.md` — any correction or non-obvious decision.

Never mark a task done without its explicit verification criterion satisfied. "It compiles" is not a verification criterion.

---

## Prohibited assumptions — never assume these without explicit confirmation

The expensive mistakes come from silent assumptions, not from missing knowledge.

- **Never assume a numeric type.** All monetary and tax values are `BigDecimal`, scale 2, `RoundingMode.HALF_UP`. `double` and `float` are banned in the domain module. If you find one, it is a bug regardless of the tests passing.
- **Never assume a value from a request body is trustworthy.** Totals are recalculated server-side from the domain. The client's numbers are a proposal.
- **Never assume the actor identity from the request body.** The actor always comes from the validated JWT subject.
- **Never assume an HTTP header is safe.** See "Headers are hostile input" below.
- **Never assume a retry is safe.** A lost response means unknown state, not failed state. See "Irreversibility".
- **Never assume a library's default parser configuration is secure.** It is not, for XML. See T-004.
- **Never assume a framework annotation enforces a rule.** Rules that matter live in the database.
- **Never assume you may add a dependency.** Every direct dependency needs a justification in the README. Ask first.
- **Never assume the scope.** If a task seems to need something outside `docs/SRS-tributary.md` §3, stop and ask. Do not widen scope to be helpful.

---

## Non-negotiable security rules

### Headers are hostile input

`User-Agent`, `Referer`, `X-Forwarded-For`, `X-Real-IP`, `Host`, `Cookie`, `Accept-Language` and any custom header are untrusted, always:

- Never concatenate them into SQL, shell commands, file paths or templates.
- **Every** database write that includes them uses prepared statements — including logging and audit writes. An audit table written by string concatenation is an SQL injection sink that nobody reviews.
- `X-Forwarded-For` is accepted only from the trusted proxy. The client-supplied value is discarded.
- `Host` is validated against an explicit allowlist.
- Threat T-006. Verified by CV-01 with `sqlmap --level 3` fuzzing headers, not just query parameters.

### XML parsing is the highest-severity surface in this system

Threat T-004, DREAD 8.6. Every XML parser is created through `SecureXmlFactory` and nowhere else — ArchUnit enforces this. That factory sets: DTD disabled, external general and parameter entities disabled, `XMLConstants.FEATURE_SECURE_PROCESSING` on, `XMLConstants.ACCESS_EXTERNAL_DTD` and `ACCESS_EXTERNAL_SCHEMA` set to empty, plus explicit input size and nesting depth limits.

If you need to parse XML anywhere and the factory does not expose what you need, extend the factory. Do not instantiate a parser inline "just this once".

### Irreversibility

Issuing a fiscal document is an irreversible side effect. There is no undo.

- Issuance is always preceded by a committed state transition, in its own transaction, **before** any network I/O.
- A timeout produces `NEEDS_RECONCILIATION`, never a retry.
- The reconciler queries the external regime by `reference_code` before deciding. There is no code path that issues without querying first.
- Three ambiguous reconciliations move the document to `MANUAL_REVIEW`, which has no automatic exit transition.
- `reference_code` is always the deterministic `businessKey`. Never a random UUID, never a timestamp.

### Immutability lives in PostgreSQL

Chain validation and update rejection are triggers, not service-layer checks. If you are tempted to add a Java-side guard "for clarity", do not: two sources of truth for the same rule diverge, and the weaker one gets trusted.

Application code never issues `UPDATE` or `DELETE` against `fiscal_record` or `audit_event`. The application's database role does not have those grants.

### Secrets

Credentials come from environment variables. Never a literal, never a default value in code, never a fixture, never a comment, never a test resource. `.env` is gitignored. gitleaks runs in pre-commit and in CI.

If a secret ever reaches a commit, the remediation is **rotate the credential**, not delete the file. Say this explicitly if it happens.

### Fail-closed environment guard

The service refuses to start against the Factus production URL unless an explicit enablement variable is set, and the production secret is read from a differently named variable than the sandbox one. Never make the environment switch implicit or convenient.

### Separation of duties

`OPERATOR` issues and corrects. `AUDITOR` reads and verifies. `ADMIN` manages keys and erasure. No role has both issuance and evidence destruction. Every new endpoint gets a negative test per role.

### Honesty in output

The system never claims something it did not do. Specifically: the ES-regime QR points to this system's own verification endpoint and never to an AEAT host (ADR-007, verified by CV-12). No README, log message, response field or comment may describe the system as compliant or certified under any regime.

---

## Architecture rules

- `tributary-domain` has **zero** dependencies. No Spring, no Jackson, no JDBC, no validation annotations. Enforced by ArchUnit. If a task seems to require a dependency in the domain, the design is wrong — stop and ask.
- The domain model uses EN 16931 semantics (BT/BG terms). Factus payloads, Verifactu records and CII XML are **output projections**, built in their adapters. Never leak a regime-specific field name into the domain (ADR-001).
- Ports are defined in `tributary-application`. Adapters depend inward, never outward.
- Naming is in English throughout, including domain concepts.
- No microservices, no queues, no event bus. Single deployable. Adding infrastructure without a problem that requires it is a defect, not an improvement.

---

## Testing rules

- New domain logic requires a unit test in the same commit.
- Arithmetic gets property-based tests (jqwik), not just examples. Rounding bugs do not show up in examples.
- Database behaviour is tested with Testcontainers against real PostgreSQL 16. A trigger tested against H2 is untested.
- Semgrep rules are validated against deliberately vulnerable code first. **A rule never observed failing proves nothing.**
- Never weaken or delete a test to make a build pass. If a test is wrong, fix the test deliberately and record it in `tasks/lessons.md`.

---

## Handling findings during development

If a control from SRS §9A cannot be implemented as specified:

1. Do **not** omit it silently.
2. Do **not** implement a reduced version without saying so.
3. Open an issue labelled `security-gap` describing: which control, why it is not implementable, the resulting risk with its threat ID, and a proposed compensating mitigation.
4. Flag it before opening the PR.

If you find a vulnerability not covered by the SRS while working:

1. Do not proceed as if it were not there.
2. Report it with a severity classification per SRS §10.2.
3. Reference the ATT&CK technique if known.

**PR blocking thresholds:** BLOCKING or HIGH blocks the merge. MEDIUM blocks if the PR touches the affected component. LOW gets a comment and an issue.

---

## Verification commands

```bash
mvn test                                   # unit, property, integration
mvn test -Dtest=ArchitectureTest           # CV-07 domain isolation
gitleaks detect --source . --redact        # CV-06 secrets
sha256sum -c validator/kosit.sha256        # CV-11 supply chain
docker compose up -d && ./scripts/verify-chain.sh   # CV-02, CV-03
./scripts/xxe-probe.sh                     # CV-04
./scripts/rbac-matrix-test.sh              # CV-08
```

A control is verified when its command produces the expected binary result and the evidence is captured. A control that is described but not exercised is declared, not verified.

---

## Definition of Done for any task

- [ ] Tests green in CI
- [ ] Domain coverage ≥ 90 %, overall ≥ 70 %
- [ ] ArchUnit clean
- [ ] gitleaks clean
- [ ] No high or critical dependency findings
- [ ] Applicable §9A controls green with captured evidence
- [ ] OpenAPI regenerated if contracts changed
- [ ] ADR written if an SRS decision changed during implementation
- [ ] `tasks/todo.md` and `tasks/lessons.md` updated
