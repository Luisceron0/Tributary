-- T-201: BEFORE INSERT chain validation. ADR-002: the application computes the hash; this
-- function is what actually verifies it links correctly — the application's computation is a
-- proposal until this trigger accepts it.
--
-- Rules enforced, matching RF-003's flow exactly:
--   * The first record of a chain (no existing rows for this chain_id) must have previous_hash
--     NULL and sequence = 1.
--   * Every later record's previous_hash must equal the hash of an EXISTING row in the SAME
--     chain — not just "some 64 hex chars", the actual predecessor — and its sequence must be
--     exactly the predecessor's sequence + 1 (gapless, no skipping).
--
-- UNIQUE(chain_id, previous_hash) (V1) stops two rows from claiming the SAME predecessor; this
-- trigger stops a row from claiming a predecessor that does not exist, or exists at the wrong
-- position. Neither alone is the full invariant.

CREATE OR REPLACE FUNCTION fiscal_record_validate_chain_link()
RETURNS TRIGGER AS $$
DECLARE
    chain_has_rows BOOLEAN;
    predecessor    RECORD;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM fiscal_record WHERE chain_id = NEW.chain_id
    ) INTO chain_has_rows;

    IF NOT chain_has_rows THEN
        IF NEW.previous_hash IS NOT NULL THEN
            RAISE EXCEPTION
                'fiscal_record: chain % has no records yet; the first one must have previous_hash NULL, got %',
                NEW.chain_id, NEW.previous_hash;
        END IF;
        IF NEW.sequence <> 1 THEN
            RAISE EXCEPTION
                'fiscal_record: the first record of chain % must have sequence 1, got %',
                NEW.chain_id, NEW.sequence;
        END IF;
    ELSE
        IF NEW.previous_hash IS NULL THEN
            RAISE EXCEPTION
                'fiscal_record: chain % already has records; previous_hash must not be NULL',
                NEW.chain_id;
        END IF;

        SELECT * INTO predecessor
        FROM fiscal_record
        WHERE chain_id = NEW.chain_id AND hash = NEW.previous_hash;

        IF NOT FOUND THEN
            RAISE EXCEPTION
                'fiscal_record: previous_hash % does not match the hash of any existing record in chain %',
                NEW.previous_hash, NEW.chain_id;
        END IF;

        IF predecessor.sequence <> NEW.sequence - 1 THEN
            RAISE EXCEPTION
                'fiscal_record: sequence must be gapless — predecessor % is at sequence %, new record claims %',
                predecessor.id, predecessor.sequence, NEW.sequence;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER fiscal_record_before_insert_validate_chain
    BEFORE INSERT ON fiscal_record
    FOR EACH ROW
    EXECUTE FUNCTION fiscal_record_validate_chain_link();
