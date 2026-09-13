-- SCRUM-85 (WBS 3.1.8.1) — discontinuation, on the ONE product lifecycle (see SCRUM-72's own
-- description: this is not a second state machine). docs/business-design/03-state-machines.md
-- names the post-approval "orderable" state PUBLISHED, not ACTIVE - the starter stub's original
-- name never shipped in any reachable code path (canTransitionTo always answered false for it), so
-- renaming it now is a pure DDL/label change, not a data migration: no row has ever had this
-- status.

ALTER TABLE product.product DROP CONSTRAINT ck_product_status;

ALTER TABLE product.product
    ADD CONSTRAINT ck_product_status
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'PUBLISHED', 'DISCONTINUED'));
