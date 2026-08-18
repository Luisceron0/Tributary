# ADR-011: Deployment infrastructure is delivered complete and left undeployed

**Status:** Accepted — closes Milestone 2 without exercising it

## Context

[ADR-010](ADR-010-web-frontend-demo-mode.md) added a web frontend so the project could be shown,
not only read. The implied next step was a public deployment: a URL a reviewer could open.

Milestone 2's infrastructure was therefore built and verified in full — `docker-compose.prod.yml`,
the Caddy edge with automatic TLS, the systemd units, the scheduled demo reset, and the
`X-Forwarded-For` trust boundary that only means anything behind a real proxy. All of it runs; see
`docs/deployment.md` for what was verified locally and how.

The hosting assumption underneath it did not survive contact with the providers:

- **Oracle Cloud Always Free**, the tier the runbook was written against, halved its Ampere A1
  allowance from 4 OCPU / 24 GB to 2 OCPU / 12 GB in June 2026, with no announcement — users found
  out when instances were shut down. Separately, `Out of host capacity` on A1 shapes is a standing
  condition in many regions, not a transient one.
- **AWS** stopped offering 12 months of free EC2 to accounts created after 15 July 2025; new
  accounts get time-boxed credits instead. It is not a free host for an indefinitely-running demo.
- **Render, Railway and Koyeb** are PaaS, not VMs. None of them runs this `docker compose`
  topology as written — the fixed-subnet bridge network that makes the trusted-proxy pinning
  deterministic has no equivalent — and their free tiers sleep, expire, or no longer include
  compute at all. A demo that cold-starts for a minute, or stops in a fortnight, is worse for the
  stated purpose than no demo.
- **Google Cloud Always Free** remains genuinely indefinite, but its `e2-micro` is 1 GB of RAM
  shared by PostgreSQL, the JVM and Caddy. It would need heap and `shared_buffers` tuning to fit,
  and would then be demonstrating that tuning rather than the system.

The forcing question is what a public URL would actually add. The audience is a technical reviewer
who reads the repository. Everything the deployment would prove — the topology, the TLS
termination, the proxy trust boundary, the interface — is already evidenced in the repository as
committed configuration, captured screenshots, and executed verification output.

What a public URL adds beyond that is a live instance that can be down, degraded, rate-limited, or
serving stale demo data at the exact moment someone opens it, on infrastructure chosen for being
free rather than for being reliable, with no one on call.

## Decision

**Deliver the deployment infrastructure as a complete, verified artifact, and do not deploy it.**

The project is presented through its repository. `docs/deployment.md` stays a runbook rather than
becoming a historical note: it is written to be executed, by whoever chooses to, on any VM with
Docker and ports 80/443 open.

Nothing in `deploy/` or `docker-compose.prod.yml` is provider-specific. The Oracle steps live in
one clearly-marked section of the runbook precisely so that switching providers is an edit to that
section and nothing else.

**Milestone 2 closes here, with T-905 recorded as not executed rather than passed.** SRS §9B's
offensive protocol against a public instance is the one verification that structurally requires a
public instance; claiming it against `localhost` would be exactly the kind of assertion this
project has spent nine phases refusing to make.

## Consequences

- A reviewer cannot click a link and use the system. The screenshots in the README and the
  recorded verification output carry that weight instead, and they are honest about being
  recordings.
- The `429` path, the TLS configuration, and the proxy trust boundary are verified locally and in
  tests, never against the public internet. `docs/deployment.md` says so explicitly.
- The infrastructure decays untested from here. It was verified against a real `docker compose`
  run at the commit that introduced it; that is a point-in-time guarantee, not a standing one.
- Reversing this costs one VM and one runbook execution. That is the property worth protecting,
  and the reason the infrastructure was finished rather than abandoned half-built.

## Alternatives considered

**Deploy to Google Cloud's `e2-micro` anyway.** Rejected for now, not on feasibility — it would
work — but on cost/benefit: the tuning needed to fit three services into 1 GB is real work whose
only output is a demo URL, and it makes the deployed artifact differ from the one the repository
documents. Kept as the recorded fallback if a live instance is ever wanted.

**Deploy to a paid VPS (~5 USD/month).** Rejected as out of proportion to a portfolio artifact,
and it introduces an ongoing obligation — patching, certificate renewal, abuse handling — that
this project has no one to carry.

**Abandon the Milestone 2 infrastructure as unused work.** Rejected. It is the part of the project
that demonstrates the deployment reasoning: the trusted-proxy boundary, the same-origin design
that removes CORS rather than configuring it, the scheduled key rotation that bounds the damage of
a leaked demo token. Deleting it because it is not currently running would remove evidence of the
thinking, not dead weight.
