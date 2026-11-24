-- WF-05 BE-23 (audit R-8): a return's goods are never declared complete, nor its deadline run out, while a parcel of it
-- is still being counted at the dock. Before this, Sales could mark the goods complete (RECEIVED), have the refund
-- proposed and approved (RESOLVED), and the draft receipt confirmed afterwards added units a final return never
-- refunded; the expiry sweep could likewise EXPIRE a return whose parcel was on a draft, the order went back to
-- DELIVERED and could COMPLETE that night, and the draft's confirmation then reopened it.
--
-- The order module cannot read procurement's receipts (procurement depends on order :: api, not the reverse), so the
-- receipt tells the return: procurement calls ReturnReceiptTracking.draftOpened / draftClosed in the same transaction
-- as it opens, confirms or cancels the draft, and the return keeps the open draft's id here.
--
--  1. open_draft_receipt_id: the DRAFT return receipt counting a parcel of this return now; null when none. One at a
--     time (uk_goods_receipts_return_request_draft). Filled from the receipts drafted before this migration.
--  2. tg_return_request_draft_receipt: the receipt named is a DRAFT receipt against this return.
--  The receipt side - a receipt is not CLOSED while the return still names it as its open draft - is checked by
--  check_return_receipt_closed (V20261218000400).

ALTER TABLE ordering.return_request ADD COLUMN open_draft_receipt_id uuid;

ALTER TABLE ordering.return_request
    ADD CONSTRAINT fk_return_request_open_draft_receipt FOREIGN KEY (open_draft_receipt_id)
        REFERENCES procurement.goods_receipts (id) ON DELETE RESTRICT;

UPDATE ordering.return_request r
   SET open_draft_receipt_id = g.id
  FROM procurement.goods_receipts g
 WHERE g.return_request_id = r.id AND g.receipt_type = 'RETURN' AND g.status = 'DRAFT';

CREATE FUNCTION ordering.check_return_request_draft_receipt() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.open_draft_receipt_id IS NOT NULL
       AND NEW.open_draft_receipt_id IS DISTINCT FROM (CASE WHEN TG_OP = 'UPDATE' THEN OLD.open_draft_receipt_id END)
       AND NOT EXISTS (SELECT 1 FROM procurement.goods_receipts g
                        WHERE g.id = NEW.open_draft_receipt_id AND g.receipt_type = 'RETURN'
                          AND g.return_request_id = NEW.id AND g.status = 'DRAFT') THEN
        RAISE EXCEPTION 'return %: the receipt it is counted on is a DRAFT return receipt against it', NEW.rma_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_return_request_draft_receipt BEFORE INSERT OR UPDATE OF open_draft_receipt_id
    ON ordering.return_request
    FOR EACH ROW EXECUTE FUNCTION ordering.check_return_request_draft_receipt();

COMMENT ON COLUMN ordering.return_request.open_draft_receipt_id IS 'The DRAFT return receipt counting a parcel of this return now (WF-05 BE-23): while it is set the goods are not marked complete, the deadline does not expire the return, and no refund is proposed or approved. Kept by procurement through ReturnReceiptTracking in the receipt''s own transaction.';
