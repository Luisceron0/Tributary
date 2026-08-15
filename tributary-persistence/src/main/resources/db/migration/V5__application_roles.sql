-- T-204: least-privilege application role. T-206: read-only verifier role for RF-006.
--
-- Both are NOLOGIN, no password: this file is version-controlled, and a credential literal in a
-- committed migration is exactly what SRS 5.3 and the pre-commit gitleaks hook (T-002) exist to
-- prevent, "local dev only" or not. The actual LOGIN role each connects as is provisioned outside
-- Flyway — docker-compose environment variables locally, the deployment's secret store otherwise
-- (T-705, phase 7) — and granted membership in one of these two group roles.
--
-- fiscal_record and audit_event get INSERT (the application appends to both) and SELECT, but
-- never UPDATE or DELETE. The REVOKE below is redundant given the GRANT never named them — kept
-- explicit anyway so the absence reads as a decision in a `\dp` listing, not an oversight to
-- "fix" later.

CREATE ROLE tributary_app NOLOGIN;
GRANT SELECT, INSERT, UPDATE ON issuer, buyer, invoice, invoice_line, issuance_attempt TO tributary_app;
GRANT SELECT, INSERT ON fiscal_record, audit_event TO tributary_app;
REVOKE UPDATE, DELETE ON fiscal_record, audit_event FROM tributary_app;

CREATE ROLE tributary_verifier NOLOGIN;
GRANT SELECT ON fiscal_record, invoice TO tributary_verifier;
