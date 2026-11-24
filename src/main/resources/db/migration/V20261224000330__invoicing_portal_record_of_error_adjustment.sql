-- =============================================================================
-- WF-05 audit r3 EINV-2, the ERROR half: the correction of an error of an original that no provider could send,
-- made on the provider's portal and recorded here once.
--
-- Why. Round 3 let /external-reductions record a return's reduction of an original whose provider can correct it no
-- more (QD Q-8: "Cùng một lệnh này dùng cho mọi ca SePay không làm được qua API"; HĐ-5). The same gap held for an ERROR
-- adjustment of such an original: drawn up and agreed in writing with the buyer, it could not be sent
-- (EINVOICE_ORIGINAL_PROVIDER_UNAVAILABLE), the accountant voided it and made it on the portal, and the system had no
-- record of it - the register still declared the original's full output VAT. A return is recorded once by its
-- return_request_id; an error has no such key, so the record names the voided draft it stands for.
--
-- What this does:
--   1. invoicing.einvoice_portal_record: one row per voided ERROR draft recorded from the portal, naming the adjustment
--      recorded for it. The primary key on the voided draft is the "once"; the unique recorded id, one draft per record.
--   2. tg_einvoice_portal_record: the voided draft is a VOIDED_DRAFT ERROR adjustment, the recorded one an ERROR
--      adjustment recorded from the portal (issued_via PORTAL_RECORD) of the same original, with the same written
--      agreement. Rows are never changed or removed.
--   3. tg_einvoice_error_portal_record (deferred): an ERROR adjustment recorded from the portal names its voided draft -
--      the aggregate's rule (EInvoice.recordedErrorReduction, from a voided draft only) stated again.
-- No row is touched: no ERROR adjustment was ever recorded from the portal before (EInvoice.recordedReduction refused it).
-- =============================================================================

CREATE TABLE invoicing.einvoice_portal_record (
    voided_draft_id      UUID        NOT NULL,
    recorded_einvoice_id UUID        NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_einvoice_portal_record PRIMARY KEY (voided_draft_id),
    CONSTRAINT uk_einvoice_portal_record_recorded UNIQUE (recorded_einvoice_id),
    CONSTRAINT fk_einvoice_portal_record_voided FOREIGN KEY (voided_draft_id) REFERENCES invoicing.einvoice (id),
    CONSTRAINT fk_einvoice_portal_record_recorded FOREIGN KEY (recorded_einvoice_id) REFERENCES invoicing.einvoice (id)
);

COMMENT ON TABLE invoicing.einvoice_portal_record IS
    'WF-05 audit r3 EINV-2: a voided ERROR adjustment no provider could send, and the adjustment recorded from the provider''s portal for it - once.';

CREATE FUNCTION invoicing.check_einvoice_portal_record() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    voided   invoicing.einvoice%ROWTYPE;
    recorded invoicing.einvoice%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'the portal record of e-invoice draft % is never changed or removed', OLD.voided_draft_id
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_portal_record';
    END IF;
    SELECT * INTO voided FROM invoicing.einvoice WHERE id = NEW.voided_draft_id;
    SELECT * INTO recorded FROM invoicing.einvoice WHERE id = NEW.recorded_einvoice_id;
    IF voided.type <> 'ADJUSTMENT' OR voided.correction_basis IS DISTINCT FROM 'ERROR'
       OR voided.status <> 'VOIDED_DRAFT'
       OR recorded.type <> 'ADJUSTMENT' OR recorded.correction_basis IS DISTINCT FROM 'ERROR'
       OR recorded.issued_via IS DISTINCT FROM 'PORTAL_RECORD'
       OR recorded.original_einvoice_id IS DISTINCT FROM voided.original_einvoice_id
       OR recorded.agreement_file_key IS DISTINCT FROM voided.agreement_file_key THEN
        RAISE EXCEPTION 'e-invoice % is not a record from the portal of the voided correction of an error %',
            recorded.code, voided.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_portal_record';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_einvoice_portal_record
    BEFORE INSERT OR UPDATE OR DELETE ON invoicing.einvoice_portal_record
    FOR EACH ROW EXECUTE FUNCTION invoicing.check_einvoice_portal_record();

CREATE FUNCTION invoicing.check_einvoice_error_portal_record() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.type = 'ADJUSTMENT' AND NEW.correction_basis = 'ERROR' AND NEW.issued_via = 'PORTAL_RECORD'
       AND NOT EXISTS (SELECT 1 FROM invoicing.einvoice_portal_record r WHERE r.recorded_einvoice_id = NEW.id) THEN
        RAISE EXCEPTION 'e-invoice % corrects an error from the provider''s portal without the voided draft it stands for',
            NEW.code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_error_portal_record';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_einvoice_error_portal_record
    AFTER INSERT OR UPDATE OF issued_via, correction_basis, status ON invoicing.einvoice
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION invoicing.check_einvoice_error_portal_record();

COMMENT ON FUNCTION invoicing.check_einvoice_error_portal_record() IS
    'WF-05 audit r3 EINV-2: an ERROR adjustment recorded from the provider''s portal names the voided draft it stands for (invoicing.einvoice_portal_record).';
