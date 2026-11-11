-- =============================================================================
-- Review M-5 (cluster 4): "whole cartons only" stated in the table too (kltn-docs 07 BR-03, 20 BR-14; team plan
-- 4C-1, 4C-2).
--
-- Why. Since 4C-2 every unit in stock is in an LPN carton, one stock row per carton (uk_stock_item_package). The rule was
-- enforced on the ledger (QA 41) and in code, but a live stock row in no carton was still accepted by the table - and
-- uk_stock_item_sku_location_lot even keys such rows. The domain now refuses to add units or holds to a row in no carton
-- (StockItem.requireInCartonWhenStocked, STOCK_NOT_IN_CARTON at the request); this is the second statement.
--
-- Kept on purpose: the loose rows 4C-1/4C-3 emptied (on_hand 0, nothing held) stay: they carry the ledger's history and
-- the layer key of their lot. ck_stock_item_whole_cartons lets them be, and only them.
--
-- Data (QD-1, owner 10/11: no archive, no stop condition; the migration clears what is left itself):
--   1. any live hold on a row in no carton is released (MANUAL_OVERRIDE) and the row's reserved falls by it - there is
--      no carton to pick it from;
--   2. any unit still on a row in no carton is written off: one ADJUSTMENT ledger line per row (reference the row
--      itself, the reason naming this migration), then on_hand 0. A database built from db/migration and db/demo has
--      none: 4C-1 put the demo stock into cartons.
--   3. a hold at a warehouse (a SKU reservation, on no row) that the write-off left with too little stock behind it
--      is released, newest first, until what is held of the SKU is covered again - at its warehouse and system-wide,
--      the rule tg_stock_reservation_soft_atp keeps on every hold it sees written (review P-4);
--   4. the CHECK, validated.
-- =============================================================================

-- 1. holds on loose rows
WITH released AS (
    UPDATE inventory.stock_reservation r
       SET status = 'RELEASED', release_reason = 'MANUAL_OVERRIDE', closed_at = now()
      FROM inventory.stock_item s
     WHERE r.stock_item_id = s.id AND s.stock_package_id IS NULL AND r.status = 'HELD'
    RETURNING r.stock_item_id, r.quantity
)
UPDATE inventory.stock_item s
   SET reserved = GREATEST(s.reserved - x.quantity, 0), version = s.version + 1, last_modified_at = now()
  FROM (SELECT stock_item_id, sum(quantity) AS quantity FROM released GROUP BY stock_item_id) x
 WHERE s.id = x.stock_item_id;

UPDATE inventory.stock_item
   SET reserved = 0, version = version + 1, last_modified_at = now()
 WHERE stock_package_id IS NULL AND reserved > 0;

-- 2. units on loose rows, written off on the ledger
CREATE TEMP TABLE written_off_sku ON COMMIT DROP AS
SELECT DISTINCT sku FROM inventory.stock_item WHERE stock_package_id IS NULL AND on_hand > 0;

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code, quantity,
                                      from_status, to_status, reference_type, reference_id, reason, occurred_at, lot_id,
                                      created_at, created_by)
SELECT gen_random_uuid(), 'ADJUSTMENT', s.sku, s.lot_number, s.location_code, NULL, s.on_hand, s.status, NULL,
       'STOCK_ADJUSTMENT', s.id, 'V20261117000100: units in no carton written off; stock is kept in whole cartons',
       now(), s.lot_id, now(), 'flyway'
  FROM inventory.stock_item s
 WHERE s.stock_package_id IS NULL AND s.on_hand > 0;

UPDATE inventory.stock_item
   SET on_hand = 0, version = version + 1, last_modified_at = now()
 WHERE stock_package_id IS NULL AND on_hand > 0;

-- 3. holds left uncovered by the write-off, newest first
DO $$
DECLARE
    r    RECORD;
    free BIGINT;
    held BIGINT;
BEGIN
    FOR r IN SELECT h.id, h.sku, h.warehouse_code
               FROM inventory.stock_reservation h
              WHERE h.stock_item_id IS NULL AND h.status = 'HELD'
                AND h.sku IN (SELECT sku FROM written_off_sku)
              ORDER BY h.reserved_at DESC, h.id DESC
    LOOP
        SELECT COALESCE(sum(on_hand - reserved), 0) INTO free FROM inventory.stock_item
         WHERE sku = r.sku AND status = 'AVAILABLE';
        SELECT COALESCE(sum(quantity), 0) INTO held FROM inventory.stock_reservation
         WHERE sku = r.sku AND stock_item_id IS NULL AND status = 'HELD';
        IF held <= free AND r.warehouse_code IS NOT NULL THEN
            SELECT COALESCE(sum(on_hand - reserved), 0) INTO free FROM inventory.stock_item
             WHERE sku = r.sku AND status = 'AVAILABLE' AND split_part(location_code, '-', 1) = r.warehouse_code;
            SELECT COALESCE(sum(quantity), 0) INTO held FROM inventory.stock_reservation
             WHERE sku = r.sku AND stock_item_id IS NULL AND status = 'HELD' AND warehouse_code = r.warehouse_code;
        END IF;
        IF held > free THEN
            UPDATE inventory.stock_reservation
               SET status = 'RELEASED', release_reason = 'MANUAL_OVERRIDE', closed_at = now()
             WHERE id = r.id;
        END IF;
    END LOOP;
END $$;

-- 4. the rule
ALTER TABLE inventory.stock_item
    ADD CONSTRAINT ck_stock_item_whole_cartons
        CHECK (stock_package_id IS NOT NULL OR (on_hand = 0 AND reserved = 0));

COMMENT ON CONSTRAINT ck_stock_item_whole_cartons ON inventory.stock_item IS
    'Stock is kept in whole LPN cartons (kltn-docs 20 BR-14): a row in no carton holds nothing and nobody''s hold.';
