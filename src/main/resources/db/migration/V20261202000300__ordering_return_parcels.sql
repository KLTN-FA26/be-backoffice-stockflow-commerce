-- WF-05 TH-3 (kltn-docs 17 §4.10 step 39; 03 §4.2): the goods of one return may come back in several parcels - four
-- cartons by truck on Monday, two by post on Thursday - and each is received on a return receipt of its own. Before
-- this a return request took exactly one receipt (uk_goods_receipts_return_request): the second parcel had no document,
-- its cartons sat in the warehouse off the books, and the customer was refunded for the first parcel only.
--
--  1. ordering.return_request_receipt: one row per confirmed return receipt that brought goods of a return (its
--     "parcel", numbered from 1). Append-only; keyed by the receipt, so a receipt is one parcel of one return, and the
--     order module recognises a receipt it has taken already (its event is delivered at least once). Checked like
--     return_request.goods_receipt_id: a CLOSED RETURN receipt against that very return.
--  2. procurement.goods_receipts: one DRAFT receipt per return request at a time, instead of one receipt not cancelled.
--     A receipt is opened only while the return still awaits its goods (check_return_receipt, unchanged).
--  3. ordering.return_request.goods_receipt_id stays as it was - the first receipt that brought goods, never changed -
--     and received_by / received_at / receiving_location_code follow the latest parcel. The units of each line
--     (return_request_line.received_quantity) are every parcel's together; the application moves the return to
--     RECEIVED once everything approved is back, when Sales mark the goods complete, or at the deadline with what came.

-- ---------------------------------------------------------------------------------------------------------- 1

CREATE TABLE ordering.return_request_receipt (
    goods_receipt_id        uuid                     NOT NULL,
    return_request_id       uuid                     NOT NULL,
    parcel                  integer                  NOT NULL,
    receipt_number          varchar(30)              NOT NULL,
    receiving_location_code varchar(64)              NOT NULL,
    received_by             uuid                     NOT NULL,
    received_at             timestamp with time zone NOT NULL,
    created_at              timestamp with time zone NOT NULL DEFAULT now(),
    created_by              varchar(100),
    CONSTRAINT pk_return_request_receipt PRIMARY KEY (goods_receipt_id),
    CONSTRAINT uk_return_request_receipt_parcel UNIQUE (return_request_id, parcel),
    CONSTRAINT ck_return_request_receipt_parcel CHECK (parcel > 0),
    CONSTRAINT fk_return_request_receipt_request FOREIGN KEY (return_request_id)
        REFERENCES ordering.return_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_return_request_receipt_receipt FOREIGN KEY (goods_receipt_id)
        REFERENCES procurement.goods_receipts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_return_request_receipt_received_by FOREIGN KEY (received_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_return_request_receipt_location FOREIGN KEY (receiving_location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT
);

COMMENT ON TABLE ordering.return_request_receipt IS 'WF-05 TH-3: a confirmed RETURN goods receipt that brought a parcel of a return''s goods back (kltn-docs 17 §4.10 step 39, 03 §4.2), numbered from 1. Append-only.';

CREATE FUNCTION ordering.check_return_request_receipt() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    rma varchar(32);
BEGIN
    IF NOT EXISTS (SELECT 1 FROM procurement.goods_receipts g
                    WHERE g.id = NEW.goods_receipt_id AND g.receipt_type = 'RETURN' AND g.status = 'CLOSED'
                      AND g.return_request_id = NEW.return_request_id) THEN
        SELECT r.rma_number INTO rma FROM ordering.return_request r WHERE r.id = NEW.return_request_id;
        RAISE EXCEPTION 'return %: a parcel comes back on a confirmed return receipt against it (03 §4.2)',
            COALESCE(rma, NEW.return_request_id::text) USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_return_request_receipt BEFORE INSERT ON ordering.return_request_receipt
    FOR EACH ROW EXECUTE FUNCTION ordering.check_return_request_receipt();

CREATE TRIGGER tg_return_request_receipt_append_only BEFORE DELETE OR UPDATE ON ordering.return_request_receipt
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('ordering.return_request', 'return_request_id');

-- The receipt each return named so far is its first parcel.
INSERT INTO ordering.return_request_receipt (goods_receipt_id, return_request_id, parcel, receipt_number,
                                             receiving_location_code, received_by, received_at)
SELECT r.goods_receipt_id, r.id, 1, g.receipt_number, r.receiving_location_code, r.received_by, r.received_at
  FROM ordering.return_request r
  JOIN procurement.goods_receipts g ON g.id = r.goods_receipt_id
 WHERE r.goods_receipt_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------- 2

DROP INDEX procurement.uk_goods_receipts_return_request;

CREATE UNIQUE INDEX uk_goods_receipts_return_request_draft ON procurement.goods_receipts (return_request_id)
    WHERE return_request_id IS NOT NULL AND status = 'DRAFT';

CREATE INDEX ix_goods_receipts_return_request ON procurement.goods_receipts (return_request_id)
    WHERE return_request_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------- 3

COMMENT ON COLUMN ordering.return_request.goods_receipt_id IS 'The first RETURN goods receipt that brought goods of the return back (team plan 4D-3, kltn-docs 03 §4.2); set once. Every receipt, the first included, is in ordering.return_request_receipt (WF-05 TH-3).';
