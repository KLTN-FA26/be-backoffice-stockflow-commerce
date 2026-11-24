-- WF-05 Q-18 (decision [3/3]; Luật Kế toán Điều 13 khoản 1: a record states what really happened; kltn-docs 20 BR-14),
-- BE-20, BE-22: no stock is created from a carton's label.
--
--  1. A carton that came back opened or empty (counted < label) is not put into stock at all. It used to go into
--     quarantine whole at its label's count - 1,000 on hand for a carton the receipt counted 800 or 0 in - only to be
--     written off later. Now it stays CONSUMED, marked return_opened_at, with no ledger line; the receipt keeps the
--     real count, and its carton line says stock_entered = false (its result: destroyed). Quarantine holds exactly
--     what was counted in sealed cartons.
--     stock_entered is NOT VALID-checked to equal "counted = label": receipts confirmed before this put opened cartons
--     into stock, and keep saying so.
--  2. counted_by: who recorded the count on the carton line now (the scan, or a later recount). A receipt with an
--     opened carton or a loose line is confirmed only by a warehouse manager who counted nothing on it (Q-18 add-on:
--     "người xác nhận khác người đếm"); the permission is the service's, the second person is checked again here.
--  3. check_return_receipt_carton, also:
--     - a carton already back once (return_opened_at set) is not taken again: it stays CONSUMED, so the status the
--       service checks would not say so;
--     - BE-22 / Q-11: a receipt against a return request takes only cartons of a SKU the return approved, and not once
--       that SKU's approved units are all back - such goods are the customer's, kept in custody on the return request.
--  4. check_return_receipt_closed: the ledger matches the cartons that entered stock (and only those); an opened carton
--     is marked return_opened_at and still CONSUMED; the confirmer counted nothing on a receipt that needs a manager;
--     and (BE-23) the return no longer names the receipt as its open draft.

-- ---------------------------------------------------------------------------------------------------------- 1, 2

ALTER TABLE procurement.goods_receipt_return_cartons ADD COLUMN stock_entered boolean;
ALTER TABLE procurement.goods_receipt_return_cartons ADD COLUMN counted_by uuid;

ALTER TABLE procurement.goods_receipt_return_cartons DISABLE TRIGGER tg_goods_receipt_return_cartons;
UPDATE procurement.goods_receipt_return_cartons c
   SET stock_entered = CASE WHEN g.status = 'DRAFT' THEN c.counted_qty = c.quantity ELSE TRUE END,
       counted_by = c.scanned_by
  FROM procurement.goods_receipts g
 WHERE g.id = c.receipt_id;
ALTER TABLE procurement.goods_receipt_return_cartons ENABLE TRIGGER tg_goods_receipt_return_cartons;

ALTER TABLE procurement.goods_receipt_return_cartons ALTER COLUMN stock_entered SET NOT NULL;
ALTER TABLE procurement.goods_receipt_return_cartons ALTER COLUMN counted_by SET NOT NULL;
ALTER TABLE procurement.goods_receipt_return_cartons
    ADD CONSTRAINT fk_goods_receipt_return_cartons_counted_by FOREIGN KEY (counted_by)
        REFERENCES identity.app_user (id);
ALTER TABLE procurement.goods_receipt_return_cartons
    ADD CONSTRAINT ck_goods_receipt_return_cartons_stock_entered CHECK (stock_entered = (counted_qty = quantity))
        NOT VALID;

COMMENT ON COLUMN procurement.goods_receipt_return_cartons.stock_entered IS 'Whether the carton went back into stock (QUARANTINE) on confirmation (WF-05 Q-18): only a sealed one (counted = label). An opened or empty carton stays CONSUMED, marked return_opened_at, its count recorded here only. TRUE on receipts confirmed before V20261218000400, which stocked every carton.';
COMMENT ON COLUMN procurement.goods_receipt_return_cartons.counted_by IS 'Who recorded the count on this line now - the scan, or the last recount (WF-05 Q-18): a receipt with an opened carton or a loose line is confirmed by someone else.';

-- ---------------------------------------------------------------------------------------------------------- 3

-- What a return approved of a SKU, and what came back of it on its receipts not cancelled (cartons by their count,
-- and loose units), leaving out one carton line or one loose line - the row being written.
CREATE FUNCTION procurement.return_units_approved(p_return uuid, p_sku varchar) RETURNS bigint
    LANGUAGE sql STABLE
    AS $$
    SELECT COALESCE(sum(x.approved_quantity), 0)
      FROM ordering.return_request_line x
     WHERE x.return_request_id = p_return AND x.sku = p_sku
$$;

CREATE FUNCTION procurement.return_units_received(p_return uuid, p_sku varchar, p_except_carton uuid,
                                                  p_except_loose uuid) RETURNS bigint
    LANGUAGE sql STABLE
    AS $$
    SELECT COALESCE((SELECT sum(c.counted_qty)
                       FROM procurement.goods_receipt_return_cartons c
                       JOIN procurement.goods_receipts g ON g.id = c.receipt_id
                      WHERE g.return_request_id = p_return AND g.status <> 'CANCELLED' AND c.sku = p_sku
                        AND c.id IS DISTINCT FROM p_except_carton), 0)
         + COALESCE((SELECT sum(l.quantity)
                       FROM procurement.goods_receipt_return_loose_lines l
                       JOIN procurement.goods_receipts g ON g.id = l.receipt_id
                      WHERE g.return_request_id = p_return AND g.status <> 'CANCELLED' AND l.sku = p_sku
                        AND l.id IS DISTINCT FROM p_except_loose), 0)
$$;

CREATE OR REPLACE FUNCTION procurement.check_return_receipt_carton() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    receipt          procurement.goods_receipts%ROWTYPE;
    pkg              RECORD;
    request_shipment UUID;
    approved         BIGINT;
BEGIN
    SELECT * INTO receipt FROM procurement.goods_receipts WHERE id = COALESCE(NEW.receipt_id, OLD.receipt_id);
    IF receipt.receipt_type IS DISTINCT FROM 'RETURN' THEN
        RAISE EXCEPTION 'cartons are scanned on a RETURN receipt only' USING ERRCODE = 'check_violation';
    END IF;
    IF receipt.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'return receipt % is %: its cartons are kept as they were confirmed', receipt.receipt_number,
            receipt.status USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    -- A writer that says nothing of the count took the carton whole: what it was before counted_qty existed. NOT NULL
    -- is checked after this trigger, so it still refuses an explicit NULL on an update.
    IF TG_OP = 'INSERT' AND NEW.counted_qty IS NULL THEN
        NEW.counted_qty := NEW.quantity;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.counted_by IS NULL THEN
        NEW.counted_by := NEW.scanned_by;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.stock_entered IS NULL THEN
        NEW.stock_entered := NEW.counted_qty = NEW.quantity;
    END IF;
    -- On a draft, whether the carton will enter stock follows its count: a recount (by the service or straight in the
    -- database) moves both together, so ck_goods_receipt_return_cartons_stock_entered never sees them disagree.
    IF TG_OP = 'UPDATE' THEN
        NEW.stock_entered := NEW.counted_qty = NEW.quantity;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.receipt_id IS DISTINCT FROM OLD.receipt_id
        OR NEW.stock_package_id IS DISTINCT FROM OLD.stock_package_id OR NEW.lpn IS DISTINCT FROM OLD.lpn
        OR NEW.sku IS DISTINCT FROM OLD.sku OR NEW.lot_number IS DISTINCT FROM OLD.lot_number
        OR NEW.quantity IS DISTINCT FROM OLD.quantity OR NEW.scanned_by IS DISTINCT FROM OLD.scanned_by) THEN
        RAISE EXCEPTION 'return receipt %: only the condition and the count inside a scanned carton are changed',
            receipt.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    SELECT p.lpn, i.sku, p.received_qty, p.return_opened_at INTO pkg
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE p.id = NEW.stock_package_id;
    IF NOT FOUND THEN
        RETURN NEW; -- fk_goods_receipt_return_cartons_package reports it
    END IF;
    IF pkg.lpn <> NEW.lpn OR pkg.sku <> NEW.sku OR pkg.received_qty IS DISTINCT FROM NEW.quantity
       OR NOT EXISTS (SELECT 1 FROM inventory.stock_item s
                       WHERE s.stock_package_id = NEW.stock_package_id
                         AND s.lot_number IS NOT DISTINCT FROM NEW.lot_number) THEN
        RAISE EXCEPTION 'return receipt %: carton % is named as it was shipped - its LPN, SKU, count and lot (BR-03)',
            receipt.receipt_number, NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' AND pkg.return_opened_at IS NOT NULL THEN
        RAISE EXCEPTION 'return receipt %: carton % came back opened on an earlier return receipt (WF-05 Q-18)',
            receipt.receipt_number, NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fulfillment.pick_line l JOIN fulfillment.pick k ON k.id = l.pick_id
                    WHERE k.order_id = receipt.customer_order_id AND l.lpn = NEW.lpn
                      AND l.picked_qty > l.put_back_qty) THEN
        RAISE EXCEPTION 'return receipt %: carton % was not shipped on its order (BR-14)', receipt.receipt_number,
            NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' AND receipt.return_request_id IS NOT NULL THEN
        -- WF-05 TH-1: a return against a delivery takes back that delivery's cartons only.
        SELECT r.shipment_id INTO request_shipment FROM ordering.return_request r WHERE r.id = receipt.return_request_id;
        IF request_shipment IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM fulfillment.package p JOIN fulfillment.pick_line l ON l.id = p.pick_line_id
                 WHERE p.shipment_id = request_shipment AND p.status = 'LABELED' AND l.lpn = NEW.lpn) THEN
            RAISE EXCEPTION 'return receipt %: carton % was not delivered on the shipment the return is against',
                receipt.receipt_number, NEW.lpn USING ERRCODE = 'check_violation';
        END IF;
        -- WF-05 Q-11 (BE-22): only goods the return approved, while some of them are still to come back.
        approved := procurement.return_units_approved(receipt.return_request_id, NEW.sku);
        IF approved = 0 OR procurement.return_units_received(receipt.return_request_id, NEW.sku, NEW.id, NULL)
                           >= approved THEN
            RAISE EXCEPTION 'return receipt %: carton % holds % the return did not approve, or whose approved units are '
                'all back: it is kept in custody on the return request (WF-05 Q-11)', receipt.receipt_number, NEW.lpn,
                NEW.sku USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------------------------------------- 4

CREATE OR REPLACE FUNCTION procurement.check_return_receipt_closed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    area_code VARCHAR(64);
    cartons   BIGINT;
    entered   BIGINT;
    loose     BIGINT;
    received  BIGINT;
    missing   TEXT;
    unmarked  TEXT;
BEGIN
    SELECT location_code INTO area_code FROM warehouse.storage_location WHERE id = NEW.quarantine_location_id;
    SELECT count(*), count(*) FILTER (WHERE stock_entered) INTO cartons, entered
      FROM procurement.goods_receipt_return_cartons WHERE receipt_id = NEW.id;
    SELECT count(*) INTO loose FROM procurement.goods_receipt_return_loose_lines WHERE receipt_id = NEW.id;
    IF cartons = 0 AND loose = 0 THEN
        RAISE EXCEPTION 'return receipt % is closed with nothing received', NEW.receipt_number
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT string_agg(c.lpn, ', ' ORDER BY c.lpn) INTO missing
      FROM procurement.goods_receipt_return_cartons c
     WHERE c.receipt_id = NEW.id AND c.stock_entered
       AND NOT EXISTS (SELECT 1 FROM inventory.stock_movement m
                        WHERE m.reference_type = 'GOODS_RECEIPT' AND m.reference_id = NEW.id
                          AND m.to_stock_package_id = c.stock_package_id AND m.to_location_code = area_code);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'return receipt % is closed but carton(s) % were not received into %', NEW.receipt_number,
            missing, area_code USING ERRCODE = 'check_violation';
    END IF;
    -- Loose units and opened cartons have no stock entry: only the cartons that entered stock are in the ledger.
    SELECT count(*) INTO received FROM inventory.stock_movement
     WHERE reference_type = 'GOODS_RECEIPT' AND reference_id = NEW.id;
    IF received <> entered THEN
        RAISE EXCEPTION 'return receipt % puts % carton(s) into stock but the ledger received %', NEW.receipt_number,
            entered, received USING ERRCODE = 'check_violation';
    END IF;
    -- WF-05 Q-18: an opened carton stays out of stock, and is marked so it is never taken back again.
    SELECT string_agg(c.lpn, ', ' ORDER BY c.lpn) INTO unmarked
      FROM procurement.goods_receipt_return_cartons c
      JOIN inventory.stock_package p ON p.id = c.stock_package_id
     WHERE c.receipt_id = NEW.id AND NOT c.stock_entered
       AND (p.return_opened_at IS NULL OR p.status <> 'CONSUMED');
    IF unmarked IS NOT NULL THEN
        RAISE EXCEPTION 'return receipt %: opened carton(s) % are not kept out of stock as opened', NEW.receipt_number,
            unmarked USING ERRCODE = 'check_violation';
    END IF;
    -- WF-05 Q-18 add-on: a receipt with an opened carton or a loose line is confirmed by someone who counted nothing
    -- on it.
    IF (loose > 0 OR EXISTS (SELECT 1 FROM procurement.goods_receipt_return_cartons c
                              WHERE c.receipt_id = NEW.id AND c.counted_qty < c.quantity))
       AND (EXISTS (SELECT 1 FROM procurement.goods_receipt_return_cartons c
                     WHERE c.receipt_id = NEW.id AND NEW.confirmed_by IN (c.scanned_by, c.counted_by))
            OR EXISTS (SELECT 1 FROM procurement.goods_receipt_return_loose_lines l
                        WHERE l.receipt_id = NEW.id AND l.recorded_by = NEW.confirmed_by)) THEN
        RAISE EXCEPTION 'return receipt %: confirmed by who counted it; a receipt with opened cartons or loose units is '
            'confirmed by someone else (WF-05 Q-18)', NEW.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    -- WF-05 BE-23: the return is told the draft is closed in the same transaction.
    IF NEW.return_request_id IS NOT NULL AND EXISTS (SELECT 1 FROM ordering.return_request r
                                                      WHERE r.id = NEW.return_request_id
                                                        AND r.open_draft_receipt_id = NEW.id) THEN
        RAISE EXCEPTION 'return receipt % is closed but its return still counts it as an open draft',
            NEW.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;
