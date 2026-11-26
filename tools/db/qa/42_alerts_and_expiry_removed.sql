-- =============================================================================
-- Adversarial checks + happy paths: no stock alerts, no expiry, FIFO only (V20261111000300, team plan 5-1; kltn-docs
-- 20 BR-13, 03 BR-06, 01 BR-17). Needs the demo seed (the two demo cups, the red one in lots, and a supplier). Rolled back at
-- the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        -- Deferred constraints and constraint triggers fire here, still inside the sub-transaction.
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'ok    %  [%]', label, left(SQLERRM, 110);
        RETURN;
    END;
    RAISE EXCEPTION 'FAIL  %: the database accepted it', label;
END $$;

CREATE FUNCTION pg_temp.expect_ok(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE stmt;
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
    RAISE NOTICE 'ok    %', label;
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'FAIL  %: %', label, SQLERRM;
END $$;

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

\echo '--- what served the alerts and expiry is gone'
SELECT pg_temp.expect_true('AE1 no stock alert table and no dirty queue',
    to_regclass('inventory.stock_alert') IS NULL AND to_regclass('inventory.stock_alert_dirty') IS NULL);
SELECT pg_temp.expect_true('AE2 no trigger on stock rows feeds a queue, and its function is gone',
    NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname LIKE 'trg_stock_item_dirty%')
    AND to_regprocedure('inventory.mark_stock_alert_dirty()') IS NULL);
SELECT pg_temp.expect_true('AE3 no threshold or expiry column left on an item, a carton, a lot or a receipt line',
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE (table_schema, table_name) IN (('inventory', 'inventory_items'), ('inventory', 'stock_package'),
                                                      ('inventory', 'stock_lot'), ('procurement', 'goods_receipt_lines'))
                   AND column_name IN ('expiry_date', 'expiry_tracked', 'max_shelf_life_days', 'reorder_point',
                                       'safety_stock', 'max_qty')));
SELECT pg_temp.expect_true('AE4 no function still reads a dropped column',
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname IN ('inventory', 'procurement', 'warehouse', 'fulfillment', 'ordering')
                   -- check_goods_receipt_line(_frozen) read expiry_date only as a jsonb key of the row (to_jsonb(NEW) -> 'expiry_date'),
                   -- on purpose: they ran before and after the column went, and a column that is gone reads as NULL there.
                   AND regexp_replace(p.prosrc, '->>? ''expiry_date''', '', 'g')
                       ~ '(expiry_date|expiry_tracked|max_shelf_life_days|reorder_point|safety_stock|stock_alert)'));
SELECT pg_temp.expect_true('AE5 the alert permission is gone, from every role',
    NOT EXISTS (SELECT 1 FROM identity.permission WHERE resource = 'inventory-alerts' OR code LIKE 'inventory-alerts:%'));
SELECT pg_temp.expect_true('AE6 no publication of an event only the alerts used is left to retry',
    NOT EXISTS (SELECT 1 FROM event_publication
                 WHERE event_type IN ('com.stockflow.contracts.InventoryAlertsRaised',
                                      'com.stockflow.contracts.SkuInventoryControlChanged')));
SELECT pg_temp.expect_true('AE6b no in-app message of a stock alert is left, linking to a page that is gone',
    NOT EXISTS (SELECT 1 FROM notification.user_notification
                 WHERE category = 'INVENTORY_ALERT' OR source_type = 'STOCK_ALERT' OR link LIKE '%/inventory/alerts%'));
SELECT pg_temp.expect_true('AE7 the carton view is gone with the stock rows it joined (V20261231000600)',
    to_regclass('inventory.stock_package_balance') IS NULL);
SELECT pg_temp.expect_fail('AE8 a threshold written to an item', $q$
    UPDATE inventory.inventory_items SET reorder_point = 5 WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH' $q$);

\echo '--- EXPIRED is read only (StockStatus.EXPIRED, AdjustmentReason.EXPIRED)'
-- Two cartons of 3 for the checks below, in quarantine: the carton is the stock (V20261231000600).
-- 01 BR-03: an item has ONE active carton. The demo seed's own is retired, so the fixture's unit below is the one.
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND variant_id IN (SELECT variant_id FROM inventory.inventory_items WHERE sku IN ('LY-GIAY-DEMO-12OZ-XANH'));
INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor)
VALUES (md5('qa42:u')::uuid, (SELECT variant_id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'QA_QA42', 3);
INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at)
VALUES (md5('qa42:lot:x')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 'QA-LOT', NOW() - interval '1 day', 'UNTRACED', NOW());
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at, location_code, status,
                                     stock_status, lot_id, received_at)
VALUES (md5('qa42:p')::uuid, 'QA-QA42-1', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa42:u')::uuid, 3, NOW(), 'HCM-QC01', 'IN_STOCK',
        'QUARANTINE', md5('qa42:lot:x')::uuid, NOW() - interval '1 day'),
       (md5('qa42:p2')::uuid, 'QA-QA42-2', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa42:u')::uuid, 3, NOW(), 'HCM-QC01', 'IN_STOCK',
        'QUARANTINE', md5('qa42:lot:x')::uuid, NOW() - interval '1 day');
CREATE FUNCTION pg_temp.expect_fail_by(label TEXT, rule TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM NOT LIKE '%' || rule || '%' THEN
            RAISE EXCEPTION 'FAIL  %: refused, but not by %: %', label, rule, SQLERRM;
        END IF;
        RAISE NOTICE 'ok    %  [%]', label, rule;
        RETURN;
    END;
    RAISE EXCEPTION 'FAIL  %: the database accepted it', label;
END $$;
SELECT pg_temp.expect_fail_by('AE10 a new carton EXPIRED', 'cannot become EXPIRED', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at, location_code, status,
                                         stock_status, lot_id, received_at)
    VALUES (gen_random_uuid(), 'QA-QA42-E', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa42:u')::uuid, 3, NOW(), 'HCM-QC01', 'IN_STOCK',
            'EXPIRED', md5('qa42:lot:x')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail_by('AE11 stock on hand turned EXPIRED', 'cannot become EXPIRED', $q$
    UPDATE inventory.stock_package SET stock_status = 'EXPIRED'
     WHERE id = (SELECT p.id FROM inventory.stock_package p WHERE p.stock_status = 'AVAILABLE'
                    AND NOT EXISTS (SELECT 1 FROM inventory.stock_reservation a
                                     WHERE a.stock_package_id = p.id AND a.status = 'HELD') ORDER BY p.id LIMIT 1) $q$);
-- Two cartons the old sweep left EXPIRED, made here by switching the guard off for one statement.
ALTER TABLE inventory.stock_package DISABLE TRIGGER tg_stock_package_lot;
UPDATE inventory.stock_package SET stock_status = 'EXPIRED' WHERE id IN (md5('qa42:p')::uuid, md5('qa42:p2')::uuid);
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
ALTER TABLE inventory.stock_package ENABLE TRIGGER tg_stock_package_lot;
SELECT pg_temp.expect_ok('AE12 a carton already EXPIRED is still written off', $q$
    UPDATE inventory.stock_package SET status = 'LOST', stock_status = NULL, closed_at = NOW() WHERE id = md5('qa42:p2')::uuid $q$);
SELECT pg_temp.expect_ok('AE13 ... and may leave EXPIRED', $q$
    UPDATE inventory.stock_package SET stock_status = 'BLOCKED' WHERE id = md5('qa42:p')::uuid $q$);
SELECT pg_temp.expect_fail_by('AE14 ... but not come back to it', 'cannot become EXPIRED', $q$
    UPDATE inventory.stock_package SET stock_status = 'EXPIRED' WHERE id = md5('qa42:p')::uuid $q$);
-- An adjustment names its carton and a direction (V20261231000100): the carton of this section, at HCM-QC01.
SELECT pg_temp.expect_fail_by('AE15 a new adjustment for EXPIRED stock', 'cannot be for EXPIRED stock', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code, requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA42-1', 'HCM-QC01', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'EXPIRED', md5('demo:user:editor')::uuid, md5('qa42:p')::uuid) $q$);
SELECT pg_temp.expect_ok('AE16 an adjustment for DAMAGED stock', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code, requested_by, stock_package_id)
    VALUES (md5('qa42:adj')::uuid, 'ADJ-QA42-2', 'HCM-QC01', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'DAMAGED', md5('demo:user:editor')::uuid, md5('qa42:p')::uuid) $q$);
SELECT pg_temp.expect_fail_by('AE17 an adjustment changed to EXPIRED afterwards', 'cannot be for EXPIRED stock', $q$
    UPDATE inventory.stock_adjustment SET reason_code = 'EXPIRED' WHERE id = md5('qa42:adj')::uuid $q$);
-- One adjustment of a carton waits at a time (uk_stock_adjustment_pending_package): AE16's is withdrawn before the
-- old one is written. An EXPIRED adjustment of the history is written past both guards of a new one: the reason
-- trigger, and the list of reasons a new adjustment gives (tg_stock_adjustment_whole_carton).
UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa42:adj')::uuid;
ALTER TABLE inventory.stock_adjustment DISABLE TRIGGER tg_stock_adjustment_no_new_expired;
ALTER TABLE inventory.stock_adjustment DISABLE TRIGGER tg_stock_adjustment_whole_carton;
INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code, requested_by, stock_package_id)
VALUES (md5('qa42:adj:old')::uuid, 'ADJ-QA42-3', 'HCM-QC01', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'EXPIRED', md5('demo:user:editor')::uuid, md5('qa42:p')::uuid);
ALTER TABLE inventory.stock_adjustment ENABLE TRIGGER tg_stock_adjustment_whole_carton;
ALTER TABLE inventory.stock_adjustment ENABLE TRIGGER tg_stock_adjustment_no_new_expired;
SELECT pg_temp.expect_ok('AE18 an EXPIRED adjustment recorded before is still decided', $q$
    UPDATE inventory.stock_adjustment SET status = 'REJECTED', decided_by = md5('demo:user:approver')::uuid,
           decided_at = NOW(), rejection_reason = 'qa' WHERE id = md5('qa42:adj:old')::uuid $q$);

\echo '--- lots without expiry (SCRUM-142): a carton names its lot'
SELECT pg_temp.expect_ok('AE20 a lot of the red cup', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type)
    VALUES (md5('qa42:lot:1')::uuid, 'LY-GIAY-DEMO-12OZ-DO', 'LOT-QA42', NOW(), 'UNTRACED') $q$);
-- AE23, AE24 (a stock row without a lot id finds the untraced lot of its SKU, number and receipt time) and AE25 (a row
-- put in a lot of another lot number) were tg_stock_item_lot: a carton has no lot number of its own, so there is nothing
-- to resolve a lot from or to disagree with it - the application names the lot (StockLots.openUntraced).
-- The red cup's carton of 500 is, since V20261231000700, a retired carton of the cup's variant (its stock keeps it):
-- it is made the variant's active carton again for these cartons, as a person would before receiving in it (BR-03: one
-- active at a time).
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND variant_id = (SELECT variant_id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-DO');
UPDATE inventory.uom_conversions SET active = TRUE WHERE id = md5('demo:cups:unit:LY-GIAY-DEMO-12OZ-DO')::uuid;
CREATE FUNCTION pg_temp.red_carton_sql(k TEXT, loc TEXT, stock TEXT, lot TEXT, received TEXT) RETURNS TEXT LANGUAGE sql AS $f$
    SELECT format($i$
        INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at,
                                             location_code, status, stock_status, lot_id, received_at)
        SELECT md5('qa42:po:' || %L)::uuid, 'QA-QA42-DO-' || %L,
               (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-DO'), u.id, u.factor, NOW(), %L,
               'IN_STOCK', %L, %s, %s
          FROM inventory.uom_conversions u WHERE u.id = md5('demo:cups:unit:LY-GIAY-DEMO-12OZ-DO')::uuid $i$,
        k, k, loc, stock, lot, received)
$f$;
SELECT pg_temp.expect_ok('AE23 two cartons of the red cup, 500 cups each, in the one lot they name',
    pg_temp.red_carton_sql('1', 'HCM-QC01', 'QUARANTINE', 'md5(''qa42:lot:1'')::uuid', '''2026-01-02 03:04:05+00''') || ';'
    || pg_temp.red_carton_sql('2', 'HCM-RCV01', 'INBOUND', 'md5(''qa42:lot:1'')::uuid', '''2026-01-02 03:04:05+00'''));
SELECT pg_temp.expect_fail_by('AE25 a carton put in a lot of another SKU', 'but lot',
    pg_temp.red_carton_sql('3', 'HCM-QC01', 'QUARANTINE', 'md5(''qa42:lot:x'')::uuid', 'NOW()'));
SELECT pg_temp.expect_fail_by('AE26 a carton received in the future (the receipt-time rule stays)', 'invalid receipt date',
    pg_temp.red_carton_sql('4', 'HCM-QC01', 'QUARANTINE', 'md5(''qa42:lot:1'')::uuid', 'clock_timestamp() + interval ''1 hour'''));
SELECT pg_temp.expect_fail_by('AE27 a SKU stocked without a lot (the rule stays, and holds for every SKU since 5-5)', 'ck_stock_package_lot',
    pg_temp.red_carton_sql('5', 'HCM-QC01', 'QUARANTINE', 'NULL', 'NOW()'));

-- Receiving records no expiry (03 BR-06): a receipt line has no expiry column to fill, and every line names its lot
-- (QA 70, V20261115000100); the lines themselves are only ever of a production order (QA 22, QA 61).

ROLLBACK;
\echo 'PASS 42_alerts_and_expiry_removed'
