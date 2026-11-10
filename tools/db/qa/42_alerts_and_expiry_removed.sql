-- =============================================================================
-- Adversarial checks + happy paths: no stock alerts, no expiry, FIFO only (V20261111000300, team plan 5-1; kltn-docs
-- 20 BR-13, 03 BR-06, 01 BR-17). Needs the demo seed (the lot-tracked oak table, the sofa, a supplier). Rolled back at
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
SELECT pg_temp.expect_true('AE3 no threshold or expiry column left on an item, a stock row, a lot or a receipt line',
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE (table_schema, table_name) IN (('inventory', 'inventory_items'), ('inventory', 'stock_item'),
                                                      ('inventory', 'stock_lot'), ('procurement', 'goods_receipt_lines'),
                                                      ('inventory', 'stock_package_balance'))
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
SELECT pg_temp.expect_true('AE7 the carton view still answers, without an expiry',
    (SELECT count(*) FROM inventory.stock_package_balance) >= 0);
SELECT pg_temp.expect_fail('AE8 a threshold written to an item', $q$
    UPDATE inventory.inventory_items SET reorder_point = 5 WHERE sku = 'SOFA-3S-GREY' $q$);

\echo '--- EXPIRED is read only (StockStatus.EXPIRED, AdjustmentReason.EXPIRED)'
-- A carton of 3 for the rows below: a stock row with units must sit in one (ck_stock_item_whole_cartons).
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa42:u')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 'QA_QA42', 3);
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty, location_code, status)
VALUES (md5('qa42:p')::uuid, 'QA-QA42-1', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa42:u')::uuid, 3, 3, 'HCM-QC01', 'IN_STOCK');
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
SELECT pg_temp.expect_fail_by('AE10 a new stock row EXPIRED (in a carton, so the carton rule is not what refuses it)', 'cannot become EXPIRED', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at, received_at, stock_package_id)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 'HCM-QC01', 'QA-LOT', 1, 0, 'EXPIRED', NOW(), NOW(), md5('qa42:p')::uuid) $q$);
SELECT pg_temp.expect_fail('AE11 stock on hand turned EXPIRED', $q$
    UPDATE inventory.stock_item SET status = 'EXPIRED'
     WHERE id = (SELECT id FROM inventory.stock_item WHERE status = 'AVAILABLE' AND reserved = 0 ORDER BY id LIMIT 1) $q$);
-- A row the old sweep left EXPIRED, made here by switching the guard off for one statement.
ALTER TABLE inventory.stock_item DISABLE TRIGGER tg_stock_item_no_new_expired;
INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at, received_at, stock_package_id)
VALUES (md5('qa42:expired')::uuid, 'SOFA-3S-GREY', 'HCM-QC01', 'QA-LOT', 3, 0, 'EXPIRED', NOW(), NOW() - interval '1 day', md5('qa42:p')::uuid);
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
ALTER TABLE inventory.stock_item ENABLE TRIGGER tg_stock_item_no_new_expired;
SELECT pg_temp.expect_ok('AE12 a row already EXPIRED is still written off', $q$
    UPDATE inventory.stock_package SET status = 'LOST', closed_at = NOW() WHERE id = md5('qa42:p')::uuid;
    UPDATE inventory.stock_item SET on_hand = 0 WHERE id = md5('qa42:expired')::uuid $q$);
SELECT pg_temp.expect_ok('AE13 ... and may leave EXPIRED', $q$
    UPDATE inventory.stock_item SET status = 'BLOCKED' WHERE id = md5('qa42:expired')::uuid $q$);
SELECT pg_temp.expect_fail('AE14 ... but not come back to it', $q$
    UPDATE inventory.stock_item SET status = 'EXPIRED' WHERE id = md5('qa42:expired')::uuid $q$);
SELECT pg_temp.expect_fail('AE15 a new adjustment for EXPIRED stock', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code, requested_by)
    VALUES (gen_random_uuid(), 'ADJ-QA42-1', 'HCM-QC01', 'SOFA-3S-GREY', -1, 'EXPIRED', md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_ok('AE16 an adjustment for DAMAGED stock', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code, requested_by)
    VALUES (md5('qa42:adj')::uuid, 'ADJ-QA42-2', 'HCM-QC01', 'SOFA-3S-GREY', -1, 'DAMAGED', md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('AE17 an adjustment changed to EXPIRED afterwards', $q$
    UPDATE inventory.stock_adjustment SET reason_code = 'EXPIRED' WHERE id = md5('qa42:adj')::uuid $q$);
ALTER TABLE inventory.stock_adjustment DISABLE TRIGGER tg_stock_adjustment_no_new_expired;
INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code, requested_by)
VALUES (md5('qa42:adj:old')::uuid, 'ADJ-QA42-3', 'HCM-QC01', 'SOFA-3S-GREY', -1, 'EXPIRED', md5('demo:user:editor')::uuid);
ALTER TABLE inventory.stock_adjustment ENABLE TRIGGER tg_stock_adjustment_no_new_expired;
SELECT pg_temp.expect_ok('AE18 an EXPIRED adjustment recorded before is still decided', $q$
    UPDATE inventory.stock_adjustment SET status = 'REJECTED', decided_by = md5('demo:user:approver')::uuid,
           decided_at = NOW(), rejection_reason = 'qa' WHERE id = md5('qa42:adj:old')::uuid $q$);

\echo '--- lots without expiry (SCRUM-142): one per receipt, SKU and lot number'
-- A historical (PURCHASE) receipt header, written with the row triggers off: purchasing was removed and
-- check_goods_receipt refuses one (QA 61), but a lot an old receipt opened is all AE20-AE22 are about.
SET LOCAL session_replication_role = replica;
INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, received_at, received_by, receipt_type)
VALUES (md5('qa42:gr')::uuid, 'GR-QA42-1', md5('demo:wh:HCM')::uuid, NOW(), md5('demo:user:editor')::uuid, 'PURCHASE');
SET LOCAL session_replication_role = origin;
SELECT pg_temp.expect_ok('AE20 a receipt opens a lot of the table', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, goods_receipt_id)
    VALUES (md5('qa42:lot:1')::uuid, 'TABLE-OAK-160', 'LOT-QA42', NOW(), 'GOODS_RECEIPT', md5('qa42:gr')::uuid) $q$);
SELECT pg_temp.expect_fail('AE21 the same receipt opening a second lot of that number (the expiry no longer tells them apart)', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, goods_receipt_id)
    VALUES (gen_random_uuid(), 'TABLE-OAK-160', 'LOT-QA42', NOW(), 'GOODS_RECEIPT', md5('qa42:gr')::uuid) $q$);
SELECT pg_temp.expect_ok('AE22 another lot number on the same receipt', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, goods_receipt_id)
    VALUES (gen_random_uuid(), 'TABLE-OAK-160', 'LOT-QA42-B', NOW(), 'GOODS_RECEIPT', md5('qa42:gr')::uuid) $q$);
-- Two cartons of one oak table each (a row with units must sit in a carton).
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
SELECT md5('qa42:uo')::uuid, i.id, 'QA_QA42O', 1 FROM inventory.inventory_items i WHERE i.sku = 'TABLE-OAK-160';
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty, location_code, status)
SELECT md5('qa42:po:' || k)::uuid, 'QA-QA42-OAK-' || k, i.id, md5('qa42:uo')::uuid, 1, 1, loc, 'IN_STOCK'
  FROM inventory.inventory_items i, (VALUES ('1', 'HCM-QC01'), ('2', 'HCM-RCV01')) v(k, loc) WHERE i.sku = 'TABLE-OAK-160';
SELECT pg_temp.expect_ok('AE23 a stock row without a lot id finds the untraced lot of its SKU, number and receipt time', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at, received_at, stock_package_id)
    VALUES (md5('qa42:row:1')::uuid, 'TABLE-OAK-160', 'HCM-QC01', 'LOT-QA42-U', 1, 0, 'QUARANTINE', NOW(),
            '2026-01-02 03:04:05+00', md5('qa42:po:1')::uuid);
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at, received_at, stock_package_id)
    VALUES (md5('qa42:row:2')::uuid, 'TABLE-OAK-160', 'HCM-RCV01', 'LOT-QA42-U', 1, 0, 'INBOUND', NOW(),
            '2026-01-02 03:04:05+00', md5('qa42:po:2')::uuid) $q$);
SELECT pg_temp.expect_true('AE24 ... the same lot for both rows, an UNTRACED one',
    (SELECT count(DISTINCT lot_id) FROM inventory.stock_item WHERE id IN (md5('qa42:row:1')::uuid, md5('qa42:row:2')::uuid)) = 1
    AND (SELECT l.origin_type FROM inventory.stock_lot l JOIN inventory.stock_item s ON s.lot_id = l.id
          WHERE s.id = md5('qa42:row:1')::uuid) = 'UNTRACED');
SELECT pg_temp.expect_fail('AE25 a stock row put in a lot of another lot number', $q$
    UPDATE inventory.stock_item SET lot_id = md5('qa42:lot:1')::uuid WHERE id = md5('qa42:row:1')::uuid $q$);
SELECT pg_temp.expect_fail('AE26 a stock row received in the future (the receipt-time rule stays)', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at, received_at)
    VALUES (gen_random_uuid(), 'TABLE-OAK-160', 'HCM-QC01', 'LOT-QA42-F', 1, 0, 'QUARANTINE', NOW(),
            clock_timestamp() + interval '1 hour') $q$);
SELECT pg_temp.expect_fail('AE27 a SKU stocked without a lot (the policy rule stays, and holds for every SKU since 5-5)', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at)
    VALUES (gen_random_uuid(), 'TABLE-OAK-160', 'HCM-QC01', 1, 0, 'QUARANTINE', NOW(), NOW()) $q$);

-- Receiving records no expiry (03 BR-06): a receipt line has no expiry column to fill, and every line names its lot
-- (QA 70, V20261115000100); the lines themselves are only ever of a production order (QA 22, QA 61).

ROLLBACK;
\echo 'PASS 42_alerts_and_expiry_removed'
