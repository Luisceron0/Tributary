-- T-600 / ADR-004: one AES-256 key per PII subject (buyer.id).
--
-- Deliberately NOT under the append-only trigger every other table in this schema gets (V3):
-- this is the one table that MUST support real deletion, because destroying a row here IS
-- crypto-shredding (RF-007) — the mechanism the whole design relies on to reconcile GDPR erasure
-- with a fiscal record that itself must never be deleted or edited.

CREATE TABLE subject_key (
    subject_id   UUID PRIMARY KEY REFERENCES buyer (id),
    key_material BYTEA NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE subject_key IS
    'ADR-004. One row per buyer with PII on file; DELETE this row to destroy the key (T-602). No
     UPDATE path is exposed by KeyVaultPort — a key is created once and only ever destroyed, never
     rotated in place, so there is nothing to accidentally overwrite.';

-- T-204's tributary_app role (V5) needs this table too — no UPDATE, matching KeyVaultPort's own
-- surface (create once, destroy, never modify in place).
GRANT SELECT, INSERT, DELETE ON subject_key TO tributary_app;
