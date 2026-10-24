-- =============================================================================
-- Reporting: the idempotency ledger behind the sales rollups (SCRUM-344).
-- Module: reporting
--
-- reporting.sales_daily_summary and reporting.product_sales_summary (V20260902001100) were created empty and
-- nothing wrote them. They are now maintained from OrderPlaced / OrderCancelled and can be rebuilt from the
-- order module's API. What counts (owner decisions): an order is revenue from the Asia/Ho_Chi_Minh day it was
-- placed on, ON_HOLD orders included (DASHHOLD), DRAFT orders never, and a cancelled order is taken out again on
-- the day it was PLACED - not the day it was cancelled. Single currency, VND.
--
-- processed_order_events    one row per (order, event) already applied, so a replayed event changes nothing.
--                           The PLACED row also keeps WHAT it added (day, total, per-SKU lines): the matching
--                           CANCELLED reverses exactly that, even if the order's lines were edited since.
--                           No foreign key to ordering.customer_order on purpose: this is a projection that
--                           must outlive the rows it was computed from; reporting calls no module's tables.
-- sales_daily_summary       unique on (day, currency) instead of (day), so the upsert key carries the currency.
-- product_sales_summary     "period" is the DAY, an ISO date ('2026-10-24'), one row per SKU per day. The column
--                           was VARCHAR(7) for a month key that nothing ever wrote; widened to 10.
-- =============================================================================

CREATE TABLE reporting.processed_order_events
(
    order_id     UUID           NOT NULL,
    event_type   VARCHAR(16)    NOT NULL,
    -- The Asia/Ho_Chi_Minh day the order was PLACED on, for both event types.
    placed_day   DATE           NOT NULL,
    currency     VARCHAR(3)     NOT NULL,
    total        NUMERIC(18, 2) NOT NULL,
    -- [{"sku": "...", "quantity": 2, "revenue": 24000000.00}, ...]
    lines        JSONB          NOT NULL DEFAULT '[]'::jsonb,
    processed_at TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_processed_order_events PRIMARY KEY (order_id, event_type),
    CONSTRAINT ck_processed_order_events_type CHECK (event_type IN ('PLACED', 'CANCELLED')),
    CONSTRAINT ck_processed_order_events_total CHECK (total >= 0)
);

-- The rebuild of a day deletes that day's rows; the startup backfill asks for the earliest day.
CREATE INDEX ix_processed_order_events_placed_day ON reporting.processed_order_events (placed_day);

ALTER TABLE reporting.sales_daily_summary
    DROP CONSTRAINT uk_sales_daily_summary_day,
    ADD CONSTRAINT uk_sales_daily_summary_day_currency UNIQUE (day, currency),
    ADD CONSTRAINT ck_sales_daily_summary_orders_count CHECK (orders_count >= 0),
    ADD CONSTRAINT ck_sales_daily_summary_revenue CHECK (revenue >= 0);

ALTER TABLE reporting.product_sales_summary
    ALTER COLUMN period TYPE VARCHAR(10),
    ADD CONSTRAINT ck_product_sales_summary_quantity CHECK (quantity_sold >= 0),
    ADD CONSTRAINT ck_product_sales_summary_revenue CHECK (revenue >= 0);

COMMENT ON COLUMN reporting.product_sales_summary.period IS
    'The day, ISO date (yyyy-MM-dd): one row per SKU per Asia/Ho_Chi_Minh day. Months are summed by the reader.';

-- The top-products and summary reads scan a range of days.
CREATE INDEX ix_product_sales_summary_period ON reporting.product_sales_summary (period);
