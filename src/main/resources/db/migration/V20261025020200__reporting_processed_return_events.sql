-- =============================================================================
-- SCRUM-243 / SCRUM-260: returns in the sales rollups (business review 2026-10-25).
-- Module: reporting
--
-- The rollups counted an order's revenue on the day it was placed, less the orders cancelled (V20261024002000). A
-- return that gave money back - refunded, set off against the receivables, kept as credit - left that revenue as it was,
-- and a free replacement order counted as one more order and more units sold. Now:
--   * a replacement order is not counted at all: its goods were sold, and counted, on the original order;
--   * the value a return credited back, and its credited units, come off the revenue of the day the paying order was
--     placed - as a cancellation does, so a day's figure is what was sold that day less what was cancelled or credited
--     back for it, and never goes below zero;
--   * withdrawing a replacement for credit takes the replaced units off the same way.
--
-- processed_return_events  one row per (return, event) already applied - RESOLVED, REPLACEMENT_WITHDRAWN - keeping what
--                          it took off. Kept through a rebuild of the day (only the order rows are recomputed from the
--                          orders), which applies them again: they are the record of the returns, as nothing in the
--                          order module's API lists them by day. No foreign key, as for processed_order_events: a
--                          projection outlives the rows it was computed from.
--
-- Schema owner (Tú): please review - written in the range reserved for the returns branch (V20261025020000-029900).
-- =============================================================================

CREATE TABLE reporting.processed_return_events
(
    return_request_id UUID           NOT NULL,
    event_type        VARCHAR(24)    NOT NULL,
    -- The order the goods were paid on, and the Asia/Ho_Chi_Minh day it was placed: where the value is taken off.
    order_id          UUID           NOT NULL,
    placed_day        DATE           NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    total             NUMERIC(18, 2) NOT NULL,
    -- [{"sku": "...", "quantity": 2, "revenue": 24000000.00}, ...]: the credited units, per SKU
    lines             JSONB          NOT NULL DEFAULT '[]'::jsonb,
    processed_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_processed_return_events PRIMARY KEY (return_request_id, event_type),
    CONSTRAINT ck_processed_return_events_type CHECK (event_type IN ('RESOLVED', 'REPLACEMENT_WITHDRAWN')),
    CONSTRAINT ck_processed_return_events_total CHECK (total > 0)
);

-- A rebuild of a day applies that day's return adjustments again.
CREATE INDEX ix_processed_return_events_placed_day ON reporting.processed_return_events (placed_day);
