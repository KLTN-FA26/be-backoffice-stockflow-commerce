-- Actual completion evidence must not change when a buyer later records a supplier response.
-- Historical rows remain unknown unless an actual completed receipt exists; do not invent dates.
ALTER TABLE procurement.purchase_order ADD COLUMN receipt_completed_at TIMESTAMPTZ;
-- Preserve uncertainty: historical sent orders have no trustworthy send timestamp.
ALTER TABLE procurement.purchase_order ADD COLUMN legacy_sent_without_timestamp BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE procurement.purchase_order
SET supplier_confirmation_status = 'PENDING', legacy_sent_without_timestamp = TRUE
WHERE status IN ('SENT', 'PARTIALLY_RECEIVED', 'CLOSED', 'CLOSED_SHORT') AND sent_at IS NULL;
ALTER TABLE notification.delivery_log ALTER COLUMN recipient TYPE VARCHAR(500);
ALTER TABLE notification.delivery_log ADD COLUMN operation_reference VARCHAR(100);
UPDATE notification.delivery_log SET operation_reference = external_reference WHERE external_reference IS NOT NULL;
CREATE INDEX ix_delivery_log_operation ON notification.delivery_log(operation_reference, created_at);
ALTER TABLE procurement.supplier ADD CONSTRAINT ck_supplier_profile CHECK (
    code ~ '^[A-Za-z0-9._-]{1,64}$' AND length(btrim(name)) > 0
    AND (tax_code IS NULL OR tax_code ~ '^[0-9A-Za-z][0-9A-Za-z-]{6,30}[0-9A-Za-z]$' AND tax_code ~ '[0-9]')) NOT VALID;

CREATE FUNCTION procurement.capture_receipt_completion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.receipt_completed_at IS NOT NULL AND NEW.receipt_completed_at IS DISTINCT FROM OLD.receipt_completed_at THEN
        RAISE EXCEPTION 'Receipt completion evidence is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'CLOSED' AND NEW.status = 'CLOSED' AND NEW.receipt_completed_at IS NULL THEN
        RAISE EXCEPTION 'Receipt completion time is required' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER capture_receipt_completion BEFORE UPDATE ON procurement.purchase_order
    FOR EACH ROW EXECUTE FUNCTION procurement.capture_receipt_completion();

-- NOT VALID preserves historical rows; all new writes enforce these invariants.
ALTER TABLE procurement.supplier ADD CONSTRAINT ck_supplier_delivery_contact CHECK (
    (communication_channel = 'EMAIL' AND NULLIF(btrim(email), '') IS NOT NULL)
    OR (communication_channel = 'API' AND api_endpoint IS NOT NULL AND api_endpoint ~ '^https://[^/]+')) NOT VALID;
ALTER TABLE procurement.purchase_order ADD CONSTRAINT ck_po_response_evidence CHECK (
    supplier_confirmation_status NOT IN ('CONFIRMED', 'REJECTED')
    OR (supplier_responded_at IS NOT NULL AND (sent_at IS NOT NULL OR legacy_sent_without_timestamp))) NOT VALID;
ALTER TABLE procurement.purchase_order ADD CONSTRAINT ck_po_rejection_reason CHECK (
    supplier_confirmation_status <> 'REJECTED' OR NULLIF(btrim(supplier_response_note), '') IS NOT NULL) NOT VALID;
ALTER TABLE procurement.purchase_order ADD CONSTRAINT ck_po_rejection_receipt CHECK (
    supplier_confirmation_status <> 'REJECTED' OR status IN ('SENT', 'CANCELLED')) NOT VALID;

-- Clean databases should not retain unvalidated checks indefinitely. Preserve invalid historical
-- rows with a visible warning; the operator preflight/validation scripts handle reconciliation.
DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN SELECT conrelid::regclass AS relation, conname FROM pg_constraint
             WHERE connamespace = 'procurement'::regnamespace AND conname IN (
                 'ck_supplier_profile','ck_supplier_delivery_contact','ck_po_response_evidence',
                 'ck_po_rejection_reason','ck_po_rejection_receipt') AND NOT convalidated
    LOOP
        BEGIN
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I', c.relation, c.conname);
        EXCEPTION WHEN check_violation THEN
            RAISE WARNING 'Historical data requires reconciliation before validating %', c.conname;
        END;
    END LOOP;
END $$;
