-- =============================================================================
-- WF-05 audit r3 LM-2: the rest of an order's fees is decided again, not once.
--
-- Why. The rest invoice (an original naming no shipment: the shipping fee no delivery carried, and since BE-62 the
-- plate fees no delivery carried) was drawn up once per order, and uk_einvoice_shipping_fee_rest held it to one. The rest
-- is not settled once: a delivery corrected to a failure after it was issued takes its invoice - and the plate fee that
-- invoice carried - off the order (HĐ-3), and a carrier return after it waives the shipping fee the rest invoice
-- carries (Q-19: nothing delivered, the whole fee). The plate fee then stood on no invoice, the order waited for good on
-- a rest the draw-up would not draw up (it returned the rest invoice standing), and the rest invoice billed a fee
-- payment no longer collected. EInvoiceDrafts.drawUpShippingFeeRest now measures the rest again on every call: a plate
-- fee on no invoice goes on another rest invoice, and shipping fee waived since is taken off the issued one by a
-- reduction (correction_basis DELIVERY_FAILED).
--
-- What this does:
--   1. uk_einvoice_shipping_fee_rest: at most one rest invoice per order NOT ISSUED YET - a draft, a refused one or one
--      at the provider. Issued ones may be several; what they carry is counted under the order's invoicing lock
--      (invoicedOfOrder), as every delivery's share is. Two unissued ones would carry the same money twice, as before.
--   2. tg_einvoice_rest_plate_once: a plate fee goes on a rest invoice only while no other invoice of the order in force
--      - an original or a replacement, not voided, not replaced, not reduced to nothing for a failed delivery - carries
--      the plate fee of that SKU. The aggregate's rule (FeeAllocation.carriesPlateFee) stated again: with several rest
--      invoices allowed, the index no longer keeps a plate fee from being invoiced twice.
-- No row is touched: an order has at most one rest invoice today, and the trigger looks only at lines written after it.
-- =============================================================================

DROP INDEX invoicing.uk_einvoice_shipping_fee_rest;
CREATE UNIQUE INDEX uk_einvoice_shipping_fee_rest ON invoicing.einvoice (order_id)
    WHERE type = 'ORIGINAL' AND shipment_id IS NULL AND status IN ('DRAFT', 'SUBMITTING', 'REJECTED');

COMMENT ON INDEX invoicing.uk_einvoice_shipping_fee_rest IS
    'WF-05 (audit r3 LM-2): at most one invoice of an order''s fee rest not issued yet; issued ones may be several, the rest being decided again when a delivery is undone or a shipment comes back after it.';

CREATE OR REPLACE FUNCTION invoicing.einvoice_rest_plate_once() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    rest invoicing.einvoice%ROWTYPE;
BEGIN
    IF NEW.fee_kind IS DISTINCT FROM 'PLATE_FEE' THEN
        RETURN NEW;
    END IF;
    SELECT * INTO rest FROM invoicing.einvoice WHERE id = NEW.einvoice_id;
    IF rest.type <> 'ORIGINAL' OR rest.shipment_id IS NOT NULL THEN
        RETURN NEW;
    END IF;
    IF EXISTS (SELECT 1 FROM invoicing.einvoice i
                 JOIN invoicing.einvoice_line l ON l.einvoice_id = i.id
                WHERE i.order_id = rest.order_id AND i.id <> rest.id AND i.type IN ('ORIGINAL', 'REPLACEMENT')
                  AND i.status NOT IN ('VOIDED_DRAFT', 'REPLACED') AND i.reversed_at IS NULL
                  AND l.fee_kind = 'PLATE_FEE' AND l.sku = NEW.sku) THEN
        RAISE EXCEPTION 'the plate fee of % is already on an invoice of order %', NEW.sku, rest.order_number
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_rest_plate_once';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_einvoice_rest_plate_once
    BEFORE INSERT ON invoicing.einvoice_line
    FOR EACH ROW EXECUTE FUNCTION invoicing.einvoice_rest_plate_once();

COMMENT ON FUNCTION invoicing.einvoice_rest_plate_once() IS
    'WF-05 audit r3 LM-2 (BE-62, Q-19): a plate fee goes on a rest invoice only while no other invoice of the order in force carries the plate fee of that SKU.';
