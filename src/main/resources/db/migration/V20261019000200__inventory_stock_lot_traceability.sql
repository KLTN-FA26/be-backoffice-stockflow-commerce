-- =============================================================================
-- SCRUM-142 / SCRUM-153: a lot is a thing with an identity, not a string on a row.
--
-- A lot number alone does not identify a lot: two suppliers both ship "L01", or one supplier reuses a
-- number next month. inventory.stock_lot is one row per goods receipt and (SKU, lot number, expiry):
-- received twice, the same number is two lots, each knowing the receipt, purchase order and supplier
-- it came from (a snapshot taken at receipt, as the documents read then).
--
--   stock_item.lot_id      every stock layer belongs to one lot; moves, putaway, holds handed to the
--                          print shop and back all carry it (StockItem copies it to the layers it makes)
--   stock_movement.lot_id  every ledger line names the lot it moved; the lot trace reads these
--   stock_movement.order_id the customer order a pick, a put-back or a package deduction was for, so
--                          the trace reaches the order without reading another module's tables
--   *.order_number         its number as it read then, filled by tg_*_order_number from ordering
--
-- A layer or a ledger line written without a lot - a path the application does not tag yet, an
-- adjustment that found stock - gets one from the database: the lot with the same identity (SKU, lot
-- number, expiry, receipt time), or a new UNTRACED lot. A ledger line takes the lot of the layer it
-- touched when that is unambiguous, and stays NULL otherwise rather than guess.
--
-- Existing data is backfilled the same way, receipts first; nothing is deleted or rewritten except the
-- new columns.
-- =============================================================================

CREATE TABLE inventory.stock_lot
(
    id                    UUID          NOT NULL,
    sku                   VARCHAR(64)   NOT NULL,
    lot_number            VARCHAR(64),
    expiry_date           DATE,
    -- The receipt's confirmation time, which is also the received_at of the layers it created. NULL
    -- only for an UNTRACED lot of stock whose receipt time was never known (pre-FIFO rows).
    received_at           TIMESTAMPTZ,
    origin_type           VARCHAR(16)   NOT NULL,
    goods_receipt_id      UUID,
    goods_receipt_number  VARCHAR(30),
    purchase_order_id     UUID,
    purchase_order_number VARCHAR(30),
    supplier_id           UUID,
    supplier_name         VARCHAR(200),
    warehouse_id          UUID,

    version               BIGINT        NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by            VARCHAR(100),
    last_modified_at      TIMESTAMPTZ,
    last_modified_by      VARCHAR(100),

    CONSTRAINT pk_stock_lot PRIMARY KEY (id),
    CONSTRAINT fk_stock_lot_item FOREIGN KEY (sku) REFERENCES inventory.inventory_items (sku) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_lot_goods_receipt FOREIGN KEY (goods_receipt_id)
        REFERENCES procurement.goods_receipts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_lot_purchase_order FOREIGN KEY (purchase_order_id)
        REFERENCES procurement.purchase_orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_lot_supplier FOREIGN KEY (supplier_id)
        REFERENCES procurement.suppliers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_lot_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT ck_stock_lot_origin CHECK (origin_type IN ('GOODS_RECEIPT', 'UNTRACED')),
    -- A receipt lot knows its receipt and when it was received; an untraced one claims no document.
    CONSTRAINT ck_stock_lot_receipt CHECK (
        (origin_type = 'GOODS_RECEIPT' AND goods_receipt_id IS NOT NULL AND received_at IS NOT NULL)
        OR (origin_type = 'UNTRACED' AND goods_receipt_id IS NULL AND purchase_order_id IS NULL
            AND supplier_id IS NULL))
);

-- One lot per receipt and (SKU, lot number, expiry): two lines of one receipt for the same lot are one lot.
CREATE UNIQUE INDEX uk_stock_lot_receipt ON inventory.stock_lot
    (goods_receipt_id, sku, COALESCE(lot_number, ''), COALESCE(expiry_date, 'infinity'::date))
    WHERE goods_receipt_id IS NOT NULL;
CREATE INDEX ix_stock_lot_sku_lot ON inventory.stock_lot (sku, lot_number);
CREATE INDEX ix_stock_lot_received ON inventory.stock_lot (received_at);

COMMENT ON TABLE inventory.stock_lot IS
    'A lot as received: one per goods receipt and (SKU, lot number, expiry), so a reused lot number is a new lot (SCRUM-142).';

ALTER TABLE inventory.stock_item
    ADD COLUMN lot_id UUID,
    ADD CONSTRAINT fk_stock_item_lot FOREIGN KEY (lot_id) REFERENCES inventory.stock_lot (id) ON DELETE RESTRICT;
CREATE INDEX ix_stock_item_lot ON inventory.stock_item (lot_id);

ALTER TABLE inventory.stock_movement
    ADD COLUMN lot_id UUID,
    ADD COLUMN order_id UUID,
    -- The order's number as it read when the units moved: the trace names the order without reading ordering.
    ADD COLUMN order_number VARCHAR(32),
    ADD CONSTRAINT fk_stock_movement_lot FOREIGN KEY (lot_id) REFERENCES inventory.stock_lot (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_stock_movement_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT;
CREATE INDEX ix_stock_movement_lot ON inventory.stock_movement (lot_id, occurred_at);

-- The same snapshot on a hold (an allocation, a production hold handed to an order): filled from the order when the
-- hold first names one that exists. A checkout's SKU reservation is written before its order row (the order foreign
-- key is deferred), so it carries none; its allocations, written at release, do.
ALTER TABLE inventory.stock_reservation ADD COLUMN order_number VARCHAR(32);
COMMENT ON COLUMN inventory.stock_reservation.order_number IS
    'Snapshot of ordering.customer_order.order_number for the lot trace (SCRUM-153); NULL until the order row exists.';
COMMENT ON COLUMN inventory.stock_movement.order_number IS
    'Snapshot of ordering.customer_order.order_number when order_id is set (SCRUM-153).';
CREATE INDEX ix_stock_movement_order ON inventory.stock_movement (order_id) WHERE order_id IS NOT NULL;

-- ---- backfill, receipts first ----------------------------------------------------------------------
-- (a) a lot per confirmed receipt and (SKU, lot number, expiry) that put stock in through the ledger
INSERT INTO inventory.stock_lot (id, sku, lot_number, expiry_date, received_at, origin_type, goods_receipt_id,
                                 goods_receipt_number, purchase_order_id, purchase_order_number, supplier_id,
                                 supplier_name, warehouse_id, created_at, created_by)
SELECT gen_random_uuid(), r.sku, r.lot_number, r.expiry_date, r.confirmed_at, 'GOODS_RECEIPT', r.receipt_id,
       r.receipt_number, r.po_id, po.po_number, po.supplier_id, s.name, r.warehouse_id, NOW(), 'flyway'
  FROM (SELECT DISTINCT m.sku, m.lot_number, grl.expiry_date, gr.id AS receipt_id, gr.receipt_number, gr.po_id,
                        gr.warehouse_id, gr.confirmed_at
          FROM inventory.stock_movement m
          JOIN procurement.goods_receipt_lines grl ON grl.id = m.reference_id
          JOIN procurement.goods_receipts gr ON gr.id = grl.receipt_id
         WHERE m.movement_type = 'RECEIPT' AND m.reference_type = 'GOODS_RECEIPT_LINE'
           AND gr.confirmed_at IS NOT NULL) r
  JOIN procurement.purchase_orders po ON po.id = r.po_id
  LEFT JOIN procurement.suppliers s ON s.id = po.supplier_id;

-- (b) layers of those receipts: same SKU, lot number, expiry and receipt time
UPDATE inventory.stock_item si
   SET lot_id = (SELECT l.id FROM inventory.stock_lot l
                  WHERE l.sku = si.sku AND l.lot_number IS NOT DISTINCT FROM si.lot_number
                    AND l.expiry_date IS NOT DISTINCT FROM si.expiry_date AND l.received_at = si.received_at
                  ORDER BY l.created_at, l.id LIMIT 1)
 WHERE si.lot_id IS NULL AND si.received_at IS NOT NULL;

-- (c) every other layer: one UNTRACED lot per identity
INSERT INTO inventory.stock_lot (id, sku, lot_number, expiry_date, received_at, origin_type, created_at, created_by)
SELECT gen_random_uuid(), sku, lot_number, expiry_date, received_at, 'UNTRACED', NOW(), 'flyway'
  FROM (SELECT DISTINCT sku, lot_number, expiry_date, received_at
          FROM inventory.stock_item WHERE lot_id IS NULL) identities;

UPDATE inventory.stock_item si
   SET lot_id = (SELECT l.id FROM inventory.stock_lot l
                  WHERE l.origin_type = 'UNTRACED' AND l.sku = si.sku
                    AND l.lot_number IS NOT DISTINCT FROM si.lot_number
                    AND l.expiry_date IS NOT DISTINCT FROM si.expiry_date
                    AND l.received_at IS NOT DISTINCT FROM si.received_at
                  ORDER BY l.id LIMIT 1)
 WHERE si.lot_id IS NULL;

-- The ledger is append-only (tg_stock_movement_append_only). The backfill below fills the two columns
-- this migration just added and nothing else, so the guard is lifted for these statements only.
ALTER TABLE inventory.stock_movement DISABLE TRIGGER tg_stock_movement_append_only;

-- (d) ledger lines: a receipt line names its receipt's lot; any other line the lot of the layer it
--     touched, when only one lot of that SKU and lot number sits at the locations it names, or else
--     when the SKU has only one lot of that number at all.
UPDATE inventory.stock_movement m
   SET lot_id = l.id
  FROM procurement.goods_receipt_lines grl, inventory.stock_lot l
 WHERE m.lot_id IS NULL AND m.movement_type = 'RECEIPT' AND m.reference_type = 'GOODS_RECEIPT_LINE'
   AND grl.id = m.reference_id AND l.goods_receipt_id = grl.receipt_id AND l.sku = m.sku
   AND l.lot_number IS NOT DISTINCT FROM m.lot_number AND l.expiry_date IS NOT DISTINCT FROM grl.expiry_date;

UPDATE inventory.stock_movement m
   SET lot_id = one.lot_id
  FROM (SELECT mv.id, (array_agg(DISTINCT si.lot_id))[1] AS lot_id
          FROM inventory.stock_movement mv
          JOIN inventory.stock_item si ON si.sku = mv.sku AND si.lot_number IS NOT DISTINCT FROM mv.lot_number
                                     AND si.location_code IN (mv.from_location_code, mv.to_location_code)
         WHERE mv.lot_id IS NULL
         GROUP BY mv.id
        HAVING count(DISTINCT si.lot_id) = 1) one
 WHERE m.id = one.id;

UPDATE inventory.stock_movement m
   SET lot_id = one.lot_id
  FROM (SELECT mv.id, (array_agg(DISTINCT l.id))[1] AS lot_id
          FROM inventory.stock_movement mv
          JOIN inventory.stock_lot l ON l.sku = mv.sku AND l.lot_number IS NOT DISTINCT FROM mv.lot_number
         WHERE mv.lot_id IS NULL
         GROUP BY mv.id
        HAVING count(DISTINCT l.id) = 1) one
 WHERE m.id = one.id;

-- (e) the customer order of every pick, put-back and package deduction already in the ledger
UPDATE inventory.stock_movement m SET order_id = m.reference_id
 WHERE m.order_id IS NULL AND m.reference_type = 'ORDER'
   AND EXISTS (SELECT 1 FROM ordering.customer_order o WHERE o.id = m.reference_id);
UPDATE inventory.stock_movement m SET order_id = p.order_id
  FROM fulfillment.pick_line pl JOIN fulfillment.pick p ON p.id = pl.pick_id
 WHERE m.order_id IS NULL AND m.reference_type = 'PICK_LINE' AND pl.id = m.reference_id;
UPDATE inventory.stock_movement m SET order_id = p.order_id
  FROM fulfillment.pack pa JOIN fulfillment.pick p ON p.id = pa.pick_id
 WHERE m.order_id IS NULL AND m.reference_type = 'PACKAGE' AND pa.id = m.reference_id;

UPDATE inventory.stock_movement m SET order_number = o.order_number
  FROM ordering.customer_order o WHERE m.order_id = o.id AND m.order_number IS NULL;

ALTER TABLE inventory.stock_movement ENABLE TRIGGER tg_stock_movement_append_only;

UPDATE inventory.stock_reservation r SET order_number = o.order_number
  FROM ordering.customer_order o WHERE r.order_id = o.id AND r.order_number IS NULL;

-- ---- every new layer and ledger line gets a lot --------------------------------------------------
CREATE FUNCTION inventory.resolve_stock_item_lot()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    lot inventory.stock_lot%ROWTYPE;
BEGIN
    IF NEW.lot_id IS NULL THEN
        SELECT id INTO NEW.lot_id FROM inventory.stock_lot l
         WHERE l.sku = NEW.sku AND l.lot_number IS NOT DISTINCT FROM NEW.lot_number
           AND l.expiry_date IS NOT DISTINCT FROM NEW.expiry_date
           AND l.received_at IS NOT DISTINCT FROM NEW.received_at
         ORDER BY l.created_at, l.id LIMIT 1;
        IF NEW.lot_id IS NULL THEN
            NEW.lot_id := gen_random_uuid();
            INSERT INTO inventory.stock_lot (id, sku, lot_number, expiry_date, received_at, origin_type, created_at,
                                             created_by)
            VALUES (NEW.lot_id, NEW.sku, NEW.lot_number, NEW.expiry_date, NEW.received_at, 'UNTRACED', NOW(),
                    NEW.created_by);
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'INSERT' OR NEW.lot_id IS DISTINCT FROM OLD.lot_id THEN
        SELECT * INTO lot FROM inventory.stock_lot WHERE id = NEW.lot_id;
        IF lot.sku IS DISTINCT FROM NEW.sku OR lot.lot_number IS DISTINCT FROM NEW.lot_number
           OR lot.expiry_date IS DISTINCT FROM NEW.expiry_date THEN
            RAISE EXCEPTION 'stock item % (%, lot %) cannot belong to lot % (%, lot %)', NEW.id, NEW.sku,
                NEW.lot_number, NEW.lot_id, lot.sku, lot.lot_number USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_item_lot BEFORE INSERT OR UPDATE OF lot_id ON inventory.stock_item
    FOR EACH ROW EXECUTE FUNCTION inventory.resolve_stock_item_lot();

CREATE FUNCTION inventory.resolve_stock_movement_lot()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    lots UUID[];
BEGIN
    IF NEW.lot_id IS NULL THEN
        SELECT array_agg(DISTINCT lot_id) INTO lots FROM inventory.stock_item
         WHERE sku = NEW.sku AND lot_number IS NOT DISTINCT FROM NEW.lot_number AND lot_id IS NOT NULL
           AND location_code IN (NEW.from_location_code, NEW.to_location_code);
        IF array_length(lots, 1) = 1 THEN
            NEW.lot_id := lots[1];
        END IF;
    ELSE
        IF NOT EXISTS (SELECT 1 FROM inventory.stock_lot l WHERE l.id = NEW.lot_id AND l.sku = NEW.sku
                         AND l.lot_number IS NOT DISTINCT FROM NEW.lot_number) THEN
            RAISE EXCEPTION 'ledger line % moves % lot % but names lot %', NEW.id, NEW.sku, NEW.lot_number,
                NEW.lot_id USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_movement_lot BEFORE INSERT ON inventory.stock_movement
    FOR EACH ROW EXECUTE FUNCTION inventory.resolve_stock_movement_lot();

-- The order number snapshot, on a ledger line or a hold that names an order (the application writes only the id).
CREATE FUNCTION inventory.snapshot_order_number()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.order_id IS NOT NULL AND NEW.order_number IS NULL THEN
        SELECT order_number INTO NEW.order_number FROM ordering.customer_order WHERE id = NEW.order_id;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_movement_order_number BEFORE INSERT ON inventory.stock_movement
    FOR EACH ROW EXECUTE FUNCTION inventory.snapshot_order_number();

CREATE TRIGGER tg_stock_reservation_order_number BEFORE INSERT OR UPDATE ON inventory.stock_reservation
    FOR EACH ROW EXECUTE FUNCTION inventory.snapshot_order_number();

-- ---- the SKU rename guard and the draft product delete know the two new references --------------
-- inventory.item_in_use (V20261016000100, V20261017001200) answers "does a document use this item?" so a rename or a
-- delete is refused with "this SKU is referenced" instead of a foreign-key error. A SKU reservation and a lot both
-- name the SKU (fk_stock_reservation_sku, fk_stock_lot_item); the body is V20261017001200's with those two lines added.
CREATE OR REPLACE FUNCTION inventory.item_in_use(p_item_id UUID, p_sku VARCHAR) RETURNS BOOLEAN
    LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM inventory.stock_item WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_movement WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_adjustment WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.cycle_count_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.transfer_order_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM warehouse.putaway_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM warehouse.move_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM fulfillment.pick_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM catalog.pricing_rule WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM procurement.supplier_items WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.replenishment_proposal_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_line_revisions WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.goods_receipt_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM catalog.customer_price WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM ordering.quote_line WHERE blank_sku = p_sku)
        OR EXISTS (SELECT 1 FROM production.production_order WHERE blank_sku = p_sku)
        OR EXISTS (SELECT 1 FROM production.sample_request WHERE blank_sku = p_sku)
        OR EXISTS (SELECT 1 FROM ordering.approved_sample WHERE blank_sku = p_sku)
        OR EXISTS (SELECT 1 FROM production.production_material_issue WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_reservation WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_lot WHERE sku = p_sku)
$$;
