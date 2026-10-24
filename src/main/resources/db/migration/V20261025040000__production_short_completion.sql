-- =============================================================================
-- A production order completed short (review 2026-10-25, S6). Module: production. Schema owner review: Tu.
--
-- A job that cannot reach its plan - the blanks ran out, a reprint would come too late - is completed with the good
-- units QC passed, once the customer agreed with Sales to take them (recorded on the order line, V20261025040100):
-- the warehouse manager completes it short and says why (short_completion_reason). Only an order's in-house job,
-- between rounds, with at least one good unit; one with none is cancelled instead. In the same transaction the units
-- it will not make are closed out of the sales order line (ordering: the order is worth less, payment refunds what
-- was paid above that) and the line's blanks never issued are let go; the goods it made are handed over as on any
-- completion.
--
-- Data: a new nullable column; every existing COMPLETED row has good_quantity >= planned_quantity.
-- =============================================================================

ALTER TABLE production.production_order
    ADD COLUMN short_completion_reason VARCHAR(500),
    ADD CONSTRAINT ck_production_order_short_completion CHECK (short_completion_reason IS NULL
        OR (status = 'COMPLETED' AND type = 'ORDER' AND mode = 'IN_HOUSE'
            AND good_quantity > 0 AND good_quantity < planned_quantity
            AND length(trim(short_completion_reason)) > 0));

-- BR-PRD-06, restated: a completed job has its plan in good units, or says why it has fewer.
ALTER TABLE production.production_order DROP CONSTRAINT ck_production_order_completed_quantity;
ALTER TABLE production.production_order ADD CONSTRAINT ck_production_order_completed_quantity
    CHECK (status <> 'COMPLETED' OR good_quantity >= planned_quantity OR short_completion_reason IS NOT NULL);

COMMENT ON COLUMN production.production_order.short_completion_reason IS
    'Completed with fewer good units than planned, with the customer''s agreement (S6): why, as the manager recorded it. The shortfall was closed out of the sales order line.';
