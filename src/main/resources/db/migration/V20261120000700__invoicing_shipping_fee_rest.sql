-- =============================================================================
-- The rest of an order's shipping fee gets an invoice of its own when no delivery is left to carry it (WF-05 E2
-- follow-up; FeeAllocation, owner's decision 12/11/2026).
--
-- The shipping fee is invoiced delivery by delivery, and the delivery that completes the order takes what is left of
-- it. An order whose undelivered rest is closed after every one of its deliveries was invoiced has no such delivery:
-- the rest of the fee would be on no invoice at all, though it is owed and NĐ 254 Điều 9 requires every amount owed
-- for a service to be invoiced. The earlier invoices are not wrong - each carried its share of the pieces the order
-- then meant to deliver - so an adjustment (TT 91 Điều 10, for errors, returns and discounts) is not the instrument.
-- The rest goes on a new original that invoices no shipment and carries that one fee line only.
--
--   * ck_einvoice_original: an original names a shipment, or names none and carries the order's shipping fee only.
--   * uk_einvoice_shipping_fee_rest: at most one such invoice per order that was not voided.
--   * einvoice_line_guard: on an original that names no shipment, a line is the shipping fee and nothing else.
-- =============================================================================

ALTER TABLE invoicing.einvoice DROP CONSTRAINT ck_einvoice_original;
-- An original invoices a shipment, or (with no shipment) the rest of its order's shipping fee, and changes no invoice;
-- an adjustment or a replacement names the one it changes.
ALTER TABLE invoicing.einvoice ADD CONSTRAINT ck_einvoice_original CHECK (
    (type = 'ORIGINAL' AND original_einvoice_id IS NULL)
    OR (type <> 'ORIGINAL' AND original_einvoice_id IS NOT NULL AND original_einvoice_id <> id));

-- One invoice of an order's shipping fee rest that was not voided: the rest is decided under the order's invoicing
-- lock from what the order's invoices carry, so a second one would carry the same money twice.
CREATE UNIQUE INDEX uk_einvoice_shipping_fee_rest ON invoicing.einvoice (order_id)
    WHERE type = 'ORIGINAL' AND shipment_id IS NULL AND status <> 'VOIDED_DRAFT';

-- As V20261120000500, and: an original that names no shipment carries the shipping fee and nothing else.
CREATE OR REPLACE FUNCTION invoicing.einvoice_line_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    invoice_id       UUID;
    invoice_status   TEXT;
    invoice_type     TEXT;
    invoice_basis    TEXT;
    invoice_shipment UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        invoice_id := OLD.einvoice_id;
    ELSE
        invoice_id := NEW.einvoice_id;
    END IF;
    SELECT status, type, correction_basis, shipment_id INTO invoice_status, invoice_type, invoice_basis, invoice_shipment
      FROM invoicing.einvoice WHERE id = invoice_id FOR SHARE;
    IF invoice_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'the lines of an e-invoice change only while it is a draft (it is %)', invoice_status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_draft_only';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.einvoice_id <> OLD.einvoice_id THEN
        RAISE EXCEPTION 'a line stays on its e-invoice'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_draft_only';
    END IF;
    IF TG_OP <> 'DELETE' THEN
        -- An original, and a replacement, say what was delivered: nothing below zero. An adjustment may reduce (TT 91
        -- Điều 10 khoản 6 điểm c); one for a return or a trade discount only reduces.
        IF invoice_type IN ('ORIGINAL', 'REPLACEMENT') AND NEW.quantity < 0 THEN
            RAISE EXCEPTION 'an original or replacement e-invoice has no line below zero'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_sign';
        END IF;
        IF invoice_basis IN ('RETURN', 'TRADE_DISCOUNT') AND (NEW.quantity > 0 OR NEW.kind = 'NOTE') THEN
            RAISE EXCEPTION 'an adjustment for a return or a trade discount only reduces'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_sign';
        END IF;
        IF NEW.kind = 'NOTE' AND invoice_type <> 'ADJUSTMENT' THEN
            RAISE EXCEPTION 'only an adjustment has a note line'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_note';
        END IF;
        -- An original without a shipment invoices the rest of the order's shipping fee: no goods, no plate fee.
        IF invoice_type = 'ORIGINAL' AND invoice_shipment IS NULL
           AND (NEW.kind <> 'FEE' OR NEW.fee_kind IS DISTINCT FROM 'SHIPPING_FEE') THEN
            RAISE EXCEPTION 'an e-invoice of a shipping fee rest carries the shipping fee only'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_shipping_fee_rest_lines';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

COMMENT ON INDEX invoicing.uk_einvoice_shipping_fee_rest IS
    'WF-05: one invoice per order of the shipping fee its deliveries'' invoices did not carry, drawn up when its undelivered rest is closed.';
