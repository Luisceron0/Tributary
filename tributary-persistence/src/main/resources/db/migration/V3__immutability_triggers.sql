-- T-202 / CV-02: reject any UPDATE or DELETE on fiscal_record. Also applied to audit_event,
-- which needs the same append-only guarantee for the same reason (SRS 5.3's audit policy).
--
-- Covers DELETE too, even though ADR-002's own text names only UPDATE: the threat this defends
-- against (T-001, "alteracion de un registro fiscal por acceso directo a la base de datos") is
-- direct SQL access bypassing the application entirely, and a role-level REVOKE (T-204) only
-- restrains the application's OWN role — not a different credential connecting straight to
-- Postgres. A trigger fires regardless of which role issued the statement, unless that role can
-- also disable triggers (an elevated, auditable act — exactly what T-207's tamper-detection test
-- does deliberately, to prove RF-006's verifier catches what gets through anyway).

CREATE OR REPLACE FUNCTION reject_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        '% is immutable: % is not permitted on row % (%). A correction is a NEW row that references this one (RF-004), never an edit.',
        TG_TABLE_NAME, TG_OP, OLD.id, TG_TABLE_NAME;
    RETURN NULL; -- unreachable: RAISE EXCEPTION aborts the statement
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER fiscal_record_before_update_reject
    BEFORE UPDATE ON fiscal_record
    FOR EACH ROW
    EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER fiscal_record_before_delete_reject
    BEFORE DELETE ON fiscal_record
    FOR EACH ROW
    EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER audit_event_before_update_reject
    BEFORE UPDATE ON audit_event
    FOR EACH ROW
    EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER audit_event_before_delete_reject
    BEFORE DELETE ON audit_event
    FOR EACH ROW
    EXECUTE FUNCTION reject_mutation();
