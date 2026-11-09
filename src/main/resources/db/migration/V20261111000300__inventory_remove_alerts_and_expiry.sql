-- =============================================================================
-- No stock alerts, no expiry, FIFO only (team plan 5-1; kltn-docs 20 BR-13, 03 BR-06, 01 BR-17).
--
-- The module raises no stock alert: a SKU printed to a customer's design is made to order, and what an order lacks
-- is answered by a production order, not by a reorder point (20 BR-13). Cups and packaging have no expiry date: a
-- receipt records none, nothing warns near one, and stock leaves first in, first out by the day it was received
-- (03 BR-06, 01 BR-17). What served the other way goes:
--
--   1. the stock alerts: inventory.stock_alert, the dirty queue inventory.stock_alert_dirty and the three triggers on
--      inventory.stock_item that fed it (trg_stock_item_dirty_*, inventory.mark_stock_alert_dirty);
--   2. the thresholds and expiry settings of an inventory item: reorder_point, safety_stock, max_qty, expiry_tracked,
--      max_shelf_life_days, with their CHECKs;
--   3. expiry dates: inventory.stock_item.expiry_date, inventory.stock_lot.expiry_date and
--      procurement.goods_receipt_lines.expiry_date. A lot of a receipt is now one per (receipt, SKU, lot number):
--      uk_stock_lot_receipt loses the expiry, and a receipt that opened two lots of one number told apart by expiry
--      alone stops this migration, named (none is known; the receiving code never wrote one);
--   4. the functions and the view that read those columns are rewritten without them: inventory.check_receivable_item,
--      inventory.enforce_sku_policy, inventory.resolve_stock_item_lot, procurement.check_goods_receipt_line_frozen and
--      the view inventory.stock_package_balance (which loses its expiry_date column);
--   5. the permission inventory-alerts (every role that held it moves its version, ADR-0008);
--   6. event publications of the two events that only the alerts used (InventoryAlertsRaised,
--      SkuInventoryControlChanged): their listeners are gone, so a publication left incomplete would be retried
--      for ever against a class that no longer exists.
--
-- Kept, read only: the stock status EXPIRED and the adjustment reason EXPIRED, which rows recorded before carry. Nothing
-- enters either from here: StockStatus / AdjustmentReason say so first (the request path refuses EXPIRED), and two
-- triggers say it again (tg_stock_item_no_new_expired, tg_stock_adjustment_no_new_expired). The ledger reference
-- EXPIRY_SWEEP stays in ck_stock_movement_reference for the lines the sweep wrote.
--
-- Not here: inventory_items.removal_strategy. Allocation no longer reads it (StockAllocator is FIFO for every SKU), and
-- the configurable policy goes as a whole in team plan 5-5.
--
-- Every value dropped is archived in platform.legacy_archive first: the alert rows whole, the queue rows whole, and for
-- the columns the row id with the values it loses.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 0. Refuse what cannot be carried over
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    clash text;
BEGIN
    SELECT string_agg(format('%s / %s / %s', l.goods_receipt_id, l.sku, l.lot), ', ')
      INTO clash
      FROM (SELECT goods_receipt_id, sku, coalesce(lot_number, '') AS lot
              FROM inventory.stock_lot
             WHERE goods_receipt_id IS NOT NULL
             GROUP BY goods_receipt_id, sku, coalesce(lot_number, '')
            HAVING count(*) > 1) l;
    IF clash IS NOT NULL THEN
        RAISE EXCEPTION 'V20261111000300: these receipts opened two lots of one SKU and lot number told apart only by expiry; merge them by hand first: %', clash;
    END IF;
END
$$;


-- ---------------------------------------------------------------------------------------------
-- 1. Archive
-- ---------------------------------------------------------------------------------------------
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'inventory.stock_alert', a.id, to_jsonb(a) FROM inventory.stock_alert a;

-- The queue's key is a bigint, not a uuid: kept in the row data.
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'inventory.stock_alert_dirty', NULL, to_jsonb(d) FROM inventory.stock_alert_dirty d;

INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'inventory.inventory_items', i.id,
       jsonb_build_object('sku', i.sku, 'reorder_point', i.reorder_point, 'safety_stock', i.safety_stock,
                          'max_qty', i.max_qty, 'expiry_tracked', i.expiry_tracked,
                          'max_shelf_life_days', i.max_shelf_life_days)
  FROM inventory.inventory_items i
 WHERE i.reorder_point IS NOT NULL OR i.safety_stock IS NOT NULL OR i.max_qty IS NOT NULL OR i.expiry_tracked
    OR i.max_shelf_life_days IS NOT NULL;

INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'inventory.stock_item', s.id, jsonb_build_object('sku', s.sku, 'lot_number', s.lot_number,
                                                        'expiry_date', s.expiry_date)
  FROM inventory.stock_item s
 WHERE s.expiry_date IS NOT NULL;

INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'inventory.stock_lot', l.id, jsonb_build_object('sku', l.sku, 'lot_number', l.lot_number,
                                                       'expiry_date', l.expiry_date)
  FROM inventory.stock_lot l
 WHERE l.expiry_date IS NOT NULL;

INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'procurement.goods_receipt_lines', g.id, jsonb_build_object('receipt_id', g.receipt_id,
                                                                   'lot_number', g.lot_number,
                                                                   'expiry_date', g.expiry_date)
  FROM procurement.goods_receipt_lines g
 WHERE g.expiry_date IS NOT NULL;

-- Publications of the two events only the alerts used, still waiting for a listener that is gone.
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'public.event_publication', e.id, to_jsonb(e)
  FROM event_publication e
 WHERE e.event_type IN ('com.stockflow.contracts.InventoryAlertsRaised',
                        'com.stockflow.contracts.SkuInventoryControlChanged')
   AND e.completion_date IS NULL;

COMMENT ON TABLE platform.legacy_archive IS
    'Rows of tables dropped by contracts C1-C4 (V20261011000200), by the removal of in-house production '
        '(V20261108000100), of receiving QC (V20261108000200), of transfers between warehouses (V20261108000300), '
        'of parcel shipping, the delivery-mode choice and COD (V20261108000400) and of the design check at packing '
        '(V20261108000500), the customization templates and print areas as they were before templates got a model '
        '(V20261108000600), design statuses renamed when customers began designing (V20261108000800), the staff '
        'design workflow''s assignments, its designs left waiting for confirmation and its permission '
        '(V20261108001000), the parcel carriers'' status before they were retired (V20261108001300), the stock '
        'rows, cartons, holds and open pick lines as they were before loose stock went into cartons '
        '(V20261111000110), and the stock alerts, their queue, the items'' thresholds and expiry settings, the expiry '
        'dates of stock, lots and receipt lines and the alerts'' pending event publications (V20261111000300). '
        'Read-only history.';


-- ---------------------------------------------------------------------------------------------
-- 2. Stock alerts
-- ---------------------------------------------------------------------------------------------
DROP TRIGGER trg_stock_item_dirty_insert ON inventory.stock_item;
DROP TRIGGER trg_stock_item_dirty_update ON inventory.stock_item;
DROP TRIGGER trg_stock_item_dirty_delete ON inventory.stock_item;
DROP FUNCTION inventory.mark_stock_alert_dirty();
DROP TABLE inventory.stock_alert;
DROP TABLE inventory.stock_alert_dirty;

DELETE FROM event_publication
 WHERE event_type IN ('com.stockflow.contracts.InventoryAlertsRaised',
                      'com.stockflow.contracts.SkuInventoryControlChanged');


-- ---------------------------------------------------------------------------------------------
-- 3. The functions and the view that read a dropped column, without it
-- ---------------------------------------------------------------------------------------------
-- A receipt line asks its item for a weight, dimensions and, when lot-tracked, a lot (BR-03). No expiry date.
CREATE OR REPLACE FUNCTION inventory.check_receivable_item()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    item inventory.inventory_items%ROWTYPE;
BEGIN
    SELECT * INTO item FROM inventory.inventory_items WHERE id = NEW.inventory_item_id;
    IF item.weight_kg IS NULL OR item.length_cm IS NULL OR item.width_cm IS NULL OR item.height_cm IS NULL THEN
        RAISE EXCEPTION 'inventory item % has no weight/dimensions yet; complete it before receiving (BR-03)', item.sku
            USING ERRCODE = 'check_violation';
    END IF;
    IF item.lot_tracked AND NEW.lot_number IS NULL THEN
        RAISE EXCEPTION 'inventory item % is lot-tracked; the receipt line needs a lot number', item.sku
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

-- V20261017000450's policy check without the expiry rules: a layer's identity is its SKU, receipt time, lot and serial.
CREATE OR REPLACE FUNCTION inventory.enforce_sku_policy()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    p inventory.inventory_items%ROWTYPE;
BEGIN
    -- Holds/consumption already lock individual rows in id order. Taking a SKU-wide lock here
    -- would invert that order when two reservations span different rows of the same SKU.
    IF TG_OP = 'UPDATE' AND NEW.sku IS NOT DISTINCT FROM OLD.sku
       AND NEW.received_at IS NOT DISTINCT FROM OLD.received_at
       AND NEW.lot_number IS NOT DISTINCT FROM OLD.lot_number
       AND NEW.serial_number IS NOT DISTINCT FROM OLD.serial_number
       AND NEW.on_hand <= OLD.on_hand THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.sku, 701));
    SELECT * INTO p FROM inventory.inventory_items WHERE sku = NEW.sku;
    IF NOT FOUND OR NOT p.policy_configured THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.sku IS DISTINCT FROM OLD.sku OR NEW.received_at IS DISTINCT FROM OLD.received_at
           OR NEW.lot_number IS DISTINCT FROM OLD.lot_number OR NEW.serial_number IS DISTINCT FROM OLD.serial_number THEN
            RAISE EXCEPTION 'Receipt identity is immutable; reconcile via a new stock layer' USING ERRCODE = '23514';
        END IF;
        IF NEW.on_hand <= OLD.on_hand THEN
            RETURN NEW;
        END IF;
        -- Replenishment must be a new layer; otherwise FIFO loses the age of incoming units. Units of this
        -- very receipt moved in from another location (identity unchanged, checked just above) are not
        -- replenishment: the application names the row it merges into for that one statement.
        IF p.removal_strategy = 'FIFO'
           AND current_setting('stockflow.same_receipt_merge', true) IS DISTINCT FROM NEW.id::text THEN
            RAISE EXCEPTION 'FIFO replenishment requires a new receipt layer' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.on_hand = 0 THEN
        RETURN NEW;
    END IF;
    IF (p.lot_tracked AND nullif(trim(NEW.lot_number), '') IS NULL)
       OR (p.serial_tracked AND (nullif(trim(NEW.serial_number), '') IS NULL OR NEW.on_hand > 1))
       OR (NOT p.serial_tracked AND NEW.serial_number IS NOT NULL)
       OR (p.removal_strategy = 'FIFO' AND NEW.received_at IS NULL) THEN
        RAISE EXCEPTION 'Stock does not satisfy configured SKU policy' USING ERRCODE = '23514';
    END IF;
    IF NEW.received_at > clock_timestamp() + interval '1 minute' THEN
        RAISE EXCEPTION 'Invalid receipt date' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

-- A stock row finds its lot by SKU, lot number and receipt time (SCRUM-142), no longer by expiry.
CREATE OR REPLACE FUNCTION inventory.resolve_stock_item_lot()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    lot inventory.stock_lot%ROWTYPE;
BEGIN
    IF NEW.lot_id IS NULL THEN
        SELECT id INTO NEW.lot_id FROM inventory.stock_lot l
         WHERE l.sku = NEW.sku AND l.lot_number IS NOT DISTINCT FROM NEW.lot_number
           AND l.received_at IS NOT DISTINCT FROM NEW.received_at
         ORDER BY l.created_at, l.id LIMIT 1;
        IF NEW.lot_id IS NULL THEN
            NEW.lot_id := gen_random_uuid();
            INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at, created_by)
            VALUES (NEW.lot_id, NEW.sku, NEW.lot_number, NEW.received_at, 'UNTRACED', NOW(), NEW.created_by);
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'INSERT' OR NEW.lot_id IS DISTINCT FROM OLD.lot_id THEN
        SELECT * INTO lot FROM inventory.stock_lot WHERE id = NEW.lot_id;
        IF lot.sku IS DISTINCT FROM NEW.sku OR lot.lot_number IS DISTINCT FROM NEW.lot_number THEN
            RAISE EXCEPTION 'stock item % (%, lot %) cannot belong to lot % (%, lot %)', NEW.id, NEW.sku,
                NEW.lot_number, NEW.lot_id, lot.sku, lot.lot_number USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- A posted receipt's lines keep what was counted (BR-05); the expiry is no longer part of it.
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt_line_frozen()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    receipt_status VARCHAR(16);
BEGIN
    SELECT status INTO receipt_status FROM procurement.goods_receipts
     WHERE id = COALESCE(NEW.receipt_id, OLD.receipt_id);
    IF receipt_status IS NULL OR receipt_status IN ('DRAFT', 'PENDING_APPROVAL') THEN
        RETURN COALESCE(NEW, OLD);
    END IF;
    IF TG_OP = 'UPDATE'
       AND NEW.receipt_id = OLD.receipt_id AND NEW.po_line_id IS NOT DISTINCT FROM OLD.po_line_id
       AND NEW.inventory_item_id = OLD.inventory_item_id AND NEW.location_id = OLD.location_id
       AND NEW.received_qty = OLD.received_qty
       AND NEW.lot_number IS NOT DISTINCT FROM OLD.lot_number
       AND NEW.uom_conversion_id IS NOT DISTINCT FROM OLD.uom_conversion_id THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'receipt is %: its lines keep what was counted (BR-05)', receipt_status
        USING ERRCODE = 'check_violation';
END
$$;

-- The carton view without expiry_date: a column cannot leave a view through CREATE OR REPLACE.
DROP VIEW inventory.stock_package_balance;
CREATE VIEW inventory.stock_package_balance AS
SELECT p.id                                                                         AS stock_package_id,
       p.lpn,
       i.sku,
       p.inventory_item_id,
       p.uom_conversion_id,
       u.unit_of_measure,
       p.nominal_qty,
       p.received_qty,
       p.location_code,
       p.status,
       p.unopened,
       max(s.lot_number::text) FILTER (WHERE s.on_hand > 0)                         AS lot_number,
       COALESCE(sum(s.on_hand), 0::bigint)                                          AS on_hand,
       COALESCE(sum(s.reserved), 0::bigint)                                         AS reserved,
       COALESCE(sum(s.on_hand - s.reserved) FILTER (WHERE s.status::text = 'AVAILABLE'), 0::bigint) AS available,
       p.goods_receipt_line_id,
       p.closed_at
  FROM inventory.stock_package p
  JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
  JOIN inventory.uom_conversions u ON u.id = p.uom_conversion_id
  LEFT JOIN inventory.stock_item s ON s.stock_package_id = p.id
 GROUP BY p.id, i.sku, u.unit_of_measure;

COMMENT ON VIEW inventory.stock_package_balance IS
    'One row per carton: its label, unit, place and status, and what its stock rows hold. Read-only.';


-- ---------------------------------------------------------------------------------------------
-- 4. The columns
-- ---------------------------------------------------------------------------------------------
ALTER TABLE inventory.inventory_items
    DROP CONSTRAINT IF EXISTS ck_inventory_items_expiry_needs_lot,
    DROP CONSTRAINT IF EXISTS ck_inventory_control_thresholds,
    DROP CONSTRAINT IF EXISTS ck_inventory_control_expiry,
    DROP CONSTRAINT IF EXISTS ck_inventory_items_thresholds,
    DROP COLUMN reorder_point,
    DROP COLUMN safety_stock,
    DROP COLUMN max_qty,
    DROP COLUMN expiry_tracked,
    DROP COLUMN max_shelf_life_days;

DROP INDEX IF EXISTS inventory.ix_stock_item_expiry;
ALTER TABLE inventory.stock_item DROP COLUMN expiry_date;

DROP INDEX inventory.uk_stock_lot_receipt;
ALTER TABLE inventory.stock_lot DROP COLUMN expiry_date;
-- One lot per receipt, SKU and lot number (SCRUM-142): StockLotsAdapter.openForReceipt finds it by the same three.
CREATE UNIQUE INDEX uk_stock_lot_receipt ON inventory.stock_lot (goods_receipt_id, sku, COALESCE(lot_number, ''))
    WHERE goods_receipt_id IS NOT NULL;

ALTER TABLE procurement.goods_receipt_lines DROP COLUMN expiry_date;


-- ---------------------------------------------------------------------------------------------
-- 5. EXPIRED, read only
-- ---------------------------------------------------------------------------------------------
-- A row recorded EXPIRED stays so (it may still be written off); none becomes EXPIRED. StockStatus.EXPIRED says so first.
CREATE FUNCTION inventory.refuse_new_expired_stock()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status = 'EXPIRED' AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM 'EXPIRED') THEN
        RAISE EXCEPTION 'stock item % cannot become EXPIRED: stock has no expiry date (20 BR-13)', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_item_no_new_expired
    BEFORE INSERT OR UPDATE OF status ON inventory.stock_item
    FOR EACH ROW EXECUTE FUNCTION inventory.refuse_new_expired_stock();

-- An adjustment recorded with the reason EXPIRED keeps it (its review and posting go on); none is raised with it.
-- StockOperationsServiceImpl.requestAdjustment refuses it first.
CREATE FUNCTION inventory.refuse_new_expired_adjustment()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.reason_code = 'EXPIRED' AND (TG_OP = 'INSERT' OR OLD.reason_code IS DISTINCT FROM 'EXPIRED') THEN
        RAISE EXCEPTION 'stock adjustment % cannot be for EXPIRED stock: stock has no expiry date (20 BR-13)', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_adjustment_no_new_expired
    BEFORE INSERT OR UPDATE OF reason_code ON inventory.stock_adjustment
    FOR EACH ROW EXECUTE FUNCTION inventory.refuse_new_expired_adjustment();


-- ---------------------------------------------------------------------------------------------
-- 6. Permission
-- ---------------------------------------------------------------------------------------------
-- The alert screen is gone. Every role that held a grant of it moves its version, so cached grants are dropped at once
-- (ADR-0008).
WITH lost AS (
    DELETE FROM identity.role_permission rp
    USING identity.permission p
    WHERE rp.permission_id = p.id
      AND p.resource = 'inventory-alerts'
    RETURNING rp.role_id
)
UPDATE identity.app_role r
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE r.id IN (SELECT DISTINCT role_id FROM lost);

DELETE FROM identity.permission WHERE resource = 'inventory-alerts';
