-- A purchase order whose total is within its submitter's own approval limit is approved at submission
-- (kltn-docs warehouse/02 §4 step 7 and §5: "Submit (dưới hạn mức)" goes DRAFT -> APPROVED; owner decision
-- POLIMIT2, SCRUM-114). Nobody else decides it, so the submitter is recorded as the approver, and
-- ck_purchase_orders_four_eyes (approved_by <> submitted_by) has to stand aside for exactly that case.
--
-- auto_approved is what it stands aside for: the flag can be TRUE only when approver and submitter are the
-- same person, and an order that is not auto-approved keeps the four-eyes rule untouched. The limit that was used,
-- the total and the reason ("auto-approved within limit") go on the approval row and the event timeline, which
-- are append-only (V20260928002400).

ALTER TABLE procurement.purchase_orders
    ADD COLUMN auto_approved BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE procurement.purchase_orders
    DROP CONSTRAINT ck_purchase_orders_four_eyes;

ALTER TABLE procurement.purchase_orders
    ADD CONSTRAINT ck_purchase_orders_four_eyes
        CHECK (auto_approved OR approved_by IS NULL OR submitted_by IS NULL OR approved_by <> submitted_by);

ALTER TABLE procurement.purchase_orders
    ADD CONSTRAINT ck_purchase_orders_auto_approved
        CHECK (NOT auto_approved OR (approved_by IS NOT NULL AND approved_by = submitted_by));

COMMENT ON COLUMN procurement.purchase_orders.auto_approved IS
    'Approved at submission because the total was within the submitter''s own approval limit (docs 02 §4 step 7); approved_by = submitted_by.';
