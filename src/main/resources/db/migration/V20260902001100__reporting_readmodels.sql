-- =============================================================================
-- Reporting: read models / summaries built from the event stream.
-- Module: reporting
--
-- reporting-service builds these from events (OrderPlaced, InvoiceMatched, ...). It joins nothing
-- across schemas: every figure here is projected from events, not queried from another module.
-- =============================================================================

CREATE TABLE reporting.sales_daily_summary
(
    id               UUID          NOT NULL,
    day              DATE          NOT NULL,
    orders_count     INTEGER       NOT NULL DEFAULT 0,
    revenue          NUMERIC(18, 2) NOT NULL DEFAULT 0,
    currency         VARCHAR(3)    NOT NULL DEFAULT 'VND',

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_sales_daily_summary PRIMARY KEY (id),
    CONSTRAINT uk_sales_daily_summary_day UNIQUE (day)
);

CREATE TABLE reporting.product_sales_summary
(
    id               UUID          NOT NULL,
    sku              VARCHAR(64)   NOT NULL,
    -- Period key, e.g. '2026-09' for a month.
    period           VARCHAR(7)    NOT NULL,
    quantity_sold    INTEGER       NOT NULL DEFAULT 0,
    revenue          NUMERIC(18, 2) NOT NULL DEFAULT 0,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_product_sales_summary PRIMARY KEY (id),
    CONSTRAINT uk_product_sales_summary_sku_period UNIQUE (sku, period)
);
