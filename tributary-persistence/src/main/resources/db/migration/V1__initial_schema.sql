-- T-200: base schema per SRS 6.4.
--
-- Deliberate omissions, and why:
--
--   * buyer.name/address/email/phone are PLAIN here. AES-256-GCM encryption under a per-subject
--     key (T-601) needs the KeyVaultPort design, which is a phase 6 decision (ADR-004) this
--     migration must not pre-empt. Phase 6 ALTERs this table; it does not redesign it.
--
--   * No cufe/number columns on invoice. SRS T-203 names them, but that is CO-Factus vocabulary
--     leaking into the shared model — exactly what lesson L-002 and the ArchUnit lexeme rule
--     (T-105) exist to keep out of tributary-domain, and the same reasoning applies here: a
--     column named after one regime's artifact on a table every regime shares is ADR-001's
--     violation moved from Java into SQL. issuance_attempt.external_reference is the
--     regime-agnostic equivalent (mirrors IssuanceResult.externalReference from T-103). See
--     V4__invoice_issued_state_coherence_trigger.sql for how "ISSUED implies proof of issuance"
--     is actually enforced.
--
--   * subject_key does not exist yet — also phase 6 (ADR-004), added when crypto-shredding lands.

CREATE TABLE issuer (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL,
    tax_identifier  TEXT NOT NULL,
    country_code    CHAR(2) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE issuer IS 'BG-4 Seller. SRS 3: a single issuer, no multi-tenancy.';

CREATE TABLE buyer (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL,
    tax_identifier  TEXT,
    country_code    CHAR(2) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE buyer IS
    'BG-7 Buyer. tax_identifier and country_code are plain (fiscal validity needs them in the
     clear); name/address/email/phone are added encrypted by phase 6 (ADR-004, T-601).';

CREATE TABLE invoice (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_key                TEXT NOT NULL UNIQUE,
    state                       TEXT NOT NULL CHECK (
        state IN ('DRAFT', 'SUBMITTING', 'ISSUED', 'ISSUED_WITH_WARNINGS', 'REJECTED',
                  'NEEDS_RECONCILIATION', 'MANUAL_REVIEW')
    ),
    issuer_id                   UUID NOT NULL REFERENCES issuer (id),
    buyer_id                    UUID NOT NULL REFERENCES buyer (id),
    currency                    CHAR(3) NOT NULL,
    issue_date                  DATE NOT NULL,
    document_level_allowance    NUMERIC(19, 2) NOT NULL DEFAULT 0.00,
    sum_of_line_net_amounts     NUMERIC(19, 2) NOT NULL,
    tax_exclusive_amount        NUMERIC(19, 2) NOT NULL,
    tax_total                   NUMERIC(19, 2) NOT NULL,
    tax_inclusive_amount        NUMERIC(19, 2) NOT NULL,
    amount_due_for_payment      NUMERIC(19, 2) NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE invoice IS
    'The aggregate root (com.tributary.domain.Invoice). state mirrors DocumentState exactly
     (T-102); the values here are the ONLY source of truth for which strings are legal, not a
     duplicate to keep in sync by hand — ArchitectureTest/DocumentStateTest cannot see this file,
     so a state added in Java without a matching value here fails loudly at insert time instead of
     silently.';
COMMENT ON COLUMN invoice.business_key IS 'ADR-003: SHA-256(issuer.tax_identifier | saleId), hex.';

CREATE TABLE invoice_line (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id           UUID NOT NULL REFERENCES invoice (id) ON DELETE CASCADE,
    line_order           INT NOT NULL,
    line_identifier      TEXT NOT NULL,
    item_name            TEXT NOT NULL,
    quantity             NUMERIC(19, 6) NOT NULL CHECK (quantity >= 0),
    unit_code            TEXT NOT NULL,
    unit_price           NUMERIC(19, 2) NOT NULL,
    line_discount        NUMERIC(19, 2) NOT NULL DEFAULT 0.00 CHECK (line_discount >= 0),
    tax_category         TEXT NOT NULL CHECK (tax_category IN ('STANDARD', 'REVERSE_CHARGE')),
    tax_rate             NUMERIC(5, 2) NOT NULL CHECK (tax_rate >= 0 AND tax_rate <= 100),
    vat_exemption_reason TEXT,
    UNIQUE (invoice_id, line_order)
);
COMMENT ON TABLE invoice_line IS 'BG-25. line_order preserves read-back order (T-101 totals are order-sensitive for reproducibility).';

CREATE TABLE issuance_attempt (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id          UUID NOT NULL REFERENCES invoice (id),
    regime              TEXT NOT NULL CHECK (regime IN ('CO', 'ES', 'DE')),
    outcome             TEXT NOT NULL CHECK (
        outcome IN ('ACCEPTED', 'ACCEPTED_WITH_WARNINGS', 'REJECTED', 'UNREACHABLE')
    ),
    external_reference  TEXT,
    warnings            JSONB NOT NULL DEFAULT '[]'::JSONB,
    raw_response        TEXT NOT NULL,
    attempted_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE issuance_attempt IS
    'Trace of every attempt against an external regime (SRS 6.4). outcome/external_reference
     mirror application.port.IssuanceOutcome/IssuanceResult (T-103) — regime-agnostic on purpose.';

CREATE TABLE fiscal_record (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id          UUID NOT NULL REFERENCES invoice (id),
    regime              TEXT NOT NULL CHECK (regime IN ('CO', 'ES', 'DE')),
    record_type         TEXT NOT NULL CHECK (record_type IN ('ISSUANCE', 'CANCELLATION')),
    chain_id            UUID NOT NULL,
    sequence             BIGINT NOT NULL CHECK (sequence > 0),
    hash                CHAR(64) NOT NULL CHECK (hash ~ '^[0-9a-f]{64}$'),
    previous_hash       CHAR(64) CHECK (previous_hash IS NULL OR previous_hash ~ '^[0-9a-f]{64}$'),
    -- TEXT, not JSONB: JSONB reformats its input on storage (e.g. '{"n":1}' round-trips as
    -- '{"n": 1}', a space inserted after the colon) — silently different bytes than what was
    -- hashed at write time. That breaks RF-003's own acceptance criterion verbatim ("the hash is
    -- reproducible: recomputing it from the persisted data gives the same value") for anything
    -- that hashes over this column, which the whole table exists for. Found by exactly that
    -- failure while building the verifier (T-206) — every healthy chain came back BROKEN. TEXT
    -- stores the exact bytes handed to it; canonicalization (T-400) decides what those bytes are.
    canonical_payload   TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (chain_id, sequence),
    UNIQUE (chain_id, previous_hash)
);
COMMENT ON TABLE fiscal_record IS
    'ADR-002''s home. One row per regime + document that produces a chained record — in practice
     only ES/Verifactu today (RF-003; RD 1007/2023''s own terms are "registro de alta" /
     "registro de anulacion", kept in English here as ISSUANCE/CANCELLATION per SRS 5.5). CO''s
     proof of issuance is issuance_attempt, not a chain; DE has no persisted record at all
     (RF-005 hands the validated XML back to the caller). Immutable from V3 onward.';

-- Only one genesis (previous_hash IS NULL) row per chain. A plain UNIQUE(chain_id, previous_hash)
-- would NOT catch two genesis rows for the same chain: SQL treats every NULL as distinct from
-- every other NULL, so that constraint alone would silently allow multiple "first" records.
CREATE UNIQUE INDEX fiscal_record_one_genesis_per_chain
    ON fiscal_record (chain_id)
    WHERE previous_hash IS NULL;

CREATE TABLE audit_event (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor       TEXT NOT NULL,
    action      TEXT NOT NULL,
    entity      TEXT NOT NULL,
    result      TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE audit_event IS
    'SRS 6.4, append-only. actor always comes from the validated JWT subject, never the request
     body (T-603) — that wiring is phase 6; this migration only creates the immutable table T-204
     needs something to REVOKE UPDATE/DELETE on. Immutable from V3 onward.';
