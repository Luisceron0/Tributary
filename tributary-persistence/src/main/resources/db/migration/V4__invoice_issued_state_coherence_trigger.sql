-- T-203: state coherence — an invoice cannot be ISSUED or ISSUED_WITH_WARNINGS without proof.
--
-- SRS names this "CHECK: ISSUED implies cufe IS NOT NULL AND number IS NOT NULL" — literally, a
-- same-row CHECK constraint. But cufe/number were deliberately kept off the invoice table (see
-- V1's header comment): they are CO-Factus vocabulary on a table every regime shares, the same
-- violation the domain-side lexeme rule (T-105) exists to catch. The proof of issuance lives in
-- issuance_attempt (regime-agnostic: outcome + external_reference), so the invariant this
-- migration actually enforces is: "a row transitioning to ISSUED/ISSUED_WITH_WARNINGS must have a
-- matching accepted issuance_attempt already recorded." A same-row CHECK cannot express a
-- cross-table condition — a trigger is the only mechanism that can, which is consistent with
-- ADR-002's own reasoning (rules that matter live where they cannot be bypassed).
--
-- This fixes the transaction order: the application must INSERT the issuance_attempt row BEFORE
-- UPDATEing invoice.state to ISSUED — which is exactly RF-002's own step order ("se persisten
-- number, cufe, validated_at, errors...; transicion a ISSUED").

CREATE OR REPLACE FUNCTION invoice_require_issuance_proof_for_issued_state()
RETURNS TRIGGER AS $$
DECLARE
    has_matching_attempt BOOLEAN;
BEGIN
    IF NEW.state IN ('ISSUED', 'ISSUED_WITH_WARNINGS') THEN
        SELECT EXISTS (
            SELECT 1 FROM issuance_attempt
            WHERE invoice_id = NEW.id
              AND outcome IN ('ACCEPTED', 'ACCEPTED_WITH_WARNINGS')
              AND external_reference IS NOT NULL
        ) INTO has_matching_attempt;

        IF NOT has_matching_attempt THEN
            RAISE EXCEPTION
                'invoice %: state % requires a prior issuance_attempt with outcome ACCEPTED(_WITH_WARNINGS) and a non-null external_reference',
                NEW.id, NEW.state;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER invoice_before_insert_check_issued_coherence
    BEFORE INSERT ON invoice
    FOR EACH ROW
    EXECUTE FUNCTION invoice_require_issuance_proof_for_issued_state();

CREATE TRIGGER invoice_before_update_check_issued_coherence
    BEFORE UPDATE ON invoice
    FOR EACH ROW
    EXECUTE FUNCTION invoice_require_issuance_proof_for_issued_state();
