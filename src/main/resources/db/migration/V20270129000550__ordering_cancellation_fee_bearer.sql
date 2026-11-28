-- =============================================================================
-- "Bên chịu phí" (fee bearer): who pays the fee Sales keep when an order is cancelled after printing started, or when
-- the rest of a partly fulfilled order is closed (kltn-docs 17 §4.8 steps 30 and 33, 15 §4.6 steps 18-19; owner
-- decisions D46 and D50, 28/11). Modules: order, payment.
--
-- CUSTOMER is what happened before: the money collected pays the fee first, the rest is refunded, a shortfall is owed
-- on a CANCELLATION_FEE installment. COMPANY: the customer is not charged - everything collected is refundable as if
-- no fee had been kept, no fee item, no receivable - and the amount is recorded as a cost of the company for the
-- accountant. Sales choose; the field is required whenever a fee is entered and has no default (the application
-- refuses a fee without it, Order#cancel / Order#closeRemainder).
--
-- What changes:
--   * ordering.customer_order.cancellation_fee_bearer - for the cancellation fee, agreed amount or share alike (the
--     share is not on the order, only its bearer is). NULL when no fee was kept.
--   * ordering.order_remainder_closure.fee_amount / fee_bearer - the fee of a closure of the rest (nothing recorded it
--     on the order before; its settlement did). Both NULL when the closure kept none.
--   * payment.order_settlement.fee_bearer / company_fee_amount - the money's side. fee_amount stays what the CUSTOMER is
--     charged (0 for COMPANY); company_fee_amount is what the company bears (0 for CUSTOMER). The fee's terms (share,
--     reason, detail) are kept either way, so ck_order_settlement_fee_terms now reads "the fee entered" as
--     fee_amount + company_fee_amount.
--   * Every fee that exists is backfilled as CUSTOMER: until now the customer paid them all.
--   * Partial indexes for the accountant's order-list filter (feeBearer=COMPANY).
--
-- The bounds of the fee (above what the order is worth, before printing, above the goods closed) stay checked on
-- fee_amount by payment.check_order_settlement and by the aggregate for every bearer: the aggregate refuses them before
-- payment is asked, whoever bears the fee.
-- =============================================================================

-- ---- order: the cancellation fee ---------------------------------------------------------------------------------

ALTER TABLE ordering.customer_order ADD COLUMN cancellation_fee_bearer varchar(8);

-- A fee kept at cancellation: an agreed amount on the order, or a share on the settlement.
UPDATE ordering.customer_order o
   SET cancellation_fee_bearer = 'CUSTOMER'
 WHERE o.status = 'CANCELLED'
   AND (o.cancellation_fee_amount IS NOT NULL
        OR EXISTS (SELECT 1 FROM payment.order_settlement s
                    WHERE s.order_id = o.id AND s.kind = 'ORDER_CANCELLED' AND s.fee_percent IS NOT NULL));

ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_cancellation_fee_bearer CHECK (
    (cancellation_fee_bearer IS NULL
        OR (cancellation_fee_bearer IN ('CUSTOMER', 'COMPANY') AND status = 'CANCELLED'
            AND printing_started_at IS NOT NULL))
    AND (cancellation_fee_amount IS NULL OR cancellation_fee_bearer IS NOT NULL));

CREATE INDEX ix_customer_order_fee_company ON ordering.customer_order (id) WHERE cancellation_fee_bearer = 'COMPANY';

COMMENT ON COLUMN ordering.customer_order.cancellation_fee_bearer IS
    'Who bears the fee kept at cancellation (D46, D50): CUSTOMER is charged it; COMPANY bears it - the customer is '
    'refunded as if no fee was kept. Set with the fee, agreed amount or share; NULL when none was kept.';

-- ---- order: the fee of a closure of the rest ---------------------------------------------------------------------

ALTER TABLE ordering.order_remainder_closure ADD COLUMN fee_amount numeric(19, 4);
ALTER TABLE ordering.order_remainder_closure ADD COLUMN fee_bearer varchar(8);

-- The rows are append-only; this one backfill is the exception, from what the settlement of the closure recorded.
ALTER TABLE ordering.order_remainder_closure DISABLE TRIGGER tg_order_remainder_closure_append_only;
UPDATE ordering.order_remainder_closure c
   SET fee_amount = s.fee_amount, fee_bearer = 'CUSTOMER'
  FROM payment.order_settlement s
 WHERE s.kind = 'REMAINDER_CLOSED' AND s.source_id = c.closure_id AND s.fee_amount > 0;
ALTER TABLE ordering.order_remainder_closure ENABLE TRIGGER tg_order_remainder_closure_append_only;

ALTER TABLE ordering.order_remainder_closure ADD CONSTRAINT ck_order_remainder_closure_fee CHECK (
    (fee_amount IS NULL) = (fee_bearer IS NULL)
    AND (fee_amount IS NULL OR (fee_amount > 0 AND fee_bearer IN ('CUSTOMER', 'COMPANY'))));

CREATE INDEX ix_order_remainder_closure_fee_company ON ordering.order_remainder_closure (order_id)
    WHERE fee_bearer = 'COMPANY';

COMMENT ON COLUMN ordering.order_remainder_closure.fee_amount IS
    'The fee Sales kept with this closure (17 §4.8 step 33), as entered; NULL when none.';
COMMENT ON COLUMN ordering.order_remainder_closure.fee_bearer IS
    'Who bears that fee (D50): CUSTOMER is charged it, COMPANY bears it. NULL exactly when fee_amount is.';

-- ---- payment: the money's side -----------------------------------------------------------------------------------

ALTER TABLE payment.order_settlement ADD COLUMN fee_bearer varchar(8);
ALTER TABLE payment.order_settlement ADD COLUMN company_fee_amount numeric(19, 4) NOT NULL DEFAULT 0;

-- A settlement is kept as it was made (tg_order_settlement); this backfill states only who bore what already was.
ALTER TABLE payment.order_settlement DISABLE TRIGGER USER;
UPDATE payment.order_settlement SET fee_bearer = 'CUSTOMER' WHERE fee_percent IS NOT NULL OR fee_reason IS NOT NULL;
ALTER TABLE payment.order_settlement ENABLE TRIGGER USER;

ALTER TABLE payment.order_settlement DROP CONSTRAINT ck_order_settlement_fee_terms;
ALTER TABLE payment.order_settlement ADD CONSTRAINT ck_order_settlement_fee_terms CHECK (
    (fee_reason IS NULL) = (fee_detail IS NULL)
    AND NOT (fee_percent IS NOT NULL AND fee_reason IS NOT NULL)
    AND (fee_reason IS NULL OR (btrim(fee_reason) <> '' AND btrim(fee_detail) <> ''
                                AND fee_amount + company_fee_amount > 0))
    AND (fee_percent IS NULL OR (fee_percent > 0 AND fee_percent <= 100
                                 AND fee_amount + company_fee_amount = round(collected_amount * fee_percent / 100)))
    AND (fee_amount + company_fee_amount = 0 OR fee_percent IS NOT NULL OR fee_reason IS NOT NULL));

ALTER TABLE payment.order_settlement ADD CONSTRAINT ck_order_settlement_fee_bearer CHECK (
    (fee_bearer IS NULL) = (fee_percent IS NULL AND fee_reason IS NULL)
    AND (fee_bearer IS NULL OR fee_bearer IN ('CUSTOMER', 'COMPANY'))
    AND company_fee_amount >= 0 AND company_fee_amount = trunc(company_fee_amount)
    -- COMPANY: the customer is charged nothing, whatever was entered; the amount entered is the company's.
    AND (fee_bearer IS DISTINCT FROM 'COMPANY' OR fee_amount = 0)
    AND (fee_bearer IS NOT DISTINCT FROM 'COMPANY' OR company_fee_amount = 0)
    AND (fee_bearer IS DISTINCT FROM 'COMPANY' OR fee_percent IS NOT NULL OR company_fee_amount > 0));

COMMENT ON COLUMN payment.order_settlement.fee_bearer IS
    'Who bears the fee entered (D46, D50): CUSTOMER (fee_amount is charged, as always before) or COMPANY (the customer '
    'is charged nothing: company_fee_amount is the company''s cost). NULL exactly when no fee was entered.';
COMMENT ON COLUMN payment.order_settlement.company_fee_amount IS
    'The fee the company bears when fee_bearer = COMPANY, whole units, with the fee terms kept in fee_percent / '
    'fee_reason / fee_detail; 0 otherwise. Never charged, never owed, never in the receivables.';

CREATE INDEX ix_order_settlement_fee_company ON payment.order_settlement (order_id) WHERE fee_bearer = 'COMPANY';
