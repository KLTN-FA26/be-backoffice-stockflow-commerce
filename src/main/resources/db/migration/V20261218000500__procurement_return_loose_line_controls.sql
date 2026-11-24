-- WF-05 Q-18 add-on (decision [3/3]; TT 91/2026/TT-BTC Điều 10 khoản 5 điểm c.1; NĐ 254/2026/NĐ-CP Phụ lục điểm 5),
-- BE-43: units that come back with no carton label were one clerk's typed figure - no photo, no bound, no second person
-- - and counted as received for the return, so as refundable up to what was approved. With BE-21 that was the way to
-- refund the same goods twice.
--
--  1. photo_id: a photo of the loose units, one of the receipt's own photos (procurement.goods_receipt_photos, taken
--     while the receipt is a DRAFT). Required: NOT VALID, so loose lines recorded before keep their place, but every
--     line written from now on - a recount included - names one.
--  2. check_return_receipt_loose_line, also: the photo is of this receipt; the SKU's units back for the return (cartons
--     counted and loose, on every receipt of it not cancelled) never exceed what it approved; a recount may change who
--     recorded the line (the one whose count it is now) and its photo.
--  The second person who confirms such a receipt is checked by check_return_receipt_closed (V20261218000400).

ALTER TABLE procurement.goods_receipt_return_loose_lines ADD COLUMN photo_id uuid;

ALTER TABLE procurement.goods_receipt_return_loose_lines
    ADD CONSTRAINT fk_goods_receipt_return_loose_lines_photo FOREIGN KEY (photo_id)
        REFERENCES procurement.goods_receipt_photos (id) ON DELETE RESTRICT;

ALTER TABLE procurement.goods_receipt_return_loose_lines
    ADD CONSTRAINT ck_goods_receipt_return_loose_lines_photo CHECK (photo_id IS NOT NULL) NOT VALID;

COMMENT ON COLUMN procurement.goods_receipt_return_loose_lines.photo_id IS 'The photo of the loose units, one of the receipt''s own (WF-05 Q-18): required on every line written since V20261218000500.';

CREATE OR REPLACE FUNCTION procurement.check_return_receipt_loose_line() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    receipt  procurement.goods_receipts%ROWTYPE;
    approved BIGINT;
BEGIN
    SELECT * INTO receipt FROM procurement.goods_receipts WHERE id = COALESCE(NEW.receipt_id, OLD.receipt_id);
    IF receipt.receipt_type IS DISTINCT FROM 'RETURN' OR receipt.return_request_id IS NULL THEN
        RAISE EXCEPTION 'loose units are recorded on a RETURN receipt against a return request only'
            USING ERRCODE = 'check_violation';
    END IF;
    IF receipt.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'return receipt % is %: its loose units are kept as they were confirmed',
            receipt.receipt_number, receipt.status USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.receipt_id IS DISTINCT FROM OLD.receipt_id OR NEW.sku IS DISTINCT FROM OLD.sku
        OR NEW.lot_number IS DISTINCT FROM OLD.lot_number) THEN
        RAISE EXCEPTION 'return receipt %: only the count of loose units, its photo and who counted them change',
            receipt.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ordering.order_line l
                    WHERE l.order_id = receipt.customer_order_id AND l.sku = NEW.sku) THEN
        RAISE EXCEPTION 'return receipt %: % is not a SKU of its order (BR-14)', receipt.receipt_number, NEW.sku
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.photo_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM procurement.goods_receipt_photos p
                                                 WHERE p.id = NEW.photo_id AND p.receipt_id = NEW.receipt_id) THEN
        RAISE EXCEPTION 'return receipt %: the photo of loose units is one of its own photos', receipt.receipt_number
            USING ERRCODE = 'check_violation';
    END IF;
    approved := procurement.return_units_approved(receipt.return_request_id, NEW.sku);
    IF procurement.return_units_received(receipt.return_request_id, NEW.sku, NULL, NEW.id) + NEW.quantity
       > approved THEN
        RAISE EXCEPTION 'return receipt %: % loose unit(s) of % would bring back more than the % the return approved '
            '(WF-05 Q-18)', receipt.receipt_number, NEW.quantity, NEW.sku, approved USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
