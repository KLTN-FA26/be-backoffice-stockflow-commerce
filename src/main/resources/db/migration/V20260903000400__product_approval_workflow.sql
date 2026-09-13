-- SCRUM-57 (WBS 3.1.1.3): multi-level approval workflow, Draft -> Pending Approval -> Approved.
-- Stacked on SCRUM-56's V20260903000300 migration, which added the master-data fields this one
-- builds the workflow columns on top of.

ALTER TABLE product.product DROP CONSTRAINT ck_product_status;

ALTER TABLE product.product
    ADD COLUMN submitted_by     UUID,
    ADD COLUMN submitted_at     TIMESTAMPTZ,
    ADD COLUMN approved_by      UUID,
    ADD COLUMN approved_at      TIMESTAMPTZ,
    ADD COLUMN rejection_reason VARCHAR(1000);

ALTER TABLE product.product
    ADD CONSTRAINT ck_product_status
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'DISCONTINUED'));

-- BR-PRD-003, stated twice: Product.approve() checks it in code, this is the backstop a manual
-- UPDATE cannot talk past. NULLs are allowed through so a DRAFT/PENDING_APPROVAL row (neither
-- column set yet) never trips the constraint.
ALTER TABLE product.product
    ADD CONSTRAINT ck_product_approver_not_submitter
        CHECK (approved_by IS NULL OR submitted_by IS NULL OR approved_by <> submitted_by);
