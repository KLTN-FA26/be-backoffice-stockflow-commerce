-- WF-05 Q-19 edge, as the owner decided on 2026-11-23: after a carrier return, an order with no shipment delivered -
-- even when part of it was closed before it shipped - is a whole return (as Q-6 liquidates its contract) and waives the
-- WHOLE shipping fee; with at least one shipment delivered the share by goods value stays (V20261216000300) and the
-- closed part follows the closing rule (the fee lines stay owed, D30 / D32).
--
-- The domain (OrderSettlement.shippingFeeShare) waives what is left of the fee with whichever settlement ends the order
-- with nothing delivered: the last shipment brought back or lost (SHIPMENT_RETURNED), or - when the rest was closed
-- after a shipment came back - the closure of the rest (REMAINDER_CLOSED). That second kind could not waive before
-- (ck_order_settlement_shipment); stated again here:
--   * a closure of the rest may waive part of the shipping fee; it is part of closed_amount, below it - the goods closed
--     are worth more than zero - and the closure's agreed fee is bounded by those goods alone, not by the fee waived;
--   * every waiver of the order counts against its SHIPPING_FEE line with VAT, whatever the kind (V20261216000300 summed
--     only the shipments');
--   * a closure waives only to end the order as a whole return: after a shipment of the order was settled as brought
--     back or lost, with no shipment of the order DELIVERED or still HANDED_OVER, and bringing the order's waivers to
--     exactly the fee.
-- V20261216000300 is not edited: its function is replaced here, its trigger kept. No backfill: neither kind of waiver
-- is deployed yet.

ALTER TABLE payment.order_settlement DROP CONSTRAINT ck_order_settlement_shipment;
ALTER TABLE payment.order_settlement ADD CONSTRAINT ck_order_settlement_shipment CHECK (
    (kind = 'SHIPMENT_RETURNED') = (shipment_id IS NOT NULL)
    AND shipping_fee_waived_amount >= 0
    AND shipping_fee_waived_amount = trunc(shipping_fee_waived_amount)
    AND shipping_fee_waived_amount <= closed_amount
    AND (kind = 'SHIPMENT_RETURNED'
         OR (kind = 'REMAINDER_CLOSED' AND shipping_fee_waived_amount < closed_amount)
         OR shipping_fee_waived_amount = 0));

CREATE OR REPLACE FUNCTION payment.check_order_settlement_shipping_waiver() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    fee    NUMERIC;
    waived NUMERIC;
BEGIN
    -- 17 §4.8 step 33: a closure keeps an agreed fee for goods made, at most what the goods closed were worth - never
    -- the shipping fee waived beside them (check_order_settlement bounds it by closed_amount, which now holds both).
    IF NEW.kind = 'REMAINDER_CLOSED' AND NEW.fee_amount > NEW.closed_amount - NEW.shipping_fee_waived_amount THEN
        RAISE EXCEPTION 'settlement of order %: a fee of % is above the goods closed (17 §4.8 step 33)',
            NEW.order_number, NEW.fee_amount USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.shipping_fee_waived_amount = 0 THEN
        RETURN NEW;
    END IF;
    SELECT COALESCE(SUM(f.amount + f.tax_amount), 0) INTO fee
      FROM ordering.order_fee_line f
     WHERE f.order_id = NEW.order_id AND f.kind = 'SHIPPING_FEE';
    SELECT COALESCE(SUM(s.shipping_fee_waived_amount), 0) INTO waived
      FROM payment.order_settlement s
     WHERE s.order_id = NEW.order_id;
    IF waived + NEW.shipping_fee_waived_amount > fee THEN
        RAISE EXCEPTION 'settlement of order %: its settlements would waive % of a shipping fee of % (WF-05 Q-19)',
            NEW.order_number, waived + NEW.shipping_fee_waived_amount, fee USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.kind = 'REMAINDER_CLOSED' AND (
            waived + NEW.shipping_fee_waived_amount <> fee
            OR NOT EXISTS (SELECT 1 FROM payment.order_settlement s
                            WHERE s.order_id = NEW.order_id AND s.kind = 'SHIPMENT_RETURNED')
            OR EXISTS (SELECT 1 FROM ordering.order_shipment sh
                        WHERE sh.order_id = NEW.order_id AND sh.status IN ('DELIVERED', 'HANDED_OVER'))) THEN
        RAISE EXCEPTION 'settlement of order %: a closure of the rest waives the shipping fee only to end, after a '
            'carrier return, an order with nothing delivered - all that is left of it (WF-05 Q-19)', NEW.order_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN payment.order_settlement.shipping_fee_waived_amount IS
    'The part of closed_amount that is the order''s shipping fee waived, whole (WF-05 Q-19). SHIPMENT_RETURNED: the fee '
    'in proportion to the shipment''s goods of the order''s goods, or all that is left of it when the order ends with '
    'nothing delivered; REMAINDER_CLOSED: what the shipments brought back left of it, when the closure ends the order '
    'with nothing delivered (owner 2026-11-23); 0 otherwise. The order''s settlements never waive more between them '
    'than its shipping fee line with VAT (tg_order_settlement_shipping_waiver).';
