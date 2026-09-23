-- Actual completion evidence must not change when a buyer later records a supplier response.
-- Historical rows remain unknown unless an actual completed receipt exists; do not invent dates.
ALTER TABLE procurement.purchase_order ADD COLUMN receipt_completed_at TIMESTAMPTZ;
ALTER TABLE notification.delivery_log ALTER COLUMN recipient TYPE VARCHAR(500);
ALTER TABLE notification.delivery_log ADD COLUMN operation_reference VARCHAR(100);
UPDATE notification.delivery_log SET operation_reference = external_reference WHERE external_reference IS NOT NULL;
CREATE INDEX ix_delivery_log_operation ON notification.delivery_log(operation_reference, created_at);
ALTER TABLE procurement.supplier ADD CONSTRAINT ck_supplier_profile CHECK (
    code ~ '^[A-Za-z0-9._-]{1,64}$' AND length(btrim(name)) > 0
    AND (tax_code IS NULL OR tax_code ~ '^[0-9A-Za-z][0-9A-Za-z-]{6,30}[0-9A-Za-z]$' AND tax_code ~ '[0-9]')) NOT VALID;

CREATE FUNCTION procurement.capture_receipt_completion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'CLOSED' AND NEW.status = 'CLOSED' THEN
        NEW.receipt_completed_at := CURRENT_TIMESTAMP;
    ELSE
        NEW.receipt_completed_at := OLD.receipt_completed_at;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER capture_receipt_completion BEFORE UPDATE ON procurement.purchase_order
    FOR EACH ROW EXECUTE FUNCTION procurement.capture_receipt_completion();

-- NOT VALID preserves historical rows; all new writes enforce these invariants.
ALTER TABLE procurement.supplier ADD CONSTRAINT ck_supplier_delivery_contact CHECK (
    (communication_channel = 'EMAIL' AND NULLIF(btrim(email), '') IS NOT NULL)
    OR (communication_channel = 'API' AND api_endpoint ~ '^https://[^/]+')) NOT VALID;
ALTER TABLE procurement.purchase_order ADD CONSTRAINT ck_po_response_evidence CHECK (
    supplier_confirmation_status NOT IN ('CONFIRMED', 'REJECTED')
    OR (supplier_responded_at IS NOT NULL AND sent_at IS NOT NULL)) NOT VALID;
ALTER TABLE procurement.purchase_order ADD CONSTRAINT ck_po_rejection_reason CHECK (
    supplier_confirmation_status <> 'REJECTED' OR NULLIF(btrim(supplier_response_note), '') IS NOT NULL) NOT VALID;
ALTER TABLE procurement.purchase_order ADD CONSTRAINT ck_po_rejection_receipt CHECK (
    supplier_confirmation_status <> 'REJECTED' OR status IN ('SENT', 'CANCELLED')) NOT VALID;
