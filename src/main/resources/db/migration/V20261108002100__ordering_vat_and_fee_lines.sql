-- =============================================================================
-- VAT on every line, the plate-fee and shipping-fee lines, and the three totals of a quote and an order (kltn-docs
-- 14 BR-09, BR-17, BR-20, BR-21; 17 §4.11, D33; QD-9; team plan 4B-3).
--
--   * ordering.tax_rate - the VAT rate of each tax class of a product (QD-9): STANDARD 10%, REDUCED 5%, EXEMPT 0%.
--     A line takes the rate of its product's class; Sales does not edit it (BR-21). A shipping fee, which names no
--     product, takes STANDARD (D31).
--   * ordering.quote_line.tax_class / tax_rate - copied onto a carton line when it is drafted, with its price (BR-06,
--     BR-21): null while the SKU's product has no tax class, and a quote is sent only once every line has its rate.
--   * ordering.quote_fee_line - at most one plate fee per SKU on the quote, for a SKU produced for the first time
--     (BR-17), and at most one shipping fee (BR-20); Sales enters the amount, whole VND. A plate fee the system added
--     waits for its amount (null) and the quote is not sent before it has one. Fee lines have no cartons: they count
--     towards no MOQ or tier, are held nowhere and go into no production order.
--   * ordering.order_line.tax_rate / tax_amount and ordering.order_fee_line - fixed when the order is placed from its
--     quote: tax = the line's total x its rate, rounded to the dong, line by line (BR-21).
--   * ordering.customer_order.tax_amount / net_amount - the VAT and the total before tax (goods and fees); total_amount
--     stays the total with tax, what the customer pays (BR-09). net_amount is generated as total_amount - tax_amount, so
--     the three cannot disagree and a row written with its total alone (an order placed before, a fixture, the seed)
--     carries no VAT: its net amount is its total, its tax 0.
--   * reporting: revenue is before tax and includes the fee lines (D33); a SKU's cartons are counted to rank it after
--     revenue. reporting.product_sales_summary.cartons_sold.
-- =============================================================================

-- VAT rates ---------------------------------------------------------------------------------------------------------
CREATE TABLE ordering.tax_rate
(
    tax_class        VARCHAR(16)  NOT NULL,
    rate             NUMERIC(5, 2) NOT NULL,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_tax_rate PRIMARY KEY (tax_class),
    CONSTRAINT ck_tax_rate_class CHECK (tax_class IN ('STANDARD', 'REDUCED', 'EXEMPT')),
    CONSTRAINT ck_tax_rate_rate CHECK (rate >= 0 AND rate < 100)
);

INSERT INTO ordering.tax_rate (tax_class, rate, created_by)
VALUES ('STANDARD', 10, 'flyway'), ('REDUCED', 5, 'flyway'), ('EXEMPT', 0, 'flyway');

COMMENT ON TABLE ordering.tax_rate IS
    '4B-3 (QD-9): the VAT rate of each product tax class; a quote line copies it with its price (14 BR-21).';

-- Quote lines and fees ---------------------------------------------------------------------------------------------
ALTER TABLE ordering.quote_line
    ADD COLUMN tax_class VARCHAR(16),
    ADD COLUMN tax_rate  NUMERIC(5, 2),
    ADD CONSTRAINT ck_quote_line_tax CHECK ((tax_class IS NULL) = (tax_rate IS NULL)
        AND (tax_class IS NULL OR (sku IS NOT NULL AND tax_class IN ('STANDARD', 'REDUCED', 'EXEMPT')
            AND tax_rate >= 0 AND tax_rate < 100)));

-- A carton line of a quote still being drafted takes its product's class and rate now, as a line drafted from here on
-- does; a quote already sent keeps the offer it made, without VAT (14 BR-06).
UPDATE ordering.quote_line l
   SET tax_class = p.tax_class, tax_rate = r.rate
  FROM ordering.quote q, product.variants v, product.products p, ordering.tax_rate r
 WHERE q.id = l.quote_id AND q.status = 'DRAFT' AND l.sku IS NOT NULL AND l.tax_class IS NULL
   AND v.sku = l.sku AND p.id = v.product_id AND r.tax_class = p.tax_class;

CREATE TABLE ordering.quote_fee_line
(
    id        UUID          NOT NULL,
    quote_id  UUID          NOT NULL,
    kind      VARCHAR(16)   NOT NULL,
    -- The SKU a plate fee is for; null on the shipping fee.
    sku       VARCHAR(64),
    amount    NUMERIC(19, 4),
    tax_class VARCHAR(16),
    tax_rate  NUMERIC(5, 2),

    CONSTRAINT pk_quote_fee_line PRIMARY KEY (id),
    CONSTRAINT fk_quote_fee_line_quote FOREIGN KEY (quote_id) REFERENCES ordering.quote (id) ON DELETE CASCADE,
    CONSTRAINT fk_quote_fee_line_sku FOREIGN KEY (sku) REFERENCES product.variants (sku) ON UPDATE CASCADE
        ON DELETE RESTRICT,
    CONSTRAINT ck_quote_fee_line_kind CHECK (kind IN ('PLATE_FEE', 'SHIPPING_FEE')),
    CONSTRAINT ck_quote_fee_line_sku CHECK ((kind = 'PLATE_FEE') = (sku IS NOT NULL)),
    -- A plate fee the system added waits for Sales' amount (null); a shipping fee is entered with one (BR-20).
    CONSTRAINT ck_quote_fee_line_amount CHECK ((kind = 'PLATE_FEE' OR amount IS NOT NULL)
        AND (amount IS NULL OR (amount >= 0 AND amount = TRUNC(amount) AND (kind = 'PLATE_FEE' OR amount > 0)))),
    CONSTRAINT ck_quote_fee_line_tax CHECK ((tax_class IS NULL) = (tax_rate IS NULL)
        AND (tax_class IS NULL OR (tax_class IN ('STANDARD', 'REDUCED', 'EXEMPT') AND tax_rate >= 0
            AND tax_rate < 100)))
);

CREATE INDEX ix_quote_fee_line_quote ON ordering.quote_fee_line (quote_id);
CREATE INDEX ix_quote_fee_line_sku ON ordering.quote_fee_line (sku) WHERE sku IS NOT NULL;
CREATE UNIQUE INDEX uk_quote_fee_line_plate ON ordering.quote_fee_line (quote_id, sku) WHERE kind = 'PLATE_FEE';
CREATE UNIQUE INDEX uk_quote_fee_line_shipping ON ordering.quote_fee_line (quote_id) WHERE kind = 'SHIPPING_FEE';

-- A plate fee is for a SKU on the same quote (14 BR-17), checked when the transaction commits: the application
-- replaces a draft's lines and fees in one go.
CREATE FUNCTION ordering.check_quote_plate_fee()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    the_quote UUID;
BEGIN
    the_quote := CASE WHEN TG_OP = 'DELETE' THEN OLD.quote_id ELSE NEW.quote_id END;
    IF EXISTS (SELECT 1 FROM ordering.quote_fee_line f
                WHERE f.quote_id = the_quote AND f.kind = 'PLATE_FEE'
                  AND NOT EXISTS (SELECT 1 FROM ordering.quote_line l WHERE l.quote_id = f.quote_id AND l.sku = f.sku))
    THEN
        RAISE EXCEPTION 'quote %: a plate fee is for a SKU on the quote (14 BR-17)', the_quote
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_quote_fee_line_plate
    AFTER INSERT OR UPDATE ON ordering.quote_fee_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ordering.check_quote_plate_fee();

CREATE CONSTRAINT TRIGGER tg_quote_line_plate_fee
    AFTER UPDATE OR DELETE ON ordering.quote_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ordering.check_quote_plate_fee();

-- Order lines and fees -------------------------------------------------------------------------------------------
ALTER TABLE ordering.order_line
    ADD COLUMN tax_rate   NUMERIC(5, 2),
    ADD COLUMN tax_amount NUMERIC(19, 4),
    ADD CONSTRAINT ck_order_line_tax CHECK ((tax_rate IS NULL) = (tax_amount IS NULL)
        AND (tax_rate IS NULL OR (tax_rate >= 0 AND tax_rate < 100
            AND tax_amount = ROUND(ROUND(quantity * unit_price, 0) * tax_rate / 100, 0))));

CREATE TABLE ordering.order_fee_line
(
    id         UUID          NOT NULL,
    order_id   UUID          NOT NULL,
    kind       VARCHAR(16)   NOT NULL,
    sku        VARCHAR(64),
    amount     NUMERIC(19, 4) NOT NULL,
    tax_rate   NUMERIC(5, 2) NOT NULL,
    tax_amount NUMERIC(19, 4) NOT NULL,
    currency   VARCHAR(3)    NOT NULL,

    CONSTRAINT pk_order_fee_line PRIMARY KEY (id),
    CONSTRAINT fk_order_fee_line_order FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_order_fee_line_sku FOREIGN KEY (sku) REFERENCES product.variants (sku) ON UPDATE CASCADE
        ON DELETE RESTRICT,
    CONSTRAINT ck_order_fee_line_kind CHECK (kind IN ('PLATE_FEE', 'SHIPPING_FEE')),
    CONSTRAINT ck_order_fee_line_sku CHECK ((kind = 'PLATE_FEE') = (sku IS NOT NULL)),
    CONSTRAINT ck_order_fee_line_amount CHECK (amount >= 0 AND amount = TRUNC(amount)
        AND (kind = 'PLATE_FEE' OR amount > 0)),
    CONSTRAINT ck_order_fee_line_tax CHECK (tax_rate >= 0 AND tax_rate < 100
        AND tax_amount = ROUND(amount * tax_rate / 100, 0))
);

CREATE INDEX ix_order_fee_line_order ON ordering.order_fee_line (order_id);
CREATE INDEX ix_order_fee_line_sku ON ordering.order_fee_line (sku) WHERE sku IS NOT NULL;
CREATE UNIQUE INDEX uk_order_fee_line_plate ON ordering.order_fee_line (order_id, sku) WHERE kind = 'PLATE_FEE';
CREATE UNIQUE INDEX uk_order_fee_line_shipping ON ordering.order_fee_line (order_id) WHERE kind = 'SHIPPING_FEE';

-- The order's totals: before tax, the tax, with tax ----------------------------------------------------------------
ALTER TABLE ordering.customer_order
    ADD COLUMN tax_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_order_totals CHECK (tax_amount >= 0 AND tax_amount = TRUNC(tax_amount)
        AND total_amount >= tax_amount);

ALTER TABLE ordering.customer_order
    ADD COLUMN net_amount NUMERIC(19, 4) GENERATED ALWAYS AS (total_amount - tax_amount) STORED;

COMMENT ON COLUMN ordering.customer_order.net_amount IS
    '4B-3: the total before tax - goods and fee lines (14 BR-09); generated as total_amount - tax_amount.';
COMMENT ON COLUMN ordering.customer_order.tax_amount IS
    '4B-3: the VAT of every line and fee line, each rounded to the dong (14 BR-21). 0 on orders placed before.';

-- The units closed out of an order are worth their share of their lines with tax since 4B-3 (Order.closedAmount).
COMMENT ON COLUMN ordering.customer_order.closed_amount IS
    'Value of the units closed out of the order (a closed remainder, a production shortfall), with their VAT: '
    'total_amount less what the order is worth now. "Paid in full" compares the money with total_amount - closed_amount.';

-- Reporting: cartons of each SKU -----------------------------------------------------------------------------------
ALTER TABLE reporting.product_sales_summary
    ADD COLUMN cartons_sold INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_product_sales_summary_cartons CHECK (cartons_sold >= 0);

COMMENT ON COLUMN reporting.product_sales_summary.cartons_sold IS
    '4B-3 (D33): cartons of the SKU on order lines placed by the carton, less cancelled orders; ranks a SKU after revenue.';
