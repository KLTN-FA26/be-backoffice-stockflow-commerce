-- WF-05 TH-1 / TH-4 (owner decisions 2026-11-18; kltn-docs 03 §4.2, BR-03, BR-14; 20 BR-14, BR-21; 17 §4.10): what a
-- return receipt finds inside the cartons that come back, and goods that come back with no carton at all. The warehouse
-- invariant stays as it is - a carton is in stock only whole, at its standard count (ck_stock_package_standard_count and
-- the checks around it are not touched): what is not whole is recorded on the receipt, refunded for what it holds, and
-- never sold again.
--
--  1. goods_receipt_return_cartons.counted_qty: the units actually inside a scanned carton (0..quantity; the full count
--     unless the warehouse says otherwise - an insert that names no count gets quantity from check_return_receipt_carton). Fewer than the label says: the carton was opened. It is received into
--     quarantine whole as before - the books know it only as a carton - but it never leaves quarantine for sale: its
--     stock_package is marked return_opened_at, and only a write-off (an approved DAMAGED adjustment of the carton) takes
--     it out. The return refunds the units counted, not the label.
--  2. goods_receipt_return_loose_lines: units that came back with no carton label (SKU, lot when known, count), on a
--     receipt against a return request. Recorded on the receipt only, no stock entry - they are destroyed - and counted
--     as received for the return. A receipt may close with loose lines and no carton.
--  3. A return request against a shipment takes back only cartons of that shipment (check_return_receipt_carton).
--  4. inventory.stock_package.return_opened_at, set once by the return receipt's confirmation, never cleared; a stock row
--     of such a carton never becomes AVAILABLE (tg_stock_item_opened_return), whatever path tries it.

-- ---------------------------------------------------------------------------------------------------------- 1

ALTER TABLE procurement.goods_receipt_return_cartons ADD COLUMN counted_qty integer;

-- Every carton received so far was taken whole. The table's trigger refuses changes on confirmed receipts; this
-- backfill is not one of the changes it is there to stop.
ALTER TABLE procurement.goods_receipt_return_cartons DISABLE TRIGGER tg_goods_receipt_return_cartons;
UPDATE procurement.goods_receipt_return_cartons SET counted_qty = quantity;
ALTER TABLE procurement.goods_receipt_return_cartons ENABLE TRIGGER tg_goods_receipt_return_cartons;

ALTER TABLE procurement.goods_receipt_return_cartons ALTER COLUMN counted_qty SET NOT NULL;
ALTER TABLE procurement.goods_receipt_return_cartons
    ADD CONSTRAINT ck_goods_receipt_return_cartons_counted CHECK (counted_qty >= 0 AND counted_qty <= quantity);

COMMENT ON COLUMN procurement.goods_receipt_return_cartons.counted_qty IS 'The units found inside the carton when it came back (WF-05 TH-4): quantity when it came back sealed, fewer when it was opened. An opened carton goes into quarantine whole and is only ever written off; the return refunds what was counted.';

-- ---------------------------------------------------------------------------------------------------------- 2

CREATE TABLE procurement.goods_receipt_return_loose_lines (
    id               uuid                     NOT NULL,
    receipt_id       uuid                     NOT NULL,
    sku              varchar(64)              NOT NULL,
    lot_number       varchar(64),
    quantity         integer                  NOT NULL,
    recorded_by      uuid                     NOT NULL,
    version          bigint                   NOT NULL DEFAULT 0,
    created_at       timestamptz              NOT NULL DEFAULT now(),
    created_by       varchar(100),
    last_modified_at timestamptz,
    last_modified_by varchar(100),
    CONSTRAINT pk_goods_receipt_return_loose_lines PRIMARY KEY (id),
    CONSTRAINT ck_goods_receipt_return_loose_lines_quantity CHECK (quantity > 0),
    CONSTRAINT ck_goods_receipt_return_loose_lines_lot CHECK (lot_number IS NULL OR btrim(lot_number) <> ''),
    CONSTRAINT fk_goods_receipt_return_loose_lines_receipt FOREIGN KEY (receipt_id)
        REFERENCES procurement.goods_receipts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_goods_receipt_return_loose_lines_variant FOREIGN KEY (sku)
        REFERENCES product.variants (sku) ON DELETE RESTRICT,
    CONSTRAINT fk_goods_receipt_return_loose_lines_recorded_by FOREIGN KEY (recorded_by)
        REFERENCES identity.app_user (id)
);

CREATE UNIQUE INDEX uk_goods_receipt_return_loose_lines ON procurement.goods_receipt_return_loose_lines
    (receipt_id, sku, COALESCE(lot_number, ''));

COMMENT ON TABLE procurement.goods_receipt_return_loose_lines IS 'Units that came back on a RETURN receipt with no carton label (WF-05 TH-4): SKU, lot when known, count. No stock entry - they are destroyed - but counted as received for the return request. Added and removed only while the receipt is a DRAFT.';

CREATE FUNCTION procurement.check_return_receipt_loose_line() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    receipt procurement.goods_receipts%ROWTYPE;
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
        OR NEW.lot_number IS DISTINCT FROM OLD.lot_number OR NEW.recorded_by IS DISTINCT FROM OLD.recorded_by) THEN
        RAISE EXCEPTION 'return receipt %: only the count of loose units is changed', receipt.receipt_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ordering.order_line l
                    WHERE l.order_id = receipt.customer_order_id AND l.sku = NEW.sku) THEN
        RAISE EXCEPTION 'return receipt %: % is not a SKU of its order (BR-14)', receipt.receipt_number, NEW.sku
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_goods_receipt_return_loose_lines BEFORE INSERT OR DELETE OR UPDATE
    ON procurement.goods_receipt_return_loose_lines
    FOR EACH ROW EXECUTE FUNCTION procurement.check_return_receipt_loose_line();

-- ---------------------------------------------------------------------------------------------------------- 1, 3

CREATE OR REPLACE FUNCTION procurement.check_return_receipt_carton() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    receipt          procurement.goods_receipts%ROWTYPE;
    pkg              RECORD;
    request_shipment UUID;
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
    IF TG_OP = 'UPDATE' AND (NEW.receipt_id IS DISTINCT FROM OLD.receipt_id
        OR NEW.stock_package_id IS DISTINCT FROM OLD.stock_package_id OR NEW.lpn IS DISTINCT FROM OLD.lpn
        OR NEW.sku IS DISTINCT FROM OLD.sku OR NEW.lot_number IS DISTINCT FROM OLD.lot_number
        OR NEW.quantity IS DISTINCT FROM OLD.quantity OR NEW.scanned_by IS DISTINCT FROM OLD.scanned_by) THEN
        RAISE EXCEPTION 'return receipt %: only the condition and the count inside a scanned carton are changed',
            receipt.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    SELECT p.lpn, i.sku, p.received_qty INTO pkg
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
    IF NOT EXISTS (SELECT 1 FROM fulfillment.pick_line l JOIN fulfillment.pick k ON k.id = l.pick_id
                    WHERE k.order_id = receipt.customer_order_id AND l.lpn = NEW.lpn
                      AND l.picked_qty > l.put_back_qty) THEN
        RAISE EXCEPTION 'return receipt %: carton % was not shipped on its order (BR-14)', receipt.receipt_number,
            NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    -- WF-05 TH-1: a return against a delivery takes back that delivery's cartons only.
    IF TG_OP = 'INSERT' AND receipt.return_request_id IS NOT NULL THEN
        SELECT r.shipment_id INTO request_shipment FROM ordering.return_request r WHERE r.id = receipt.return_request_id;
        IF request_shipment IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM fulfillment.package p JOIN fulfillment.pick_line l ON l.id = p.pick_line_id
                 WHERE p.shipment_id = request_shipment AND p.status = 'LABELED' AND l.lpn = NEW.lpn) THEN
            RAISE EXCEPTION 'return receipt %: carton % was not delivered on the shipment the return is against',
                receipt.receipt_number, NEW.lpn USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------------------------------------- 2

CREATE OR REPLACE FUNCTION procurement.check_return_receipt_closed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    area_code VARCHAR(64);
    cartons   BIGINT;
    loose     BIGINT;
    received  BIGINT;
    missing   TEXT;
BEGIN
    SELECT location_code INTO area_code FROM warehouse.storage_location WHERE id = NEW.quarantine_location_id;
    SELECT count(*) INTO cartons FROM procurement.goods_receipt_return_cartons WHERE receipt_id = NEW.id;
    SELECT count(*) INTO loose FROM procurement.goods_receipt_return_loose_lines WHERE receipt_id = NEW.id;
    IF cartons = 0 AND loose = 0 THEN
        RAISE EXCEPTION 'return receipt % is closed with nothing received', NEW.receipt_number
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT string_agg(c.lpn, ', ' ORDER BY c.lpn) INTO missing
      FROM procurement.goods_receipt_return_cartons c
     WHERE c.receipt_id = NEW.id
       AND NOT EXISTS (SELECT 1 FROM inventory.stock_movement m
                        WHERE m.reference_type = 'GOODS_RECEIPT' AND m.reference_id = NEW.id
                          AND m.to_stock_package_id = c.stock_package_id AND m.to_location_code = area_code);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'return receipt % is closed but carton(s) % were not received into %', NEW.receipt_number,
            missing, area_code USING ERRCODE = 'check_violation';
    END IF;
    -- Loose units have no stock entry: only the cartons are in the ledger.
    SELECT count(*) INTO received FROM inventory.stock_movement
     WHERE reference_type = 'GOODS_RECEIPT' AND reference_id = NEW.id;
    IF received <> cartons THEN
        RAISE EXCEPTION 'return receipt % lists % carton(s) but received %', NEW.receipt_number, cartons, received
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

-- ---------------------------------------------------------------------------------------------------------- 4

ALTER TABLE inventory.stock_package ADD COLUMN return_opened_at timestamptz;

COMMENT ON COLUMN inventory.stock_package.return_opened_at IS 'Set when a return receipt found fewer units in the carton than its label (WF-05 TH-4): the carton sits in quarantine whole and is only ever written off - none of its stock is AVAILABLE again. Never cleared.';

CREATE FUNCTION inventory.check_stock_package_return_opened() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.return_opened_at IS NOT NULL AND NEW.return_opened_at IS DISTINCT FROM OLD.return_opened_at THEN
        RAISE EXCEPTION 'carton %: it came back opened, and stays recorded so', OLD.lpn
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_package_return_opened BEFORE UPDATE OF return_opened_at ON inventory.stock_package
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_package_return_opened();

CREATE FUNCTION inventory.refuse_opened_return_carton_on_sale() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM inventory.stock_package p
                WHERE p.id = NEW.stock_package_id AND p.return_opened_at IS NOT NULL) THEN
        RAISE EXCEPTION 'stock of carton % came back opened on a return: it is written off, never sold again',
            NEW.stock_package_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

-- Only on the way into AVAILABLE, so the rows of every other carton pay for nothing on their ordinary updates (an ORM
-- writes the status column on every update).
CREATE TRIGGER tg_stock_item_opened_return BEFORE UPDATE OF status ON inventory.stock_item
    FOR EACH ROW WHEN (NEW.status = 'AVAILABLE' AND OLD.status IS DISTINCT FROM 'AVAILABLE'
                       AND NEW.stock_package_id IS NOT NULL)
    EXECUTE FUNCTION inventory.refuse_opened_return_carton_on_sale();

CREATE TRIGGER tg_stock_item_opened_return_insert BEFORE INSERT ON inventory.stock_item
    FOR EACH ROW WHEN (NEW.status = 'AVAILABLE' AND NEW.stock_package_id IS NOT NULL)
    EXECUTE FUNCTION inventory.refuse_opened_return_carton_on_sale();
