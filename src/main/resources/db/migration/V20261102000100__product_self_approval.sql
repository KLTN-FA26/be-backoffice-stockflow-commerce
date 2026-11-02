-- A SYSTEM_ADMIN may approve a product it submitted itself (owner decision of 02/11, BR-PRD-003 amended). Everyone
-- else still needs a second person, so ck_products_approver_not_submitter (approved_by <> submitted_by) has to stand
-- aside for exactly that case, the way ck_purchase_orders_four_eyes does for an auto-approved purchase order
-- (V20261028000100).
--
-- self_approved is what it stands aside for: the flag can be TRUE only when approver and submitter are the same
-- person, so a self-approval is visible on the row instead of being indistinguishable from a broken constraint.
-- Whether the approver held SYSTEM_ADMIN is decided by Product.approve at the time; roles change, the row does not.

ALTER TABLE product.products
    ADD COLUMN self_approved BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE product.products
    DROP CONSTRAINT ck_products_approver_not_submitter;

ALTER TABLE product.products
    ADD CONSTRAINT ck_products_approver_not_submitter
        CHECK (self_approved OR approved_by IS NULL OR submitted_by IS NULL OR approved_by <> submitted_by);

ALTER TABLE product.products
    ADD CONSTRAINT ck_products_self_approved
        CHECK (NOT self_approved OR (approved_by IS NOT NULL AND approved_by = submitted_by));

COMMENT ON COLUMN product.products.self_approved IS
    'Approved by the person who submitted it, which only a SYSTEM_ADMIN may do (BR-PRD-003); approved_by = submitted_by.';
