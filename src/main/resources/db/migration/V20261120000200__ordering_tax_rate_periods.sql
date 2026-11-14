-- =============================================================================
-- A VAT rate that holds for a period (WF-05 E2; plan §1.4 "thuế suất từng dòng ... có ngày hiệu lực, hỗ trợ đợt giảm
-- thuế GTGT"; kltn-docs open question D34).
--
-- ordering.tax_rate keeps one rate per product tax class: what a quote, an order and a contract fix on their lines
-- (14 BR-21). An e-invoice is drawn up when the goods are delivered, which may be months later, and the rate the law
-- sets for the day of delivery is the one the invoice must carry. A temporary reduction (the 10% -> 8% rounds) is a
-- rate with a first and a last day:
--
--   * ordering.tax_rate_period - for a tax class, a rate in force from valid_from to valid_to, both included. Two
--     periods of one class never overlap. Outside every period the class's rate in ordering.tax_rate holds.
--
-- Nothing is seeded: no reduction is in force for the goods sold here. Quotes, orders and contracts go on reading
-- ordering.tax_rate alone; only the e-invoice asks for the rate of a day.
-- =============================================================================

CREATE TABLE ordering.tax_rate_period
(
    id         UUID          NOT NULL,
    tax_class  VARCHAR(16)   NOT NULL,
    rate       NUMERIC(5, 2) NOT NULL,
    valid_from DATE          NOT NULL,
    valid_to   DATE          NOT NULL,
    note       VARCHAR(500),
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by VARCHAR(100),

    CONSTRAINT pk_tax_rate_period PRIMARY KEY (id),
    CONSTRAINT fk_tax_rate_period_class FOREIGN KEY (tax_class) REFERENCES ordering.tax_rate (tax_class),
    CONSTRAINT ck_tax_rate_period_rate CHECK (rate >= 0 AND rate < 100),
    CONSTRAINT ck_tax_rate_period_days CHECK (valid_from <= valid_to),
    -- One rate per class and day: which of two overlapping periods wins would be anybody's guess.
    CONSTRAINT ex_tax_rate_period_overlap EXCLUDE USING gist (
        tax_class WITH =, daterange(valid_from, valid_to, '[]') WITH &&)
);

COMMENT ON TABLE ordering.tax_rate_period IS
    'WF-05 E2: a VAT rate of a tax class in force for a period (a temporary reduction); outside every period '
    'ordering.tax_rate holds. Read by the e-invoice for the day of delivery.';
