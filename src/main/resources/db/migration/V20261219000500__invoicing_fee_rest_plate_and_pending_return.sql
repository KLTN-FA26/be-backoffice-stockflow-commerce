-- =============================================================================
-- WF-05 BE-62 (decision Q-19) and BE-28.
--
-- BE-62. An order whose goods all came back (the carrier returned every shipment, or lost it) keeps its plate fee -
-- the plates were made, the service was done (NĐ 254/2026 Điều 9 khoản 2) - but no delivery's invoice ever carried
-- it: only the first delivery's invoice takes a plate fee, and the invoice of the order's rest took the shipping fee
-- only. The money was collected with no invoice for it. The rest is now drawn up with every plate fee no invoice
-- carries (EInvoiceDrafts.drawUpShippingFeeRest; the order's deliveries over: OrderRemainderClosed, or
-- ShipmentReturnSettled with nothingMoreComing). The line guard lets a plate fee onto such an invoice; goods still not.
--
-- BE-28. A return used to be taken off whichever issued invoice of the order held the goods when its own shipment's
-- invoice was not issued yet - another delivery, often another tax period. It now waits for its own: the pieces are
-- kept here and taken off when that invoice is issued (PendingReturnAdjustmentListener), and the accountants are told.
-- =============================================================================

CREATE TABLE invoicing.einvoice_pending_return
(
    return_request_id UUID         NOT NULL,
    shipment_id       UUID         NOT NULL,
    order_line_id     UUID         NOT NULL,
    order_id          UUID         NOT NULL,
    rma_number        VARCHAR(64)  NOT NULL,
    order_number      VARCHAR(64)  NOT NULL,
    pieces            BIGINT       NOT NULL,
    kept_at           TIMESTAMPTZ  NOT NULL,
    settled_at        TIMESTAMPTZ,

    CONSTRAINT pk_einvoice_pending_return PRIMARY KEY (return_request_id, shipment_id, order_line_id),
    CONSTRAINT fk_einvoice_pending_return_shipment FOREIGN KEY (shipment_id) REFERENCES ordering.order_shipment (id),
    CONSTRAINT fk_einvoice_pending_return_order FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id),
    CONSTRAINT fk_einvoice_pending_return_request FOREIGN KEY (return_request_id) REFERENCES ordering.return_request (id),
    CONSTRAINT fk_einvoice_pending_return_order_line FOREIGN KEY (order_line_id) REFERENCES ordering.order_line (id),
    CONSTRAINT ck_einvoice_pending_return_pieces CHECK (pieces > 0)
);

CREATE INDEX ix_einvoice_pending_return_open ON invoicing.einvoice_pending_return (shipment_id)
    WHERE settled_at IS NULL;

COMMENT ON TABLE invoicing.einvoice_pending_return IS
    'WF-05 BE-28 (HĐ-4): pieces of a return whose own shipment''s invoice was not issued yet when the refund was approved; taken off that invoice once it is issued, never off another shipment''s.';

-- As V20261202000210, and: an original without a shipment may carry plate fees too (BE-62).
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
        -- Điều 10 khoản 6 điểm c); one for a return, a trade discount or a failed delivery only reduces.
        IF invoice_type IN ('ORIGINAL', 'REPLACEMENT') AND NEW.quantity < 0 THEN
            RAISE EXCEPTION 'an original or replacement e-invoice has no line below zero'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_sign';
        END IF;
        IF invoice_basis IN ('RETURN', 'TRADE_DISCOUNT', 'DELIVERY_FAILED') AND (NEW.quantity > 0 OR NEW.kind = 'NOTE') THEN
            RAISE EXCEPTION 'an adjustment for a return, a trade discount or a failed delivery only reduces'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_sign';
        END IF;
        IF NEW.kind = 'NOTE' AND invoice_type <> 'ADJUSTMENT' THEN
            RAISE EXCEPTION 'only an adjustment has a note line'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_line_note';
        END IF;
        -- An original without a shipment invoices the rest of the order's fees: the shipping fee no delivery
        -- carried, and a plate fee none did (BE-62) - never goods, which are always a shipment's.
        IF invoice_type = 'ORIGINAL' AND invoice_shipment IS NULL
           AND (NEW.kind <> 'FEE' OR NEW.fee_kind NOT IN ('SHIPPING_FEE', 'PLATE_FEE')) THEN
            RAISE EXCEPTION 'an e-invoice of an order''s fee rest carries fees only'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_einvoice_shipping_fee_rest_lines';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;
