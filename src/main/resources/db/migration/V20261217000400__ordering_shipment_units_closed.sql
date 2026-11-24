-- =============================================================================
-- WF-05 decisions Q-7 and Q-12 (BE-14, BE-17, L-I8): no unit is closed and no money becomes refundable on a carrier's
-- word alone.
--
-- 1. ordering.order_shipment.units_closed_at: when the shipment's units were closed on their lines without being
--    delivered - the return receipt of a shipment the carrier brought back was confirmed (RETURNED), or Sales closed a
--    shipment the carrier lost (DELIVERY_FAILED, reason LOST). Null while the units are still the shipment's: with the
--    carrier, with the customer, or on their way back after a RETURNED report. Set once, never cleared; a shipment
--    whose units were closed is never DELIVERED on the order again.
--
--    Backfill: every RETURNED shipment existing now had its units closed when it was reported (RTM-RETURNED, #377) -
--    or, before #377, owed again on the order until Sales decided. Either way its units are not the shipment's any
--    more, so it is closed as of its outcome: Order.closeReturnedShipment then closes nothing again at its receipt,
--    and DeliveryFailureService.goodsReceived opens a decision case only when the order still owes its units (BE-16).
--
--    closed_goods_value / shipping_fee_waived: what the closed units were worth with VAT (Q-2), and the part of the
--    order's shipping fee payment waived with them (Q-19 as the owner settled it on 23/11: the shipment's share of the
--    order's goods; every shipment brought back waives the whole fee). Payment works the share out
--    (payment.order_settlement.shipping_fee_waived_amount); the order keeps the figure it answered, so that invoicing,
--    which does not read payment, invoices the fee less what was waived. Set with units_closed_at, never changed after;
--    the order's closures never waive more than its shipping fee. A backfilled row waived nothing and its value is
--    unknown (null).
--
-- 2. ordering.order_shipment_loss_closure: Sales' closure of a lost shipment (Q-12) - why, the carrier's confirmation
--    file, who and when; the key payment settled it under. One per shipment, append-only.
--
-- 3. ordering.delivery_failure_case_carton.quantity: what was counted inside the carton (Q-18, BE-20), not its label -
--    an opened carton that came back empty counts 0.
--
-- Stated twice: OrderShipment.closeUnits / delivered, Order.closeLostShipment, and here.
-- =============================================================================

ALTER TABLE ordering.order_shipment ADD COLUMN units_closed_at TIMESTAMPTZ;
ALTER TABLE ordering.order_shipment ADD COLUMN closed_goods_value NUMERIC(19, 4);
ALTER TABLE ordering.order_shipment ADD COLUMN shipping_fee_waived NUMERIC(19, 4) NOT NULL DEFAULT 0;

UPDATE ordering.order_shipment SET units_closed_at = outcome_at WHERE status = 'RETURNED';

-- Q-19 edge (owner, 23/11): after a carrier return, an order with nothing delivered waives its WHOLE shipping fee -
-- also when its rest was closed before it shipped. Whichever comes last ends the order so and waives what is left: the
-- shipment brought back (order_shipment.shipping_fee_waived) or the closure of the rest (here, written with the
-- closure's append-only row; 0 for every closure that waived nothing). Invoicing reads the order's total
-- (ShippingFeeWaivers.waivedOf = the shipments' + the closures').
ALTER TABLE ordering.order_remainder_closure ADD COLUMN shipping_fee_waived NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE ordering.order_remainder_closure ADD CONSTRAINT ck_order_remainder_closure_fee_waived CHECK (
    shipping_fee_waived >= 0 AND shipping_fee_waived = trunc(shipping_fee_waived));
COMMENT ON COLUMN ordering.order_remainder_closure.shipping_fee_waived IS
    'WF-05 Q-19 edge: the part of the order''s shipping fee (with VAT) payment waived with this closure - what the '
    'shipments brought back left of it, when the closure ends an order with nothing delivered; 0 otherwise.';

ALTER TABLE ordering.order_shipment ADD CONSTRAINT ck_order_shipment_units_closed CHECK (
    units_closed_at IS NULL OR status = 'RETURNED' OR (status = 'DELIVERY_FAILED' AND failure_reason = 'LOST'));
ALTER TABLE ordering.order_shipment ADD CONSTRAINT ck_order_shipment_closed_money CHECK (
    shipping_fee_waived >= 0 AND shipping_fee_waived = trunc(shipping_fee_waived)
    AND (closed_goods_value IS NULL OR closed_goods_value >= 0)
    AND (units_closed_at IS NOT NULL OR (closed_goods_value IS NULL AND shipping_fee_waived = 0)));

COMMENT ON COLUMN ordering.order_shipment.units_closed_at IS
    'WF-05 Q-7 / Q-12: when the shipment''s units were closed on their lines without being delivered - its return '
    'receipt confirmed, or closed as lost. Null while they are still the shipment''s. Set once.';
COMMENT ON COLUMN ordering.order_shipment.closed_goods_value IS
    'WF-05 Q-2: what the units closed at units_closed_at were worth with VAT; null while open or on a backfilled row.';
COMMENT ON COLUMN ordering.order_shipment.shipping_fee_waived IS
    'WF-05 Q-19: the part of the order''s shipping fee (with VAT) payment waived when the units were closed - the '
    'shipment''s share of the order''s goods; 0 otherwise. Set once.';

CREATE FUNCTION ordering.check_order_shipment_units_closed() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    -- The waiver is written once, after payment answered: the closure is flushed before payment is asked (its
    -- triggers read the shipments), so it may go from 0 to its amount once, never change after.
    IF OLD.units_closed_at IS NOT NULL AND (NEW.units_closed_at IS DISTINCT FROM OLD.units_closed_at
            OR NEW.closed_goods_value IS DISTINCT FROM OLD.closed_goods_value
            OR (NEW.shipping_fee_waived IS DISTINCT FROM OLD.shipping_fee_waived AND OLD.shipping_fee_waived <> 0))
            THEN
        RAISE EXCEPTION 'shipment % of order %: its units were closed at % and stay closed', OLD.id, OLD.order_id,
            OLD.units_closed_at USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.shipping_fee_waived > 0 AND NEW.units_closed_at IS NULL THEN
        RAISE EXCEPTION 'shipment % of order %: only closed units waive the shipping fee', OLD.id, OLD.order_id
            USING ERRCODE = 'check_violation';
    END IF;
    -- Q-19: the order's closures together never waive more than its shipping fee with VAT.
    IF NEW.shipping_fee_waived > 0 AND NEW.shipping_fee_waived
            + (SELECT COALESCE(SUM(s.shipping_fee_waived), 0) FROM ordering.order_shipment s
                WHERE s.order_id = NEW.order_id AND s.id <> NEW.id)
            + (SELECT COALESCE(SUM(c.shipping_fee_waived), 0) FROM ordering.order_remainder_closure c
                WHERE c.order_id = NEW.order_id)
            > (SELECT COALESCE(SUM(f.amount + f.tax_amount), 0) FROM ordering.order_fee_line f
                WHERE f.order_id = NEW.order_id AND f.kind = 'SHIPPING_FEE') THEN
        RAISE EXCEPTION 'order %: its shipments would waive more than its shipping fee', NEW.order_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_order_shipment_units_closed
    BEFORE UPDATE OF units_closed_at, closed_goods_value, shipping_fee_waived ON ordering.order_shipment
    FOR EACH ROW EXECUTE FUNCTION ordering.check_order_shipment_units_closed();

CREATE TABLE ordering.order_shipment_loss_closure (
    shipment_id      UUID          NOT NULL,
    order_id         UUID          NOT NULL,
    closure_id       UUID          NOT NULL,
    note             VARCHAR(1000) NOT NULL,
    evidence_key     VARCHAR(512)  NOT NULL,
    evidence_name    VARCHAR(255),
    closed_by        UUID          NOT NULL,
    closed_at        TIMESTAMPTZ   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_order_shipment_loss_closure PRIMARY KEY (shipment_id),
    CONSTRAINT uk_order_shipment_loss_closure_closure UNIQUE (closure_id),
    CONSTRAINT ck_order_shipment_loss_closure_note CHECK (btrim(note) <> ''),
    CONSTRAINT fk_order_shipment_loss_closure_shipment FOREIGN KEY (shipment_id)
        REFERENCES ordering.order_shipment (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_shipment_loss_closure_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_shipment_loss_closure_actor FOREIGN KEY (closed_by) REFERENCES identity.app_user (id)
);
CREATE INDEX ix_order_shipment_loss_closure_order ON ordering.order_shipment_loss_closure (order_id);

CREATE TRIGGER tg_order_shipment_loss_closure_append_only BEFORE DELETE OR UPDATE
    ON ordering.order_shipment_loss_closure
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('ordering.customer_order', 'order_id');

COMMENT ON TABLE ordering.order_shipment_loss_closure IS
    'WF-05 Q-12: Sales closed a shipment the carrier lost - its units closed, settled like a carrier return at this '
    'moment. The carrier''s confirmation is evidence_key (common storage). Append-only.';

ALTER TABLE ordering.delivery_failure_case_carton DROP CONSTRAINT ck_delivery_failure_case_carton_quantity;
ALTER TABLE ordering.delivery_failure_case_carton ADD CONSTRAINT ck_delivery_failure_case_carton_quantity CHECK (
    quantity >= 0);
COMMENT ON COLUMN ordering.delivery_failure_case_carton.quantity IS
    'WF-05 Q-18: the units counted inside the carton when it came back - fewer than its label when it was opened, 0 '
    'when empty.';
