-- T-601 / ADR-004: buyer.name/address/email/phone move under AES-256-GCM, one key per buyer
-- (subject_key, V6). V1's own comment on the buyer table named this exact migration in advance:
-- "name/address/email/phone are added encrypted by phase 6 (ADR-004, T-601)".
--
-- BYTEA, not TEXT: each column holds IV || ciphertext || tag (PiiCipher's own blob format), raw
-- bytes with no text encoding to preserve. address/email/phone are nullable — Buyer's own domain
-- fields are Optional, absent by default; name stays NOT NULL, since Buyer.name always is.
--
-- tax_identifier and country_code are untouched, still plain — V1's own reasoning still holds:
-- fiscal validity needs them in the clear.

ALTER TABLE buyer
    ADD COLUMN name_encrypted    BYTEA,
    ADD COLUMN address_encrypted BYTEA,
    ADD COLUMN email_encrypted   BYTEA,
    ADD COLUMN phone_encrypted   BYTEA;

-- SET NOT NULL below is safe with no backfill because this table has never held a production row
-- (SRS 10.5's gate on public exposure has not been crossed) — a real migration against live data
-- would need a genuine backfill pass first, encrypting each existing plain name before this point.
ALTER TABLE buyer
    ALTER COLUMN name_encrypted SET NOT NULL,
    DROP COLUMN name;

COMMENT ON COLUMN buyer.name_encrypted IS 'AES-256-GCM, IV || ciphertext || tag (PiiCipher). Key: subject_key WHERE subject_id = buyer.id.';
COMMENT ON COLUMN buyer.address_encrypted IS 'Same scheme as name_encrypted. NULL means Buyer.address was Optional.empty(), not "encryption failed".';
COMMENT ON COLUMN buyer.email_encrypted IS 'Same scheme as name_encrypted.';
COMMENT ON COLUMN buyer.phone_encrypted IS 'Same scheme as name_encrypted.';
