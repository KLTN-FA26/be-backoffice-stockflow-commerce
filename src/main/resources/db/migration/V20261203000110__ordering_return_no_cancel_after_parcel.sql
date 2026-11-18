-- WF-05 TH-3, review defect: a return whose goods come back in several parcels stays AWAITING_RETURN after the first
-- one, and it could still be withdrawn then. The goods of that parcel stayed in quarantine, nothing was refunded, and
-- the order went back to DELIVERED and on to completion. Once a parcel is in, the return is settled on what came -
-- Sales mark the goods complete (POST /api/v1/returns/{id}/goods-complete), or the deadline does it - never withdrawn.
--
-- The aggregate refuses it first (ReturnRequest.cancel, RETURN_GOODS_PARTLY_RECEIVED); this is the same rule where a
-- manual UPDATE cannot talk its way past it. Only the move INTO CANCELLED is guarded: a parcel confirmed after a
-- withdrawal (its receipt opened while the return awaited it) still brings the return back to RECEIVED
-- (ReturnRequest.goodsReceived, RECEIVED_AFTER_WITHDRAWAL), and a return CANCELLED before this migration keeps its
-- status. return_request.goods_receipt_id is checked too: it names the first parcel, and V20261202000300 copied every
-- such receipt into return_request_receipt, so either one is enough - both are read in case one is ever written alone.

CREATE FUNCTION ordering.check_return_request_no_cancel_after_parcel() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.goods_receipt_id IS NOT NULL
       OR EXISTS (SELECT 1 FROM ordering.return_request_receipt p WHERE p.return_request_id = NEW.id) THEN
        RAISE EXCEPTION 'return %: goods of it are back, so it is not withdrawn - mark its goods complete (WF-05 TH-3)',
            NEW.rma_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

COMMENT ON FUNCTION ordering.check_return_request_no_cancel_after_parcel() IS 'WF-05 TH-3: a return any parcel of whose goods came back is not moved to CANCELLED (ReturnRequest.cancel, RETURN_GOODS_PARTLY_RECEIVED).';

CREATE TRIGGER tg_return_request_no_cancel_after_parcel BEFORE UPDATE OF status ON ordering.return_request
    FOR EACH ROW WHEN (NEW.status = 'CANCELLED' AND OLD.status IS DISTINCT FROM 'CANCELLED')
    EXECUTE FUNCTION ordering.check_return_request_no_cancel_after_parcel();
