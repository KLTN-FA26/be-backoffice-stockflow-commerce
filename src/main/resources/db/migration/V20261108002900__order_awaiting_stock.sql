-- =============================================================================
-- An order of a contract waits for stock (kltn-docs 17 §4.2 step 5, §4.3 steps 7 and 10, §4.4 step 15, §4.9 step 35,
-- §5.1, §5.2, BR-04, BR-11; team plan 4A-6, QD-16).
--
--   * ck_order_status: AWAITING_STOCK - its next delivery batch is short at its shipping warehouse and a production
--     order for that batch was sent to a producer (BR-04). IN_PRODUCTION stays, for the history rows that carry it.
--   * ordering.order_status_transition, the edges of 17 §5.1:
--       CONFIRMED -> AWAITING_STOCK         a production order for the short batch sent;
--       AWAITING_STOCK -> READY_TO_FULFILL  released with every carton held ("nhận đủ hàng"; READY_TO_FULFILL still
--                                            means released: ck_order_release_status);
--       AWAITING_STOCK -> PARTIALLY_FULFILLED  what it has handed over first (17 §4.4 step 15, a partial shipment);
--       AWAITING_STOCK -> ON_HOLD, ON_HOLD -> AWAITING_STOCK  held by Sales (§4.9 step 35) and back where it was;
--       AWAITING_STOCK -> CANCELLED.
--   * ck_order_hold_return: an order held from AWAITING_STOCK goes back to it.
--   * ck_order_awaiting_stock: only an order of a contract with a shipping warehouse waits for stock - the production
--     orders it waits for deliver there (17 BR-22). The application says it in Order.awaitStock.
--   * tg_order_printing_started: printing_started_at, once written, stays - it is when the first production order of
--     the order started mass printing (BR-11), and from it a cancellation may keep an agreed fee. Said a first time in
--     Order.recordPrintingStarted.
--
-- Status lists read and left as they are (every CHECK, index, view and function naming an order status was listed from
-- pg_constraint, pg_indexes, pg_views and pg_proc):
--   * ck_order_release_status (IN_PRODUCTION, READY_TO_FULFILL need a release): an order waiting for stock is not
--     released yet - its holds are by SKU at the warehouse, bins are chosen when it is released.
--   * ck_order_confirmed_basis (CONFIRMED): AWAITING_STOCK is entered from CONFIRMED only, and keeps its basis - the
--     signed contract, which ck_order_awaiting_stock requires.
--   * ck_order_deposit_before_production (IN_PRODUCTION): in-house production, gone.
--   * ck_order_contract_signed, ck_order_shipped_at, ck_order_delivered_at, ck_order_refunded_at, ck_order_closed_at,
--     ck_order_completed_at: name statuses before a contract or after a handover; nothing of AWAITING_STOCK.
--   * ordering.enforce_order_transition(): reads the table, and lets a hold go back only to status_before_hold - which
--     ck_order_hold_return now allows to be AWAITING_STOCK.
--   * No index, view or other function (inventory, payment, fulfillment) lists order statuses.
--
-- Nothing is back-filled: an order CONFIRMED with a production order already sent stays CONFIRMED, and is released
-- from there as before. Such an order cannot exist where this runs: production orders came with V20261108002700, which
-- ships together with this one, and no seed draws one up. No order is IN_PRODUCTION (V20261108000100).
-- =============================================================================

ALTER TABLE ordering.customer_order DROP CONSTRAINT ck_order_status;
ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_status CHECK (status IN
    ('DRAFT', 'PENDING_CONTRACT', 'PENDING_PAYMENT', 'CONFIRMED', 'AWAITING_STOCK', 'IN_PRODUCTION', 'READY_TO_FULFILL',
     'PICKING', 'PARTIALLY_FULFILLED', 'PACKED', 'ON_HOLD', 'SHIPPED', 'DELIVERY_FAILED', 'DELIVERED',
     'RETURN_REQUESTED', 'RETURNED', 'REFUNDED', 'PARTIALLY_REFUNDED', 'COMPLETED', 'CANCELLED', 'CLOSED'));

ALTER TABLE ordering.customer_order DROP CONSTRAINT ck_order_hold_return;
ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_hold_return CHECK (
    (status_before_hold IS NULL OR status = 'ON_HOLD')
    AND (status <> 'ON_HOLD' OR hold_reason = 'CREDIT' OR status_before_hold IS NOT NULL)
    AND (status_before_hold IS NULL OR status_before_hold IN ('CONFIRMED', 'AWAITING_STOCK', 'IN_PRODUCTION',
                                                              'READY_TO_FULFILL', 'PICKING', 'PARTIALLY_FULFILLED',
                                                              'PACKED')));

ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_awaiting_stock CHECK (status <> 'AWAITING_STOCK'
    OR (contract_signed_at IS NOT NULL AND shipping_warehouse_id IS NOT NULL));

INSERT INTO ordering.order_status_transition (from_status, to_status)
VALUES ('CONFIRMED', 'AWAITING_STOCK'),
       ('AWAITING_STOCK', 'READY_TO_FULFILL'),
       ('AWAITING_STOCK', 'PARTIALLY_FULFILLED'),
       ('AWAITING_STOCK', 'ON_HOLD'),
       ('AWAITING_STOCK', 'CANCELLED'),
       ('ON_HOLD', 'AWAITING_STOCK')
ON CONFLICT DO NOTHING;

-- BR-11: when printing started is a fact of the order's history, written once.
CREATE FUNCTION ordering.keep_printing_started() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.printing_started_at IS NOT NULL AND NEW.printing_started_at IS DISTINCT FROM OLD.printing_started_at THEN
        RAISE EXCEPTION 'Order %: printing started at %, and that stays (17 BR-11)', OLD.order_number,
            OLD.printing_started_at USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_order_printing_started
    BEFORE UPDATE OF printing_started_at ON ordering.customer_order
    FOR EACH ROW EXECUTE FUNCTION ordering.keep_printing_started();

COMMENT ON COLUMN ordering.customer_order.printing_started_at IS
    'When the first production order of the order entered In Production (17 BR-11): from here a cancellation is '
    'staff''s and may keep an agreed fee. Written once (tg_order_printing_started).';
