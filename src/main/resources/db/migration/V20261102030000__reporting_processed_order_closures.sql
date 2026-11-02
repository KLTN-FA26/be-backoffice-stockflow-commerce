-- =============================================================================
-- Follow-up of #196 (S6), owner decision SHORT-LIMITS 2026-11-02. Module: reporting. Schema owner review: Tu.
--
-- The sales rollups counted an order at what it was placed for. When production completes a print line short, with the
-- customer's agreement, the order is worth less (OrderRemainderClosed, reason PRODUCTION_SHORTFALL): the closed value and
-- units now come off the revenue and the units of the day the order was placed, as a cancellation's and a return's do.
-- The order count stays - the sale happened - and a cancellation after a closure takes out only what is left.
--
-- processed_order_closures  one row per closure already applied, keeping what it took off: the order, the day it was
--                           placed on, the value and the units per SKU. Kept through a rebuild of the day (only the
--                           order rows are recomputed from the orders), which applies them again: nothing in the order
--                           module's API lists closures by day. A cancellation reads the closures of its order to take
--                           out only the rest. No foreign key, as for processed_order_events: a projection outlives the
--                           rows it was computed from.
--
-- Data: a new table. Closures made before this migration are not in it (the listener did not exist); S6 was merged on
-- 2026-11-02, so there are none in production.
-- =============================================================================

CREATE TABLE reporting.processed_order_closures
(
    closure_id   UUID           NOT NULL,
    order_id     UUID           NOT NULL,
    -- The Asia/Ho_Chi_Minh day the order was placed on: where the value is taken off.
    placed_day   DATE           NOT NULL,
    currency     VARCHAR(3)     NOT NULL,
    total        NUMERIC(18, 2) NOT NULL,
    -- [{"sku": "...", "quantity": 100, "revenue": 4000.00}, ...]: the closed units, per SKU
    lines        JSONB          NOT NULL DEFAULT '[]'::jsonb,
    processed_at TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_processed_order_closures PRIMARY KEY (closure_id),
    CONSTRAINT ck_processed_order_closures_total CHECK (total > 0)
);

-- A rebuild of a day applies that day's closures again; a cancellation reads its order's.
CREATE INDEX ix_processed_order_closures_placed_day ON reporting.processed_order_closures (placed_day);
CREATE INDEX ix_processed_order_closures_order ON reporting.processed_order_closures (order_id);
