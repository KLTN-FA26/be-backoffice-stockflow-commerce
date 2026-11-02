-- =============================================================================
-- SCRUM-491 / issue 206: a rejected transfer order keeps why it was rejected (kltn-docs 10 §5).
--
-- 10 §5: Pending Approval -> Draft is a rejection, and the transfer goes back "for the planner to change". The service
-- required a reason and only logged it, so the planner who opened the DRAFT could not see why. Like cancel_reason, the
-- transfer's last rejection - the reason, who rejected and when - is kept on the order and shown on its detail. A later
-- rejection replaces it: the order carries its last one. Not part of what tg_transfer_order_draft_header guards.
-- =============================================================================

ALTER TABLE inventory.transfer_order
    ADD COLUMN rejected_by      UUID,
    ADD COLUMN rejected_at      TIMESTAMPTZ,
    ADD COLUMN rejection_reason VARCHAR(500),
    ADD CONSTRAINT fk_transfer_order_rejected_by FOREIGN KEY (rejected_by) REFERENCES identity.app_user (id),
    -- A rejection is all three or none, and it says why.
    ADD CONSTRAINT ck_transfer_order_rejection CHECK (
        (rejected_by IS NULL AND rejected_at IS NULL AND rejection_reason IS NULL)
        OR (rejected_by IS NOT NULL AND rejected_at IS NOT NULL AND NULLIF(btrim(rejection_reason), '') IS NOT NULL));
