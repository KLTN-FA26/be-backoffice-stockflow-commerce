-- Operator action AFTER preflight findings are reconciled and a backup is available.
-- Atomic: any invalid historical record aborts the transaction. No data is rewritten/deleted.
BEGIN;
ALTER TABLE procurement.supplier VALIDATE CONSTRAINT ck_supplier_profile;
ALTER TABLE procurement.supplier VALIDATE CONSTRAINT ck_supplier_delivery_contact;
ALTER TABLE procurement.purchase_order VALIDATE CONSTRAINT ck_po_response_evidence;
ALTER TABLE procurement.purchase_order VALIDATE CONSTRAINT ck_po_rejection_reason;
ALTER TABLE procurement.purchase_order VALIDATE CONSTRAINT ck_po_rejection_receipt;
COMMIT;
