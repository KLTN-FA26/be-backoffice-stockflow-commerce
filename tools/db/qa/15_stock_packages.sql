-- =============================================================================
-- Adversarial checks + happy paths: stock packages (cartons with an LPN), the units documents count
-- in, and the factor lock (GitHub #125, Jira SCRUM-473; V20261020000100-0700). Needs the demo seed.
-- Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;

CREATE FUNCTION pg_temp.expect_fail(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'ok    %  [%]', label, left(SQLERRM, 110);
        RETURN;
    END;
    RAISE EXCEPTION 'FAIL  %: the database accepted it', label;
END $$;

-- Refused by the named rule: a statement that breaks something else proves nothing.
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

-- Packing units: a carton of 10 blue cups, a carton of 5 red cups, and a case of 4 blue cups nobody uses. An item has ONE
-- active carton (01 BR-03): the demo seed's own are retired, the two cartons below are the active ones, and the case is
-- declared retired - a retired unit is declared beside the active one freely.
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND inventory_item_id IN (SELECT id FROM inventory.inventory_items
                                                         WHERE sku IN ('LY-GIAY-DEMO-12OZ-XANH', 'LY-GIAY-DEMO-12OZ-DO'));
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, length_cm, width_cm, height_cm, weight_kg, active)
VALUES (md5('qa:unit:XANH-CARTON')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'QA_CARTON', 10, 60, 40, 50, 12, TRUE),
       (md5('qa:unit:DO-CARTON')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-DO'), 'QA_CARTON', 5, 80, 60, 40, 30, TRUE),
       (md5('qa:unit:XANH-CASE')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'CASE', 4, NULL, NULL, NULL, NULL, FALSE);
-- The supplier's old carton of 10 blue cups, retired before anything was received in it (docs 01 edge case "NCC đổi quy cách thùng").
-- Measured, as every carton received against a production order must be (01 BR-03, V20261215000100).
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, length_cm, width_cm, height_cm, weight_kg, active)
VALUES (md5('qa:unit:XANH-OLD')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'OLDCTN', 10, 60, 40, 50, 12, FALSE);


\echo '--- a stock package is the stock (V20261020000100; the stock rows folded into it by V20261230000600, ADR-0011)'
-- The lot of this script's cartons: a carton names its lot by id, the application opens it (StockLots).
INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at)
VALUES (md5('qa:pk:lot')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', NULL, 'UNTRACED', NOW());
SELECT pg_temp.expect_ok('K1 received into stock: RECEIVING, counted, then IN_STOCK as INBOUND with its lot and receipt date', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at, location_code)
    VALUES (md5('qa:pk:1')::uuid, 'QA-LPN-1', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-CARTON')::uuid, 10, NOW(), 'HCM-A01-1-A');
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'INBOUND', lot_id = md5('qa:pk:lot')::uuid,
           received_at = NOW() - interval '1 hour' WHERE id = md5('qa:pk:1')::uuid;
    UPDATE inventory.stock_package SET stock_status = 'AVAILABLE' WHERE id = md5('qa:pk:1')::uuid $q$);
-- K2, K3 (the carton and its rows moved apart), K5, K6, K13 (part of a carton leaving), K7 (a row of another SKU in
-- it), K25, K26 (two lots / two layers in one carton) checked a carton against its stock rows
-- (check_stock_package_contents): there are no rows and no quantity to disagree with any more.
SELECT pg_temp.expect_ok('K4 the stock package moved: one row, nothing to move with it', $q$
    UPDATE inventory.stock_package SET location_code = 'HCM-A01-1-B' WHERE id = md5('qa:pk:1')::uuid $q$);
SELECT pg_temp.expect_fail('K8 counted again once in stock', $q$
    UPDATE inventory.stock_package SET counted_at = counted_at + interval '1 minute' WHERE id = md5('qa:pk:1')::uuid $q$);
SELECT pg_temp.expect_fail('K9 its LPN changed', $q$
    UPDATE inventory.stock_package SET lpn = 'QA-LPN-X' WHERE id = md5('qa:pk:1')::uuid $q$);
SELECT pg_temp.expect_fail('K10 a second stock package with the same LPN', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA-LPN-1', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-CARTON')::uuid, 10, 'HCM-A01-1-A') $q$);
SELECT pg_temp.expect_fail_by('K11 in stock with no stock status (it was: emptied but left IN_STOCK)', 'ck_stock_package_stock_status', $q$
    UPDATE inventory.stock_package SET stock_status = NULL WHERE id = md5('qa:pk:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('K12 CONSUMED while still carrying a stock status (it was: while still holding stock)', 'ck_stock_package_stock_status', $q$
    UPDATE inventory.stock_package SET status = 'CONSUMED', closed_at = NOW() WHERE id = md5('qa:pk:1')::uuid $q$);
SELECT pg_temp.expect_true('K14 no stock package is ever opened: the flag is gone (V20261113000300, team plan 4C-3)',
    'NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = ''inventory'' AND table_name = ''stock_package'' AND column_name = ''unopened'')');
SELECT pg_temp.expect_ok('K15 CONSUMED: out of stock, so no stock status; its lot and receipt date stay', $q$
    UPDATE inventory.stock_package SET status = 'CONSUMED', stock_status = NULL, closed_at = NOW()
     WHERE id = md5('qa:pk:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('K16 CONSUMED back to IN_STOCK without saying as what', 'ck_stock_package_stock_status', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL WHERE id = md5('qa:pk:1')::uuid $q$);

\echo '--- creation and lifecycle'
SELECT pg_temp.expect_ok('K17 made in the warehouse (repacked): created IN_STOCK with its stock status and lot', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at, location_code, status,
                                         stock_status, lot_id, received_at)
    VALUES (md5('qa:pk:2')::uuid, 'QA-LPN-2', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-CARTON')::uuid, 10, NOW(), 'HCM-A01-1-A', 'IN_STOCK',
            'AVAILABLE', md5('qa:pk:lot')::uuid, NOW() - interval '2 hour') $q$);
SELECT pg_temp.expect_ok('K18 not found by a count (LOST), then found again as AVAILABLE, the same lot and date', $q$
    UPDATE inventory.stock_package SET status = 'LOST', stock_status = NULL, closed_at = NOW()
     WHERE id = md5('qa:pk:2')::uuid;
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'AVAILABLE', closed_at = NULL
     WHERE id = md5('qa:pk:2')::uuid $q$);
SELECT pg_temp.expect_fail('K19 created already CONSUMED (skipping the lifecycle)', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at, location_code, status, closed_at, lot_id)
    VALUES (gen_random_uuid(), 'QA-LPN-19', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-CARTON')::uuid, 10, NOW(), 'HCM-A01-1-A', 'CONSUMED', NOW(), md5('qa:pk:lot')::uuid) $q$);
SELECT pg_temp.expect_fail('K20 a base unit row to make a carton in: there is none any more, nor the column that flagged one (V20261229000100, QA 46)', $q$
    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base)
    SELECT gen_random_uuid(), id, 'QA15BASE', 1, TRUE
      FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH' $q$);
SELECT pg_temp.expect_fail('K21 nominal_qty other than the unit''s factor', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA-LPN-21', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-CARTON')::uuid, 12, 'HCM-A01-1-A') $q$);
SELECT pg_temp.expect_fail('K22 a unit of another item', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA-LPN-22', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:DO-CARTON')::uuid, 5, 'HCM-A01-1-A') $q$);
SELECT pg_temp.expect_fail_by('K23 in stock without having been counted', 'ck_stock_package_counted', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code, status, stock_status, lot_id)
    VALUES (gen_random_uuid(), 'QA-LPN-23', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-CARTON')::uuid, 10, 'HCM-A01-1-A', 'IN_STOCK', 'AVAILABLE', md5('qa:pk:lot')::uuid) $q$);
SELECT pg_temp.expect_fail_by('K24 still RECEIVING but carrying a stock status (it was: but holding stock)', 'ck_stock_package_stock_status', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code, stock_status)
    VALUES (md5('qa:pk:24')::uuid, 'QA-LPN-24', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-CARTON')::uuid, 10, 'HCM-A01-1-A', 'AVAILABLE') $q$);

\echo '--- ledger: a line names one carton (V20261020000300; one column since V20261230000200, ADR-0011)'
-- The two carton columns, one per side, are one: a line is one whole carton, and its places say which way it went.
SELECT pg_temp.expect_true('K27 the ledger has one carton column, indexed, and the two sides are gone', $c$
    (SELECT count(*) FILTER (WHERE column_name = 'stock_package_id') = 1
            AND count(*) FILTER (WHERE column_name IN ('from_stock_package_id', 'to_stock_package_id')) = 0
       FROM information_schema.columns WHERE table_schema = 'inventory' AND table_name = 'stock_movement')
    AND EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'inventory' AND indexname = 'ix_stock_movement_package')
    AND NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'inventory'
                     AND indexname IN ('ix_stock_movement_from_package', 'ix_stock_movement_to_package')) $c$);
-- V20261230000400 (ADR-0011, step 3b): a line is one carton and no number of units. The column that repeated the
-- carton's nominal_qty is gone, every line names its carton, and the CHECK that said so of new lines only went into
-- the NOT NULL.
SELECT pg_temp.expect_true('K27a every line names its carton: stock_package_id is NOT NULL, the NOT VALID CHECK is gone', $c$
    (SELECT attnotnull FROM pg_attribute
      WHERE attrelid = 'inventory.stock_movement'::regclass AND attname = 'stock_package_id')
    AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_stock_movement_names_carton') $c$);
SELECT pg_temp.expect_true('K27e the ledger keeps no quantity, and no CHECK or index of one', $c$
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                 AND table_name = 'stock_movement' AND column_name = 'quantity')
    AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_stock_movement_qty')
    AND NOT EXISTS (SELECT 1 FROM pg_index WHERE indrelid = 'inventory.stock_movement'::regclass
                     AND pg_get_indexdef(indexrelid) ~ 'quantity') $c$);
SELECT pg_temp.expect_fail('K27f a line cannot be written with a quantity any more', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity, reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-1-A', 'HCM-A01-1-B', 5, 'RETURN_REQUEST_LINE', gen_random_uuid(), NOW(), md5('qa:pk:2')::uuid) $q$);
-- Postgres does not track what a function body mentions (a view it does: the drop would have been refused), so
-- nothing live may still read the dropped column: by the table's name, by an alias the body gave the ledger, or bare
-- in a statement over the ledger alone.
CREATE FUNCTION pg_temp.reads_ledger_quantity(body TEXT) RETURNS boolean LANGUAGE sql IMMUTABLE AS $f$
    SELECT body ~* 'stock_movement\.quantity\M'
        OR body ~* 'stock_movement\s+(WHERE|SET)\M[^;]*\mquantity\M'
        OR EXISTS (SELECT 1 FROM regexp_matches(body, 'stock_movement\s+(?:AS\s+)?(\w+)', 'gi') AS a
                    WHERE lower(a[1]) NOT IN ('where', 'set', 'join', 'on', 'group', 'order', 'for')
                      AND body ~* ('\m' || a[1] || '\.quantity\M'))
$f$;
SELECT pg_temp.expect_true('K27g the reader check bites: a body that reads the ledger''s quantity is seen, one that reads another table''s is not', $c$
    pg_temp.reads_ledger_quantity('SELECT sum(m.quantity) FROM inventory.stock_movement m WHERE m.sku = x;')
    AND pg_temp.reads_ledger_quantity('SELECT 1 FROM inventory.stock_movement AS mv JOIN t ON mv.quantity > 0;')
    AND pg_temp.reads_ledger_quantity('SELECT sum(quantity) FROM x; UPDATE inventory.stock_movement SET quantity = 1;')
    AND NOT pg_temp.reads_ledger_quantity('SELECT count(*) FROM inventory.stock_movement m WHERE m.sku = x; '
                                          || 'SELECT 1 FROM procurement.goods_receipt_return_cartons c WHERE c.quantity > 0;') $c$);
SELECT pg_temp.expect_true('K27h no live function reads stock_movement.quantity', $c$
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                   AND pg_temp.reads_ledger_quantity(p.prosrc)) $c$);
SELECT pg_temp.expect_fail_by('K27c a new ledger line that names no carton', 'null value in column "stock_package_id"', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'RECEIPT', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-1-A', 'GOODS_RECEIPT_LINE', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_fail_by('K27d ... whatever kind of line it is', 'null value in column "stock_package_id"', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'ADJUSTMENT', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-1-A', 'STOCK_ADJUSTMENT', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_ok('K28 a whole stock package moved, its line naming it', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-1-A', 'HCM-A01-1-B', 'MOVE_TASK', gen_random_uuid(), NOW(), md5('qa:pk:2')::uuid) $q$);
SELECT pg_temp.expect_fail_by('K28a a movement naming a stock package of another SKU', 'names a stock package of', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-DO', 'HCM-A01-1-A', 'HCM-A01-1-B', 'MOVE_TASK', gen_random_uuid(), NOW(), md5('qa:pk:2')::uuid) $q$);
SELECT pg_temp.expect_fail_by('K28b a movement naming a stock package at a bin it never touched', 'names a stock package at', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-1-B', 'HCM-RCV01', 'MOVE_TASK', gen_random_uuid(), NOW(), md5('qa:pk:2')::uuid) $q$);
SELECT pg_temp.expect_fail_by('K28b2 ... a line that leaves, from a bin the carton does not stand at', 'names a stock package at', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'PICK', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-1-B', 'PICK_LINE', gen_random_uuid(), NOW(), md5('qa:pk:2')::uuid) $q$);
SELECT pg_temp.expect_fail_by('K28b3 a held carton is still not moved by hand: the trigger reads the one carton', 'it is not moved by hand', $q$
    INSERT INTO inventory.stock_reservation (id, stock_package_id, order_id, root_request_id, request_id, cartons, units_per_carton, reserved_at, status)
    SELECT gen_random_uuid(), md5('qa:pk:2')::uuid, o.id, gen_random_uuid(), gen_random_uuid(), 1, 10, NOW(), 'HELD'
      FROM ordering.customer_order o ORDER BY o.id LIMIT 1;
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-1-A', 'HCM-A01-1-B', 'MOVE_TASK', gen_random_uuid(), NOW(), md5('qa:pk:2')::uuid) $q$);

-- K28c to K28g (a second row in a carton, a carton and its one row moving together) went with the stock rows: a
-- carton is one row of inventory.stock_package, and uk_stock_item_package has nothing left to forbid.

\echo '--- receiving by the carton, putaway by the stock package (V20261020000400, 0500; purchase orders went with V20261113000200)'
-- A production order of 5 cartons of 10 blue cups at HCM, SENT with no sample to approve first, so goods may be received
-- against it (procurement.check_goods_receipt). Its own rows are written with the row triggers off - the production
-- order's rules are 21_production_orders.sql's - and its receipt is registered with it as 22_production_receipts.sql does.
SET LOCAL session_replication_role = replica;
INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id,
                                       receiving_warehouse_id, need_by_date, status, sample_approval_required,
                                       sample_required_by_default, drawn_up_by, sent_by, sent_at)
VALUES (md5('qa:pk:prod')::uuid, 'PRD-20261109-9015', md5('qa:pk:so')::uuid, md5('qa:pk:batch')::uuid,
        md5('qa:pk:cust')::uuid, md5('qa:pk:producer')::uuid, md5('demo:wh:HCM')::uuid, CURRENT_DATE + 30, 'SENT', FALSE,
        FALSE, md5('demo:user:editor')::uuid, md5('demo:user:editor')::uuid, NOW());
INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                            design_snapshot_id, print_file_checksum)
VALUES (md5('qa:pk:prod:line')::uuid, md5('qa:pk:prod')::uuid, md5('qa:pk:ol')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 5, 10,
        md5('qa:pk:snap')::uuid, repeat('d', 64));
SET LOCAL session_replication_role = origin;
INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, production_order_id, warehouse_id, status,
                                        received_at, received_by)
VALUES (md5('qa:pk:gr')::uuid, 'GR-QA-PK', 'PRODUCTION', md5('qa:pk:prod')::uuid, md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(),
        md5('demo:user:editor')::uuid);
INSERT INTO ordering.production_order_receipt (production_order_id, goods_receipt_id, receipt_number, status, opened_at)
VALUES (md5('qa:pk:prod')::uuid, md5('qa:pk:gr')::uuid, 'GR-QA-PK', 'DRAFT', NOW());
-- The old carton of 10 blue cups was declared retired: nothing was received in it.

SELECT pg_temp.expect_fail('U9 received in a unit of another item', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id, location_id, received_qty, lot_number, uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa:pk:gr')::uuid, md5('qa:pk:prod:line')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
            md5('demo:loc:HCM-RCV01')::uuid, 10, 'PRD-20261109-9015', md5('qa:unit:DO-CARTON')::uuid) $q$);
SELECT pg_temp.expect_fail('U8b a new receipt line in a retired unit', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id, location_id, received_qty, lot_number, uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa:pk:gr')::uuid, md5('qa:pk:prod:line')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
            md5('demo:loc:HCM-RCV01')::uuid, 10, 'PRD-20261109-9015', md5('qa:unit:XANH-OLD')::uuid) $q$);
SELECT pg_temp.expect_ok('U10 received in CARTON', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id, location_id, received_qty, lot_number, uom_conversion_id)
    VALUES (md5('qa:pk:grl')::uuid, md5('qa:pk:gr')::uuid, md5('qa:pk:prod:line')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
            md5('demo:loc:HCM-RCV01')::uuid, 10, 'PRD-20261109-9015', md5('qa:unit:XANH-CARTON')::uuid) $q$);
SELECT pg_temp.expect_fail('K29 a stock package naming a receipt line of another item', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code, goods_receipt_line_id)
    VALUES (gen_random_uuid(), 'QA-LPN-29', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-DO'), md5('qa:unit:DO-CARTON')::uuid, 5, 'HCM-RCV01', md5('qa:pk:grl')::uuid) $q$);
SELECT pg_temp.expect_ok('K30 a stock package of the receipt line, waiting in the receiving area', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at, location_code, goods_receipt_line_id)
    VALUES (md5('qa:pk:30')::uuid, 'QA-LPN-30', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-CARTON')::uuid, 10, NOW(), 'HCM-RCV01', md5('qa:pk:grl')::uuid) $q$);
SELECT pg_temp.expect_fail('K31 a putaway task naming a stock package of another SKU', $q$
    INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, quantity, status, created_at, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:pk:gr')::uuid, md5('qa:pk:grl')::uuid, 'LY-GIAY-DEMO-12OZ-DO', 10, 'PENDING', NOW(), md5('qa:pk:30')::uuid) $q$);
SELECT pg_temp.expect_ok('K32 one open putaway task for the stock package', $q$
    INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, quantity, status, created_at, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:pk:gr')::uuid, md5('qa:pk:grl')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 10, 'PENDING', NOW(), md5('qa:pk:30')::uuid) $q$);
SELECT pg_temp.expect_fail('K33 a second open putaway task for it', $q$
    INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, quantity, status, created_at, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:pk:gr')::uuid, md5('qa:pk:grl')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 10, 'PENDING', NOW(), md5('qa:pk:30')::uuid) $q$);
SELECT pg_temp.expect_fail('K33a a putaway task naming a VOID stock package', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code, goods_receipt_line_id)
    VALUES (md5('qa:pk:33a')::uuid, 'QA-LPN-33A', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-CARTON')::uuid, 10, 'HCM-RCV01', md5('qa:pk:grl')::uuid);
    UPDATE inventory.stock_package SET status = 'VOID', closed_at = NOW() WHERE id = md5('qa:pk:33a')::uuid;
    INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, quantity, status, created_at, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:pk:gr')::uuid, md5('qa:pk:grl')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 10, 'PENDING', NOW(), md5('qa:pk:33a')::uuid) $q$);

\echo '--- a retired unit stays for the documents already in it (V20261020000100, 0400)'
SELECT pg_temp.expect_fail('U11 a receipt line newly put in a retired unit (no purchase order line to excuse it any more)', $q$
    UPDATE procurement.goods_receipt_lines SET uom_conversion_id = md5('qa:unit:XANH-OLD')::uuid WHERE id = md5('qa:pk:grl')::uuid $q$);
SELECT pg_temp.expect_fail('K34 a stock package in a retired unit, made in the warehouse', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA-LPN-34', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-OLD')::uuid, 10, 'HCM-RCV01') $q$);
SELECT pg_temp.expect_fail('K35 a stock package in a retired unit its receipt line was not received in', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code, goods_receipt_line_id)
    VALUES (gen_random_uuid(), 'QA-LPN-35', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-OLD')::uuid, 10, 'HCM-RCV01', md5('qa:pk:grl')::uuid) $q$);
SELECT pg_temp.expect_ok('K36 cartons received in the old unit before it was retired are still packed in it', $q$
    -- The real sequence (BR-03, BR-10): the active carton is retired before the old one is active again.
    UPDATE inventory.uom_conversions SET active = FALSE WHERE id = md5('qa:unit:XANH-CARTON')::uuid;
    UPDATE inventory.uom_conversions SET active = TRUE WHERE id = md5('qa:unit:XANH-OLD')::uuid;
    UPDATE procurement.goods_receipt_lines SET uom_conversion_id = md5('qa:unit:XANH-OLD')::uuid WHERE id = md5('qa:pk:grl')::uuid;
    UPDATE inventory.uom_conversions SET active = FALSE WHERE id = md5('qa:unit:XANH-OLD')::uuid;
    UPDATE inventory.uom_conversions SET active = TRUE WHERE id = md5('qa:unit:XANH-CARTON')::uuid;
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code, goods_receipt_line_id)
    VALUES (gen_random_uuid(), 'QA-LPN-36', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-OLD')::uuid, 10, 'HCM-RCV01', md5('qa:pk:grl')::uuid) $q$);

\echo '--- a unit a document counts in keeps its factor (V20261020000600)'
SELECT pg_temp.expect_true('F1 the carton unit is in use', $c$ inventory.unit_in_use(md5('qa:unit:XANH-CARTON')::uuid) $c$);
SELECT pg_temp.expect_true('F2 the case unit is not', $c$ NOT inventory.unit_in_use(md5('qa:unit:XANH-CASE')::uuid) $c$);
SELECT pg_temp.expect_fail('F3 the factor of a unit in use changed (BR-10)', $q$
    UPDATE inventory.uom_conversions SET factor = 12 WHERE id = md5('qa:unit:XANH-CARTON')::uuid $q$);
SELECT pg_temp.expect_ok('F4 the factor of an unused unit changed', $q$
    UPDATE inventory.uom_conversions SET factor = 6 WHERE id = md5('qa:unit:XANH-CASE')::uuid $q$);
SELECT pg_temp.expect_fail('F5 a unit in use deleted', $q$
    DELETE FROM inventory.uom_conversions WHERE id = md5('qa:unit:XANH-CARTON')::uuid $q$);
SELECT pg_temp.expect_fail('F5a a unit in use moved to another item', $q$
    UPDATE inventory.uom_conversions SET inventory_item_id = (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-DO')
     WHERE id = md5('qa:unit:XANH-CARTON')::uuid $q$);
SELECT pg_temp.expect_fail('F5b a unit in use renamed', $q$
    UPDATE inventory.uom_conversions SET unit_of_measure = 'BOX' WHERE id = md5('qa:unit:XANH-CARTON')::uuid $q$);
SELECT pg_temp.expect_ok('F5c an unused unit renamed', $q$
    UPDATE inventory.uom_conversions SET unit_of_measure = 'PACK' WHERE id = md5('qa:unit:XANH-CASE')::uuid $q$);
SELECT pg_temp.expect_true('F6 an item with stock packages is in use', $c$
    inventory.item_in_use((SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'LY-GIAY-DEMO-12OZ-XANH') $c$);

\echo '--- documents name their carton (V20261022000100)'
SELECT pg_temp.expect_ok('D1 a carton of 10 in stock on HCM-A01-1-A', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at, location_code, status,
                                         stock_status, lot_id, received_at)
    VALUES (md5('qa:pk:doc')::uuid, 'QA-LPN-D1', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-CARTON')::uuid, 10, NOW(), 'HCM-A01-1-A', 'IN_STOCK',
            'AVAILABLE', md5('qa:pk:lot')::uuid, NOW() - interval '3 day') $q$);
SELECT pg_temp.expect_fail('D2 an adjustment naming a carton of another SKU', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code, requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'QA-ADJ-D2', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-DO', 'WRITE_OFF', 'DAMAGED',
            (SELECT id FROM identity.app_user ORDER BY id LIMIT 1), md5('qa:pk:doc')::uuid) $q$);
SELECT pg_temp.expect_fail('D3 an adjustment naming a carton at another bin', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code, requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'QA-ADJ-D3', 'HCM-A01-1-B', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'DAMAGED',
            (SELECT id FROM identity.app_user ORDER BY id LIMIT 1), md5('qa:pk:doc')::uuid) $q$);
SELECT pg_temp.expect_ok('D4 an adjustment of that carton: the whole carton', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code, requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'QA-ADJ-D4', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'DAMAGED',
            (SELECT id FROM identity.app_user ORDER BY id LIMIT 1), md5('qa:pk:doc')::uuid) $q$);
SELECT pg_temp.expect_ok('D5 a count with a line for the carton', $q$
    INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type)
    SELECT md5('qa:cc:doc')::uuid, 'QA-CC-D5', id, 'MANUAL' FROM warehouse.warehouse WHERE prefix = 'HCM';
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:cc:doc')::uuid, 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', TRUE, md5('qa:pk:doc')::uuid) $q$);
SELECT pg_temp.expect_fail('D6 the same carton counted twice in one count', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:cc:doc')::uuid, 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', TRUE, md5('qa:pk:doc')::uuid) $q$);
-- D7 (a carton filled past what was counted into it) went with the stock rows: a carton holds no quantity.
SELECT pg_temp.expect_true('D8 the balance view is gone with the table it joined (V20261230000600)', $c$
    NOT EXISTS (SELECT 1 FROM pg_views WHERE schemaname = 'inventory' AND viewname = 'stock_package_balance')
    AND to_regclass('inventory.stock_item') IS NULL $c$);

\echo '--- a carton records when it was counted in, not a number of units (V20261230000300, ADR-0011 step 3a)'
SELECT pg_temp.expect_true('CA1 received_qty is gone from the carton and counted_at is a nullable timestamp', $c$
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                   AND table_name = 'stock_package' AND column_name = 'received_qty')
    AND (SELECT data_type = 'timestamp with time zone' AND is_nullable = 'YES' FROM information_schema.columns
          WHERE table_schema = 'inventory' AND table_name = 'stock_package' AND column_name = 'counted_at') $c$);
SELECT pg_temp.expect_true('CA2 the CHECKs that only constrained the figure went with it; "counted" reads counted_at', $c$
    NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'inventory.stock_package'::regclass
                   AND conname IN ('ck_stock_package_received', 'ck_stock_package_standard_count'))
    AND (SELECT pg_get_constraintdef(oid) LIKE '%counted_at IS NOT NULL%' FROM pg_constraint
          WHERE conrelid = 'inventory.stock_package'::regclass AND conname = 'ck_stock_package_counted') $c$);
SELECT pg_temp.expect_true('CA3 no function of inventory or ordering, nor the returned-carton check, still reads a carton''s received_qty', $c$
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE p.prosrc ILIKE '%received_qty%'
                   AND (n.nspname IN ('inventory', 'ordering', 'fulfillment', 'warehouse')
                        OR p.proname = 'check_return_receipt_carton'))
    AND NOT EXISTS (SELECT 1 FROM pg_views WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
                       AND definition ILIKE '%received_qty%') $c$);
SELECT pg_temp.expect_true('CA4 the lifecycle trigger fires when counted_at changes (the contents check went with the rows)', $c$
    (SELECT count(*) FROM pg_trigger WHERE tgrelid = 'inventory.stock_package'::regclass AND NOT tgisinternal
        AND tgname = 'tg_stock_package_transition' AND pg_get_triggerdef(oid) LIKE '%counted_at%') = 1
    AND NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'tg_stock_package_rows') $c$);
SELECT pg_temp.expect_ok('CA5 labelled and not counted yet: RECEIVING with no count', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (md5('qa:pk:ca')::uuid, 'QA-LPN-CA', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-CARTON')::uuid, 10, 'HCM-RCV01') $q$);
SELECT pg_temp.expect_fail_by('CA6 it goes into stock uncounted', 'ck_stock_package_counted', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK' WHERE id = md5('qa:pk:ca')::uuid $q$);
SELECT pg_temp.expect_fail_by('CA7 a carton made IN_STOCK that was never counted in', 'ck_stock_package_counted', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code, status)
    VALUES (gen_random_uuid(), 'QA-LPN-CA7', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-CARTON')::uuid, 10, 'HCM-A01-1-A', 'IN_STOCK') $q$);
SELECT pg_temp.expect_ok('CA8 counted, counted again and uncounted while it is still RECEIVING (a draft is recounted)', $q$
    UPDATE inventory.stock_package SET counted_at = NOW() - interval '1 hour' WHERE id = md5('qa:pk:ca')::uuid;
    UPDATE inventory.stock_package SET counted_at = NOW() WHERE id = md5('qa:pk:ca')::uuid;
    UPDATE inventory.stock_package SET counted_at = NULL WHERE id = md5('qa:pk:ca')::uuid;
    UPDATE inventory.stock_package SET counted_at = NOW() WHERE id = md5('qa:pk:ca')::uuid $q$);
-- CA9 (a counted carton in stock holding 9 of the 10 it stands for) cannot be written: there is no quantity.
SELECT pg_temp.expect_ok('CA10 counted, it goes into stock: nominal_qty is what it stands for', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'INBOUND', lot_id = md5('qa:pk:lot')::uuid,
           received_at = NOW() WHERE id = md5('qa:pk:ca')::uuid $q$);
SELECT pg_temp.expect_fail_by('CA11 its count moved once it is in stock', 'counted in only while it is being received', $q$
    UPDATE inventory.stock_package SET counted_at = counted_at + interval '1 minute' WHERE id = md5('qa:pk:ca')::uuid $q$);
SELECT pg_temp.expect_fail('CA12 its count taken away once it is in stock', $q$
    UPDATE inventory.stock_package SET counted_at = NULL WHERE id = md5('qa:pk:ca')::uuid $q$);
SELECT pg_temp.expect_fail_by('CA13 its count taken away with its status untouched and the row triggers off: the CHECK alone', 'ck_stock_package_counted', $q$
    SET LOCAL session_replication_role = replica;
    UPDATE inventory.stock_package SET counted_at = NULL WHERE id = md5('qa:pk:ca')::uuid $q$);
SET LOCAL session_replication_role = origin;
SELECT pg_temp.expect_ok('CA14 a carton voided before it was counted stays uncounted', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (md5('qa:pk:cv')::uuid, 'QA-LPN-CV', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa:unit:XANH-CARTON')::uuid, 10, 'HCM-RCV01');
    UPDATE inventory.stock_package SET status = 'VOID', closed_at = NOW() WHERE id = md5('qa:pk:cv')::uuid $q$);
SELECT pg_temp.expect_fail('CA15 a voided carton counted afterwards', $q$
    UPDATE inventory.stock_package SET counted_at = NOW() WHERE id = md5('qa:pk:cv')::uuid $q$);
SELECT pg_temp.expect_true('CA16 the carton answers the count as a moment beside the standard count', $c$
    (SELECT nominal_qty = 10 AND counted_at IS NOT NULL AND status = 'IN_STOCK'
       FROM inventory.stock_package WHERE id = md5('qa:pk:ca')::uuid)
    AND (SELECT counted_at IS NULL AND status = 'VOID' AND stock_status IS NULL AND lot_id IS NULL
           FROM inventory.stock_package WHERE id = md5('qa:pk:cv')::uuid) $c$);

\echo '--- permissions (V20261020000700)'
SELECT pg_temp.expect_true('P1 SYSTEM_ADMIN holds every inventory-stock-packages permission', $c$
    (SELECT count(*) FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
       JOIN identity.permission p ON p.id = rp.permission_id
      WHERE r.code = 'SYSTEM_ADMIN' AND p.resource = 'inventory-stock-packages') = 4 $c$);
SELECT pg_temp.expect_true('P2 warehouse staff cannot UPDATE (mark one LOST)', $c$
    NOT EXISTS (SELECT 1 FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
       JOIN identity.permission p ON p.id = rp.permission_id
      WHERE r.code = 'WAREHOUSE_STAFF' AND p.code = 'inventory-stock-packages:UPDATE') $c$);

ROLLBACK;
