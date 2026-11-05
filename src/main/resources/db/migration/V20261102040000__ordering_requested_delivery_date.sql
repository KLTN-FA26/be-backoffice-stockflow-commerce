-- =============================================================================
-- The customer's requested delivery date (SCRUM-502, owner decision PROD-DUE 2026-11-02). Module: ordering. Schema
-- owner review: Tu.
--
-- Until now an ORDER production order was created with due_date = null (Order.release passed no date), so the
-- workshop's overdue dashboard (19 §4.10) could never list one. The date the customer asks for is now recorded:
--   1. on the quote - Sales enters it with the offer while the quote is a draft (14 §4.1);
--   2. on each print line of the order the quote becomes, copied at conversion and then fixed (14 §4.3);
--   3. on the production order, which takes it from its order line when the order is released (19 §4.2).
-- Optional everywhere: no date means none was promised, as before. Orders placed and quotes drafted earlier keep
-- NULL; nothing is back-filled, because no earlier row recorded what the customer asked for.
-- =============================================================================

ALTER TABLE ordering.quote
    ADD COLUMN requested_delivery_date DATE;

ALTER TABLE ordering.order_line
    ADD COLUMN requested_delivery_date DATE,
    -- Only a print line is produced to a date; a stock line goes straight to picking.
    ADD CONSTRAINT ck_order_line_requested_delivery CHECK (
        requested_delivery_date IS NULL OR design_snapshot_id IS NOT NULL);

COMMENT ON COLUMN ordering.quote.requested_delivery_date IS
    'SCRUM-502: the date the customer asks to receive the goods; copied onto every print line of the order the quote becomes. Null = none asked.';
COMMENT ON COLUMN ordering.order_line.requested_delivery_date IS
    'SCRUM-502: the print line''s requested delivery date, from its quote; becomes the production order''s due_date when the order is released. Fixed once the order exists.';
