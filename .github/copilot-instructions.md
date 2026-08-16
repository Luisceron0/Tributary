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

## Toolchain and verification discipline

Third-party tooling added to this repo goes through the O.5 gate before installation — six questions, answered against a real, checked source, logged in `docs/decisiones.md`:

- **Verified source:** installed from the author's official channel (documented repo/npm), not a mirror.
- **Data surface:** what the tool reads, what it persists, whether it syncs anywhere.
- **Light STRIDE:** can it exfiltrate secrets? Does it run commands against untrusted input?
- **Context scope:** how much it loads into a session; is there a narrower profile.
- **Conflict with the mandate:** does any default behavior contradict security-by-design here — e.g. an "aggressive minimalism" mode that would strip validation, authorization, or security logging.
- **Record:** the decision and its reservations, in `docs/decisiones.md`.

No tool is installed without that record — same discipline as "every control needs a binary criterion," applied to the tool instead of the code.

**Installed for this project, each with its own entry and non-negotiable reservations in `docs/decisiones.md`:**

- `pip install semgrep` — SAST. **A rule is never trusted without being observed failing red against a deliberately vulnerable fixture first; "0 findings" only means something once you've confirmed the rule can find anything.** Verify both directions: N findings on the vulnerable fixture, 0 on real code.
- Trail of Bits Skills (`trailofbits/skills`, official org account only — verified copycat forks exist under near-identical names) — `variant-analysis` real and verified; installed **only** from an interactive Claude Code session (`/plugin marketplace add trailofbits/skills` then `/plugin install variant-analysis`) — this Agent SDK session has no `claude` binary and cannot run `/plugin` commands. `fix-verification` does not exist in the real marketplace under that name — do not assume it does.
- `npx -y skills add DietrichGebert/ponytail --agent claude-code` — YAGNI decision-ladder skill. **Non-negotiable, no exception: intensity `full` (the default) only. Never `ultra`, ever, in this repo.** `ultra` questions the requirement in the same pass as the code and can classify a security control as over-engineering. This project **is**, fundamentally, security controls (immutability triggers ADR-002, RBAC, crypto-shredding ADR-004) — exactly what `ultra` tends to "simplify." Minimalism never outranks security-by-design.
- `npx claude-mem install` — persistent cross-session memory. Verified empirically against the real generated `~/.claude-mem/settings.json`: no `cloud-sync` key exists, because the tool has no such feature — not "confirmed off," there's nothing to turn off. The real data-egress point is different: session-observation compression calls out to an LLM API (`api.anthropic.com`, optionally Gemini) — expected behavior for an AI-powered memory tool, not a hidden backend, but real external egress worth naming precisely rather than reusing a "cloud-sync" framing the tool doesn't actually have.
- ECC (`affaan-m/ECC`) — **NOT installed.** The project's own documentation confirms unofficial mirrors/re-uploads may contain malware (independently verified, not just asserted) — official channels only: `github.com/affaan-m/ECC`, npm packages `ecc-universal`/`ecc-agentshield`, the GitHub App, plugin slug `ecc@ecc`, `ecc.tools`. **The npm package is `ecc-universal`, not a bare `ecc` — a package literally named `ecc` is not this project and installing it is exactly the typosquat risk this gate exists to catch.** Real Claude Code install path is `/plugin marketplace add` + `/plugin install ecc@ecc`, same structural blocker as Trail of Bits Skills in this environment.
- `pipx install strix-agent` — autonomous DAST/pentest agent. Installed, **never run** without separate, explicit authorization and the effects-neutralization protocol below confirmed for that specific run. The documented precedent is real: running `sqlmap` against a contact endpoint with real side effects once injected ~5000 garbage rows into a real PII table and delivered ~200 test emails to real inboxes. "It's just a test" is not harmless.

**What each test layer sees, and doesn't:**

| Layer | Sees | Blind to |
|---|---|---|
| Unit | The class under test, in isolation | Wiring, dependency injection, real I/O |
| Integration (Testcontainers) | Real PostgreSQL, real triggers, real transactions | The full HTTP stack, cross-service behavior |
| `@SpringBootTest` end-to-end | The real wired application, real filter chain order | Whether any *individual* test actually asserts anything meaningful |
| Mutation (PIT) | Whether the other layers' tests would actually catch a real defect | Correctness of a mutant that's semantically equivalent to the original (reported, not a bug) |

A SAST rule, a CI gate, or a security test that was never observed failing on purpose is not verified — it is declared. This is the same principle behind the Semgrep fixture discipline above (`.semgrep/fixtures/`, T-703) and behind every falsifiability probe recorded in `tasks/lessons.md` — it now applies to mutation testing and to any new tool's own claims about itself, not only to hand-written rules.

**Effects-neutralization protocol — blocking precondition before running anything that sends many requests against an endpoint with real side effects** (`sqlmap` for CV-01, `strix` in an offensive pass, contract fuzzing against the OpenAPI document):

1. Confirm sandbox, never production (T-309's guard already enforces this at the credential level — confirm it again before each offensive run anyway, don't assume yesterday's check still holds).
2. Confirm rate limits count the test traffic as part of the real quota — never assume a heavy tool and a concurrent test are isolated from each other just because they're "different tests."
3. Confirm a positive control — one case expected to succeed, run and green — **before** launching a battery that expects failures.

Not a hygiene nicety. It's the lesson behind a documented real incident: running `sqlmap` against a real-effects endpoint once injected ~5000 garbage rows into a real PII table and sent ~200 test emails to real inboxes.

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

**Extended gate (Q.8) — applies in addition to the above for any `CV-*` task, and any task in phases 3–6 (Factus, Verifactu, XRechnung, privacy/audit) going forward:**

- [ ] Suite green **and** at least one critical test verified by mutation (`tributary-domain`/`tributary-persistence`: `mvn org.pitest:pitest-maven:mutationCoverage`, threshold 60%)
- [ ] A context-load/startup test passes (a Spring context that fails to wire is a defect line coverage never sees)
- [ ] If the task touches authz: a correct-role-gets-access test **and** a wrong-role-gets-denied test, as two distinct tests — not one test asserting both, since a single test can pass for the wrong reason
- [ ] If the task uses a battery that expects failures: the positive control runs and is green first
- [ ] If the task runs anything offensive: effects neutralized per the protocol above, confirmed for that specific run
