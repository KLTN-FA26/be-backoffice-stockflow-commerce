-- WF-05 Q-19, as the owner decided on 2026-11-23: a shipment the carrier brought back or lost waives the share of the
-- order's shipping fee its goods are of the order's goods - one shipment of three equal ones a third of the fee, all of
-- them the whole fee (V20261216000100 waived the fee only when the order ended with nothing delivered). The share is
-- the domain's arithmetic (OrderSettlement.shippingFeeShare: worked out cumulatively over the order's shipments, half
-- up, so the waivers add up to the fee to the dong). Stated again here is its bound: the order's shipments never waive
-- more between them than its shipping fee line with VAT, and an order without one waives nothing. The arithmetic of
-- the share itself stays the domain's: the goods value the order module computes for a shipment is not re-derivable
-- from the order's rows without restating its VAT rounding, and a second copy of that would be one more thing to drift.
--
-- A trigger of its own beside tg_order_settlement, rather than a third copy of payment.check_order_settlement: it reads
-- the sibling rows of the order, which the order's row lock and the installments' lock (taken by every settlement)
-- keep still while it runs. No backfill: SHIPMENT_RETURNED is not deployed yet.

CREATE FUNCTION payment.check_order_settlement_shipping_waiver() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    fee    NUMERIC;
    waived NUMERIC;
BEGIN
    IF NEW.shipping_fee_waived_amount = 0 THEN
        RETURN NEW;
    END IF;
    SELECT COALESCE(SUM(f.amount + f.tax_amount), 0) INTO fee
      FROM ordering.order_fee_line f
     WHERE f.order_id = NEW.order_id AND f.kind = 'SHIPPING_FEE';
    SELECT COALESCE(SUM(s.shipping_fee_waived_amount), 0) INTO waived
      FROM payment.order_settlement s
     WHERE s.order_id = NEW.order_id AND s.kind = 'SHIPMENT_RETURNED';
    IF waived + NEW.shipping_fee_waived_amount > fee THEN
        RAISE EXCEPTION 'settlement of order %: the shipments brought back or lost would waive % of a shipping fee of % '
            '(WF-05 Q-19)', NEW.order_number, waived + NEW.shipping_fee_waived_amount, fee
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_order_settlement_shipping_waiver BEFORE INSERT ON payment.order_settlement
    FOR EACH ROW EXECUTE FUNCTION payment.check_order_settlement_shipping_waiver();

COMMENT ON COLUMN payment.order_settlement.shipping_fee_waived_amount IS
    'SHIPMENT_RETURNED: the part of closed_amount that is the share of the order''s shipping fee waived with the '
    'shipment - the fee in proportion to its goods of the order''s goods, whole (WF-05 Q-19); 0 otherwise. The order''s '
    'shipments never waive more between them than its shipping fee line with VAT (tg_order_settlement_shipping_waiver).';
