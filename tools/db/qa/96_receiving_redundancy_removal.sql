-- =============================================================================
-- Adversarial checks + happy paths: the receiving-flow leftovers removed (plan 2026-11-20-receiving-redundancy-removal).
-- Needs the demo seed (the HCM warehouse and its RECEIVING area HCM-RCV01). Rolled back at the end. Each section below
-- is one task of the plan; a later task appends its own section, with the same helpers, before the final ROLLBACK.
--
--   A2  purchase receipts and the CONFIRMED status (V20261214000100): a receipt is PRODUCTION or RETURN, a line always
--       names a production order line, and no receipt is stored CONFIRMED.
--   A3  the lot origin GOODS_RECEIPT (V20261214000200): a lot is of a production order or UNTRACED; the columns that named
--       a receipt are gone. A lot stored GOODS_RECEIPT before it is StockLotReceiptOriginMigrationUpgradeTest's.
--   A4  the default purchase unit (V20261214000300): uom_conversions has no default_purchase column, and an inventory item
--       still gets its base unit from create_base_uom().
--   B   one active carton per item (V20261214000400): at most one active non-base unit per inventory item; retired
--       ones stay as history beside it (01 BR-03, BR-10).
--
-- The fixture follows 61_purchasing_removal.sql: a production order written with the row triggers off (its rules are
-- 21_production_orders.sql's), a DRAFT receipt against it, registered with it.
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

-- Refused, and for the reason named: the message says {fragment}. A refusal for any other reason (a typo, a missing
-- column) is not the rule under test.
CREATE FUNCTION pg_temp.expect_fail_like(label TEXT, fragment TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        IF position(fragment IN SQLERRM) = 0 THEN
            RAISE EXCEPTION 'FAIL  %: refused, but for another reason: %', label, SQLERRM;
        END IF;
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

CREATE FUNCTION pg_temp.expect_true(label TEXT, cond TEXT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    result BOOLEAN;
BEGIN
    EXECUTE 'SELECT (' || cond || ')' INTO result;
    IF result IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %: % is not true', label, cond;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;


-- ============================================================================= A2
\echo '--- A2 fixture: a SENT production order of 5 cartons of 10 blue cups at HCM, a DRAFT receipt against it'
-- The demo cup already has its one active carton (01 BR-03, B below): retired, so the fixture's own is the one.
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND inventory_item_id = (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH');
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, length_cm, width_cm, height_cm, weight_kg)
VALUES (md5('qa96:carton')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'CARTON10', 10, 60, 40, 50, 12);
SET LOCAL session_replication_role = replica;
INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id,
                                       receiving_warehouse_id, need_by_date, status, sample_approval_required,
                                       sample_required_by_default, drawn_up_by, sent_by, sent_at)
VALUES (md5('qa96:prod')::uuid, 'PRD-20261109-9601', md5('qa96:so')::uuid, md5('qa96:batch')::uuid,
        md5('qa96:cust')::uuid, md5('qa96:producer')::uuid, md5('demo:wh:HCM')::uuid, CURRENT_DATE + 30, 'SENT', FALSE,
        FALSE, md5('demo:user:editor')::uuid, md5('demo:user:editor')::uuid, NOW());
INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                            design_snapshot_id, print_file_checksum)
VALUES (md5('qa96:prod:line')::uuid, md5('qa96:prod')::uuid, md5('qa96:ol')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 5, 10,
        md5('qa96:snap')::uuid, repeat('d', 64));
SET LOCAL session_replication_role = origin;
INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, production_order_id, warehouse_id, status,
                                        received_at, received_by)
VALUES (md5('qa96:gr')::uuid, 'GR-QA96', 'PRODUCTION', md5('qa96:prod')::uuid, md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(),
        md5('demo:user:editor')::uuid);
INSERT INTO ordering.production_order_receipt (production_order_id, goods_receipt_id, receipt_number, status, opened_at)
VALUES (md5('qa96:prod')::uuid, md5('qa96:gr')::uuid, 'GR-QA96', 'DRAFT', NOW());
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

\echo '--- A2 no purchase receipt, no CONFIRMED status, no line without a production order line'
SELECT pg_temp.expect_fail_like('A2.1 a PURCHASE receipt', 'ck_goods_receipts_type', $q$
    SET LOCAL session_replication_role = replica;
    INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, warehouse_id, status, received_at, received_by)
    VALUES (gen_random_uuid(), 'GR-QA96-P', 'PURCHASE', md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(), md5('demo:user:editor')::uuid) $q$);
-- Written with the row triggers off: the row trigger of a line (a line names a production order line exactly when its
-- receipt is against a production order) answers first with triggers on, so the column is what is under test here.
SELECT pg_temp.expect_fail_like('A2.2 a receipt line with no production order line', 'production_order_line_id', $q$
    SET LOCAL session_replication_role = replica;
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, inventory_item_id, location_id, received_qty, lot_number,
                                                 uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa96:gr')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
            md5('demo:loc:HCM-RCV01')::uuid, 10, 'PRD-20261109-9601', md5('qa96:carton')::uuid) $q$);
SELECT pg_temp.expect_fail_like('A2.2b ... and with the triggers on', 'names a production order line', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, inventory_item_id, location_id, received_qty, lot_number,
                                                 uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa96:gr')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
            md5('demo:loc:HCM-RCV01')::uuid, 10, 'PRD-20261109-9601', md5('qa96:carton')::uuid) $q$);
SELECT pg_temp.expect_fail_like('A2.3 a receipt stored CONFIRMED', 'ck_goods_receipts_status', $q$
    UPDATE procurement.goods_receipts SET status = 'CONFIRMED', confirmed_at = NOW(),
           confirmed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa96:gr')::uuid $q$);
SELECT pg_temp.expect_true('A2.4 receipt_type and status name exactly what is left', $c$
    (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_goods_receipts_type') LIKE '%PRODUCTION%'
    AND (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_goods_receipts_type') LIKE '%RETURN%'
    AND (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_goods_receipts_type') NOT LIKE '%PURCHASE%'
    AND (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_goods_receipts_status') NOT LIKE '%CONFIRMED%' $c$);
SELECT pg_temp.expect_true('A2.5 production_order_line_id is NOT NULL and check_goods_receipt no longer names PURCHASE', $c$
    (SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'procurement'
        AND table_name = 'goods_receipt_lines' AND column_name = 'production_order_line_id') = 'NO'
    AND (SELECT prosrc FROM pg_proc WHERE oid = 'procurement.check_goods_receipt()'::regprocedure) NOT LIKE '%PURCHASE%' $c$);

\echo '--- A2 happy path: a production receipt still takes a line, counts and is posted'
SELECT pg_temp.expect_ok('A2.6 a whole carton of the production order line, in its lot', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id, location_id,
                                                 received_qty, lot_number, uom_conversion_id)
    VALUES (md5('qa96:l1')::uuid, md5('qa96:gr')::uuid, md5('qa96:prod:line')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
            md5('demo:loc:HCM-RCV01')::uuid, 20, 'PRD-20261109-9601', md5('qa96:carton')::uuid) $q$);
SELECT pg_temp.expect_ok('A2.7 the draft posted (IN_PUTAWAY) and its registration confirmed', $q$
    UPDATE procurement.goods_receipts SET status = 'IN_PUTAWAY', confirmed_at = NOW(),
           confirmed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa96:gr')::uuid;
    UPDATE ordering.production_order_receipt SET status = 'CONFIRMED', settled_at = NOW()
     WHERE goods_receipt_id = md5('qa96:gr')::uuid $q$);

-- ============================================================================= A3
\echo '--- A3 a lot is of a production order or UNTRACED'
SELECT pg_temp.expect_fail_like('A3.1 a GOODS_RECEIPT lot', 'ck_stock_lot_origin', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type)
    VALUES (gen_random_uuid(), 'LY-GIAY-DEMO-12OZ-XANH', 'LOT-QA96', NOW(), 'GOODS_RECEIPT') $q$);
SELECT pg_temp.expect_fail_like('A3.2 a PRODUCTION_ORDER lot naming no production order', 'ck_stock_lot_production_order', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type)
    VALUES (gen_random_uuid(), 'LY-GIAY-DEMO-12OZ-XANH', 'PRD-20261109-9601', NOW(), 'PRODUCTION_ORDER') $q$);
SELECT pg_temp.expect_fail_like('A3.3 an UNTRACED lot naming a production order', 'ck_stock_lot_production_order', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, production_order_id, production_order_code)
    VALUES (gen_random_uuid(), 'LY-GIAY-DEMO-12OZ-XANH', 'PRD-20261109-9601', NOW(), 'UNTRACED', md5('qa96:prod')::uuid,
            'PRD-20261109-9601') $q$);
SELECT pg_temp.expect_ok('A3.4 an UNTRACED lot still inserts', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type)
    VALUES (md5('qa96:lot:untraced')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 'LOT-QA96', NOW(), 'UNTRACED') $q$);
SELECT pg_temp.expect_ok('A3.5 the lot of the production order''s SKU, numbered with its code', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, production_order_id, production_order_code)
    VALUES (md5('qa96:lot:prod')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 'PRD-20261109-9601', NOW(), 'PRODUCTION_ORDER',
            md5('qa96:prod')::uuid, 'PRD-20261109-9601') $q$);
SELECT pg_temp.expect_true('A3.6 the columns, the index and the CHECKs of the receipt origin are gone', $c$
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory' AND table_name = 'stock_lot'
                 AND column_name IN ('goods_receipt_id', 'goods_receipt_number'))
    AND NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'inventory' AND indexname = 'uk_stock_lot_receipt')
    AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname IN ('ck_stock_lot_receipt', 'ck_stock_lot_untraced',
                                                                  'fk_stock_lot_goods_receipt'))
    AND (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_stock_lot_origin') NOT LIKE '%GOODS_RECEIPT%' $c$);

-- ============================================================================= A4
\echo '--- A4 the default purchase unit is gone; the base unit is the item''s own unit'
SELECT pg_temp.expect_fail_like('A4.1 default_purchase is no longer a column', 'default_purchase', $q$
    SELECT default_purchase FROM inventory.uom_conversions $q$);
SELECT pg_temp.expect_true('A4.2 the column, its index and its CHECK are gone', $c$
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                 AND table_name = 'uom_conversions' AND column_name = 'default_purchase')
    AND NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'inventory'
                     AND indexname = 'uk_uom_conversions_default_purchase')
    AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_uom_conversions_default_purchase') $c$);
INSERT INTO product.products (id, code, name, slug, status, kind, tax_class, version, created_at)
VALUES (md5('qa96:a4:product')::uuid, 'QA96A4', 'QA a4', 'qa96-a4', 'DRAFT', 'CUSTOMIZABLE', 'STANDARD', 0, NOW());
-- An item is born with its variant (tg_variant_inventory_item), counted in EACH; it has no unit row until a carton is declared.
SELECT pg_temp.expect_ok('A4.3 a variant, and with it an inventory item, is inserted', $q$
    INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                                  created_at)
    VALUES (md5('qa96:a4:variant')::uuid, md5('qa96:a4:product')::uuid, 'QA96-A4-ITEM', 'QA a4', 'ACTIVE', TRUE,
            'DEFAULT', 0, 0, NOW()) $q$);
SELECT pg_temp.expect_true('A4.4 its base unit is its own unit_of_measure, with no unit row (V20261226000200)', $c$
    (SELECT i.unit_of_measure = 'EACH'
            AND NOT EXISTS (SELECT 1 FROM inventory.uom_conversions u WHERE u.inventory_item_id = i.id)
       FROM inventory.inventory_items i WHERE i.sku = 'QA96-A4-ITEM') $c$);
SELECT pg_temp.expect_true('A4.5 no function body names default_purchase any more', $c$
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND p.prosrc LIKE '%default_purchase%') $c$);

-- ============================================================================= B
\echo '--- B one active non-base unit per inventory item'
-- QA96-A4-ITEM (A4) has only its base unit.
SELECT pg_temp.expect_ok('B.1 the first active carton is declared', $q$
    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, length_cm, width_cm, height_cm, weight_kg)
    VALUES (md5('qa96:b:c1')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'QA96-A4-ITEM'), 'CARTON1', 12, 60, 40, 50, 12) $q$);
SELECT pg_temp.expect_fail_like('B.2 a second active unit of the item', 'uk_uom_conversions_one_carton', $q$
    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, length_cm, width_cm, height_cm, weight_kg)
    VALUES (md5('qa96:b:c2')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'QA96-A4-ITEM'), 'CARTON2', 24, 60, 40, 50, 12) $q$);
SELECT pg_temp.expect_ok('B.3 a retired unit beside the active one', $q$
    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, active, length_cm, width_cm, height_cm, weight_kg)
    VALUES (md5('qa96:b:c3')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'QA96-A4-ITEM'), 'CARTON3', 24, FALSE, 60, 40, 50, 12) $q$);
SELECT pg_temp.expect_fail_like('B.4 re-activating the retired one beside the active one', 'uk_uom_conversions_one_carton', $q$
    UPDATE inventory.uom_conversions SET active = TRUE WHERE id = md5('qa96:b:c3')::uuid $q$);
SELECT pg_temp.expect_ok('B.5 retiring the active one, then re-activating the other', $q$
    UPDATE inventory.uom_conversions SET active = FALSE WHERE id = md5('qa96:b:c1')::uuid;
    UPDATE inventory.uom_conversions SET active = TRUE WHERE id = md5('qa96:b:c3')::uuid $q$);
SELECT pg_temp.expect_true('B.6 the one active unit is the carton: the item''s own unit has no row (V20261226000200)', $c$
    (SELECT count(*) FROM inventory.uom_conversions
      WHERE inventory_item_id = (SELECT id FROM inventory.inventory_items WHERE sku = 'QA96-A4-ITEM') AND active) = 1 $c$);

ROLLBACK;
