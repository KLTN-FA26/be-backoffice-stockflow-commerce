-- WF-05 audit round 2, returns: the receipt side of the rules for goods coming back.
--
--  1. M3, L6 (Q-11 hỏi thêm: "Hàng vượt số duyệt ... giữ hộ, không nhập tồn"; docs 03 §4.2 step 10): a carton on a
--     return request's receipt never takes the SKU above what the return approved - counting its units (the label for
--     a sealed one, the count for an opened one) together with what its receipts already brought, on the scan and on
--     every recount. Before, only "anything still to come back?" was asked, so a sealed 1,000 carton on a 500 approval
--     went into stock whole, its 500 over the approval company stock. A carton is never split: one that does not fit
--     is the customer's, kept in custody on the return request.
--  2. H1 (Q-4): a carton held for the customer, or destroyed on their behalf, is not taken back on a receipt.
--  3. check_return_receipt_closed, also:
--     - M1: a return request's receipt is CLOSED only with its return having taken its goods (a row in
--       ordering.return_request_receipt) in the same transaction. The return used to learn of them after commit,
--       asynchronously, while its draft flag was already cleared - a refund could be approved in between and the parcel
--       never refunded; a failing listener stretched that until the next restart.
--     - H1: every carton the receipt stocked waits for the return's refund decision (PENDING, naming the return); a
--       carrier's return stocks the company's goods, with no ownership.

-- ---------------------------------------------------------------------------------------------------------- 1, 2

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
    SELECT p.lpn, i.sku, p.received_qty, p.return_opened_at, p.return_ownership INTO pkg
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
    IF TG_OP = 'INSERT' AND pkg.return_ownership IN ('CUSTOMER_HELD', 'CUSTOMER_DESTROYED') THEN
        RAISE EXCEPTION 'return receipt %: carton % is the customer''s, held for them or destroyed on their behalf '
            '(WF-05 Q-4)', receipt.receipt_number, NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fulfillment.pick_line l JOIN fulfillment.pick k ON k.id = l.pick_id
                    WHERE k.order_id = receipt.customer_order_id AND l.lpn = NEW.lpn
                      AND l.picked_qty > l.put_back_qty) THEN
        RAISE EXCEPTION 'return receipt %: carton % was not shipped on its order (BR-14)', receipt.receipt_number,
            NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF receipt.return_request_id IS NOT NULL THEN
        IF TG_OP = 'INSERT' THEN
            -- WF-05 TH-1: a return against a delivery takes back that delivery's cartons only.
            SELECT r.shipment_id INTO request_shipment FROM ordering.return_request r
             WHERE r.id = receipt.return_request_id;
            IF request_shipment IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM fulfillment.package p JOIN fulfillment.pick_line l ON l.id = p.pick_line_id
                     WHERE p.shipment_id = request_shipment AND p.status = 'LABELED' AND l.lpn = NEW.lpn) THEN
                RAISE EXCEPTION 'return receipt %: carton % was not delivered on the shipment the return is against',
                    receipt.receipt_number, NEW.lpn USING ERRCODE = 'check_violation';
            END IF;
        END IF;
        -- WF-05 Q-11 (BE-22, M3, L6): only goods the return approved, and never above it - on the scan and on a
        -- recount alike. Units beyond are the customer's, kept in custody on the return request.
        IF TG_OP = 'INSERT' OR NEW.counted_qty > OLD.counted_qty THEN
            approved := procurement.return_units_approved(receipt.return_request_id, NEW.sku);
            IF approved = 0 OR procurement.return_units_received(receipt.return_request_id, NEW.sku, NEW.id, NULL)
                               + NEW.counted_qty > approved THEN
                RAISE EXCEPTION 'return receipt %: carton % holds % the return did not approve, or more of it than is '
                    'still to come back: it is kept in custody on the return request (WF-05 Q-11)',
                    receipt.receipt_number, NEW.lpn, NEW.sku USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------------------------------------------- 3

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
    unowned   TEXT;
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
    -- WF-05 Q-4 (H1): a sealed carton of a return request waits for its refund decision; one a carrier brought back is
    -- the company's.
    SELECT string_agg(c.lpn, ', ' ORDER BY c.lpn) INTO unowned
      FROM procurement.goods_receipt_return_cartons c
      JOIN inventory.stock_package p ON p.id = c.stock_package_id
     WHERE c.receipt_id = NEW.id AND c.stock_entered
       AND (CASE WHEN NEW.return_request_id IS NULL THEN p.return_ownership IS NOT NULL
                 ELSE p.return_request_id IS DISTINCT FROM NEW.return_request_id
                      OR p.return_ownership IS DISTINCT FROM 'PENDING' END);
    IF unowned IS NOT NULL THEN
        RAISE EXCEPTION 'return receipt %: carton(s) % do not wait for the return''s refund decision as they came back '
            '(WF-05 Q-4)', NEW.receipt_number, unowned USING ERRCODE = 'check_violation';
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
    -- WF-05 M1: and it has taken the receipt's goods, in the same transaction - never after a commit a refund decision
    -- could slip into.
    IF NEW.return_request_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.return_request_receipt t
                                                          WHERE t.goods_receipt_id = NEW.id
                                                            AND t.return_request_id = NEW.return_request_id) THEN
        RAISE EXCEPTION 'return receipt % is closed but its return has not taken its goods', NEW.receipt_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;
