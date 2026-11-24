-- WF-05 Q-11 (arbiter's decision, option a; TT 91/2026/TT-BTC Điều 10 khoản 5 điểm c; Luật Thương mại Điều 62; Luật
-- Kế toán Điều 40), BE-13 / BE-22: every unit that physically comes back for an approved claim can be put on a receipt,
-- and goods nobody agreed to take back are kept in custody on the return request instead of entering the warehouse.
--
--  1. check_return_receipt: a receipt against a return request is opened while the money of the return is not settled
--     - AWAITING_RETURN, RECEIVED, SETTLEMENT_PENDING, EXPIRED, or CANCELLED after it was approved - not only while it
--     awaits its goods. A parcel that arrives after Sales marked the goods complete, after the deadline, or after the
--     customer withdrew an approved claim used to have no document at all, its cartons left CONSUMED while they stood in
--     the warehouse. RESOLVED and REJECTED stay refused: such goods are held in custody (3.).
--  2. uk_goods_receipts_return_shipment allowed one non-cancelled receipt per carrier-returned shipment ever, so the
--     carrier's second trip with the rest of the shipment could never be received. It becomes one DRAFT at a time per
--     shipment, as for a return request (uk_goods_receipts_return_request_draft, V20261202000300); a carton is still
--     received once, since it is no longer CONSUMED afterwards (or is marked opened, V20261218000400).
--  3. ordering.return_request_evidence stage CUSTODY: the custody note for goods that came back but are not the
--     return's to take (another SKU, more than approved, sent after the claim was settled or refused): what (SKU or
--     LPN), how many, a photo, who took them (uploaded_by) and when (uploaded_at), on the return request concerned. No
--     stock entry, no refund, no invoice adjustment: the goods remain the customer's until they are sent back, a
--     supplementary claim takes them, or the customer agrees in writing that they are destroyed.

-- ---------------------------------------------------------------------------------------------------------- 1

CREATE OR REPLACE FUNCTION procurement.check_return_receipt() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    source_order     UUID;
    source_status    VARCHAR(32);
    source_warehouse UUID;
    source_decided   TIMESTAMPTZ;
    source_required  BOOLEAN;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.receipt_type IS DISTINCT FROM OLD.receipt_type
            OR NEW.customer_order_id IS DISTINCT FROM OLD.customer_order_id
            OR NEW.return_shipment_id IS DISTINCT FROM OLD.return_shipment_id
            OR NEW.return_request_id IS DISTINCT FROM OLD.return_request_id
            OR NEW.quarantine_location_id IS DISTINCT FROM OLD.quarantine_location_id THEN
            RAISE EXCEPTION 'return receipt %: what it is against and where its goods go do not change',
                OLD.receipt_number USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.return_shipment_id IS NOT NULL THEN
        SELECT s.order_id, s.status, s.warehouse_id INTO source_order, source_status, source_warehouse
          FROM fulfillment.shipment s WHERE s.id = NEW.return_shipment_id;
        IF NOT FOUND THEN
            RETURN NEW; -- fk_goods_receipts_return_shipment reports it
        END IF;
        IF source_status <> 'RETURNED' THEN
            RAISE EXCEPTION 'return receipt %: the shipment is %, not RETURNED (kltn-docs 09 BR-06)',
                NEW.receipt_number, source_status USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        SELECT r.order_id, r.status, o.warehouse_id, r.decided_at, r.return_required
          INTO source_order, source_status, source_warehouse, source_decided, source_required
          FROM ordering.return_request r JOIN ordering.customer_order o ON o.id = r.order_id
         WHERE r.id = NEW.return_request_id;
        IF NOT FOUND THEN
            RETURN NEW; -- fk_goods_receipts_return_request reports it
        END IF;
        -- ReturnRequest.receiptOpenable: approved, and its money not settled yet.
        IF NOT (source_status IN ('AWAITING_RETURN', 'RECEIVED', 'SETTLEMENT_PENDING', 'EXPIRED')
                OR (source_status = 'CANCELLED' AND source_decided IS NOT NULL AND source_required IS TRUE)) THEN
            RAISE EXCEPTION 'return receipt %: the return request is %; its goods are received while it is approved '
                'and its refund not settled (WF-05 Q-11)', NEW.receipt_number, source_status
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF source_order IS DISTINCT FROM NEW.customer_order_id THEN
        RAISE EXCEPTION 'return receipt %: its order is not the order of the shipment or return request',
            NEW.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    IF source_warehouse IS DISTINCT FROM NEW.warehouse_id THEN
        RAISE EXCEPTION 'return receipt %: goods come back to the warehouse they left from (kltn-docs 03 §4.2)',
            NEW.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM warehouse.storage_location s JOIN warehouse.area a ON a.location_id = s.id
                    WHERE s.id = NEW.quarantine_location_id AND s.warehouse_id = NEW.warehouse_id
                      AND a.type = 'QUARANTINE' AND s.status = 'ACTIVE' AND a.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'return receipt %: goods come back into an active QUARANTINE area of its warehouse (BR-14)',
            NEW.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------------------------------------- 2

DROP INDEX procurement.uk_goods_receipts_return_shipment;

CREATE UNIQUE INDEX uk_goods_receipts_return_shipment_draft ON procurement.goods_receipts (return_shipment_id)
    WHERE return_shipment_id IS NOT NULL AND status = 'DRAFT';

COMMENT ON INDEX procurement.uk_goods_receipts_return_shipment_draft IS 'One DRAFT return receipt per shipment the carrier brought back at a time (WF-05 Q-11, BE-13): the carrier may bring the shipment back in several trips, each received on a receipt of its own.';

-- ---------------------------------------------------------------------------------------------------------- 3

ALTER TABLE ordering.return_request_evidence DROP CONSTRAINT ck_return_request_evidence_stage;
ALTER TABLE ordering.return_request_evidence
    ADD CONSTRAINT ck_return_request_evidence_stage CHECK (stage IN ('CLAIM', 'RECEIPT', 'INSPECTION', 'CUSTODY'));
ALTER TABLE ordering.return_request_evidence DROP CONSTRAINT ck_return_request_evidence_claim_only;
ALTER TABLE ordering.return_request_evidence
    ADD CONSTRAINT ck_return_request_evidence_claim_only CHECK (stage IN ('CLAIM', 'CUSTODY'));

ALTER TABLE ordering.return_request_evidence ADD COLUMN custody_sku varchar(64);
ALTER TABLE ordering.return_request_evidence ADD COLUMN custody_lpn varchar(40);
ALTER TABLE ordering.return_request_evidence ADD COLUMN custody_quantity integer;
ALTER TABLE ordering.return_request_evidence ADD COLUMN custody_note varchar(1000);

ALTER TABLE ordering.return_request_evidence
    ADD CONSTRAINT ck_return_request_evidence_custody CHECK (
        CASE WHEN stage = 'CUSTODY'
             THEN custody_quantity IS NOT NULL AND custody_quantity > 0
                  AND (custody_sku IS NOT NULL OR custody_lpn IS NOT NULL)
                  AND (custody_sku IS NULL OR btrim(custody_sku) <> '')
                  AND (custody_lpn IS NULL OR btrim(custody_lpn) <> '')
                  AND (custody_note IS NULL OR btrim(custody_note) <> '')
             ELSE custody_sku IS NULL AND custody_lpn IS NULL AND custody_quantity IS NULL AND custody_note IS NULL
        END);

COMMENT ON COLUMN ordering.return_request_evidence.custody_quantity IS 'CUSTODY only (WF-05 Q-11): how many units of custody_sku / carton custody_lpn came back that the return does not take - kept for the customer, not in stock, not refunded. Who took them is uploaded_by, when is uploaded_at, the photo is file_key.';
