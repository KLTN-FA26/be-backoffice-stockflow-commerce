-- SCRUM-116 (WBS 3.2.4.3) — a cancel/close-short reason with nowhere to persist it is write-only
-- and immediately lost; same treatment product.product.rejection_reason got in SCRUM-57.

ALTER TABLE procurement.purchase_order
    ADD COLUMN cancellation_reason VARCHAR(1000),
    ADD COLUMN close_short_reason  VARCHAR(1000);
