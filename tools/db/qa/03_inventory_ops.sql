-- =============================================================================
-- Adversarial checks + happy paths: stock ledger, adjustments, cycle counts, move tasks (docs 07, 11). Transfers
-- between warehouses were removed (V20261108000300). Needs the demo seed. Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
-- The moves and holds below use two cartons of the demo cup LY-GIAY-DEMO-12OZ-XANH made here, of 25 and of 8 cups, in a
-- lot of their own. The carton is the stock (ADR-0011, V20261231000600): each is one row of inventory.stock_package
-- with its stock status, lot and receipt date. The cups' demo cartons stay beside them; no check here reads a SKU's
-- total.
INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at)
VALUES (md5('qa03x:demo:lot')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 'QA-LOT', NULL, 'UNTRACED', NOW()),
       (md5('qa03:lot')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', NOW() - interval '1 day', 'UNTRACED', NOW());
-- An item has ONE active carton (01 BR-03): the demo seed's is retired, then each carton is made in its unit while
-- that unit is the active one, which is retired before the next is declared.
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND inventory_item_id = (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH');
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa03x:demo:u25')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'QA_DEMO25', 25);
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at, location_code, status, stock_status, lot_id)
VALUES (md5('qa03x:demo:p25')::uuid, 'QA-DEMO-25', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa03x:demo:u25')::uuid, 25, NOW(), 'HCM-A01-2-B', 'IN_STOCK', 'AVAILABLE', md5('qa03x:demo:lot')::uuid);
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND inventory_item_id = (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH');
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa03x:demo:u8')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'QA_DEMO8', 8);
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at, location_code, status, stock_status, lot_id)
VALUES (md5('qa03x:demo:p8')::uuid, 'QA-DEMO-8', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa03x:demo:u8')::uuid, 8, NOW(), 'HCM-A02-1-A', 'IN_STOCK', 'AVAILABLE', md5('qa03x:demo:lot')::uuid);

-- Inserts a warehouse whether or not the pre-C2 column "code" still exists (C2 drops it).
CREATE FUNCTION pg_temp.qa_warehouse(wid UUID, wprefix TEXT, wunit TEXT, w NUMERIC, h NUMERIC) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'warehouse' AND table_name = 'warehouse' AND column_name = 'code') THEN
        EXECUTE 'INSERT INTO warehouse.warehouse (id, code, name, status, prefix, address, map_unit, map_width, map_height, created_at)
                 VALUES ($1, $2, $2, ''ACTIVE'', $2, ''QA'', $3, $4, $5, NOW())' USING wid, wprefix, wunit, w, h;
    ELSE
        EXECUTE 'INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height, created_at)
                 VALUES ($1, $2, ''ACTIVE'', $2, ''QA'', $3, $4, $5, NOW())' USING wid, wprefix, wunit, w, h;
    END IF;
END $$;

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

SELECT pg_temp.qa_warehouse(md5('qa:wh:HN')::uuid, 'HN', 'M', 30, 20);
INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, is_pickable, is_putaway_target, status)
VALUES (md5('qa:loc:HN-RCV')::uuid, md5('qa:wh:HN')::uuid, 'AREA', 'HN-RCV01', FALSE, FALSE, 'ACTIVE');
INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation, is_obstacle, status)
VALUES (gen_random_uuid(), md5('qa:wh:HN')::uuid, md5('qa:loc:HN-RCV')::uuid, 'RCV01', 'RECEIVING', 'x', 1, 1, 5, 5, 0, FALSE, 'ACTIVE');

\echo '--- stock movement ledger (docs 11 BR-06)'
-- A ledger line names its carton (stock_package_id NOT NULL, V20261231000400): one of the demo's blue-cup cartons
-- standing at the place, so that each line below is judged by the rule its label names.
CREATE FUNCTION pg_temp.blue_carton(place TEXT) RETURNS uuid LANGUAGE sql AS $$
    SELECT p.id FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.location_code = place AND p.status = 'IN_STOCK'
     ORDER BY p.lpn LIMIT 1
$$;
-- Not a move by hand (a MOVE_TASK refuses a carton held for an order, QA 41): the line names a return line.
SELECT pg_temp.expect_ok('I1 a MOVE between two bins', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code,
        reference_type, reference_id, actor_id, occurred_at, stock_package_id)
    VALUES (md5('qa:mv:1')::uuid, 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-1-A', 'RETURN_REQUEST_LINE', gen_random_uuid(),
        md5('demo:user:editor')::uuid, NOW(), pg_temp.blue_carton('HCM-A01-2-B')) $q$);
SELECT pg_temp.expect_fail_by('I1a a line that names no carton (ADR-0011: the ledger is cartons)', 'null value in column "stock_package_id"', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code,
        reference_type, reference_id, actor_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-1-A', 'RETURN_REQUEST_LINE', gen_random_uuid(),
        md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail_by('I2 a MOVE to the same location', 'ck_stock_movement_shape', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-2-B', 'MOVE_TASK', gen_random_uuid(), NOW(), pg_temp.blue_carton('HCM-A01-2-B')) $q$);
SELECT pg_temp.expect_fail_by('I3 a RECEIPT that comes from a location', 'ck_stock_movement_shape', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'RECEIPT', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-RCV01', 'GOODS_RECEIPT_LINE', gen_random_uuid(), NOW(), pg_temp.blue_carton('HCM-A01-2-B')) $q$);
SELECT pg_temp.expect_fail_by('I4 an ADJUSTMENT with both sides', 'ck_stock_movement_shape', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJUSTMENT', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-1-A', 'STOCK_ADJUSTMENT', gen_random_uuid(), NOW(), pg_temp.blue_carton('HCM-A01-2-B')) $q$);
-- On the bin the carton stands at: the statuses are what the line does not change.
SELECT pg_temp.expect_fail_by('I5 a STATUS_CHANGE that changes nothing', 'ck_stock_movement_shape', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, from_status, to_status, reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'STATUS_CHANGE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-2-B', 'AVAILABLE', 'AVAILABLE', 'QC_INSPECTION', gen_random_uuid(), NOW(), pg_temp.blue_carton('HCM-A01-2-B')) $q$);
SELECT pg_temp.expect_fail_by('I6 movement to a location that does not exist', 'fk_stock_movement_to', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-NOWHERE', 'RETURN_REQUEST_LINE', gen_random_uuid(), NOW(), pg_temp.blue_carton('HCM-A01-2-B')) $q$);
-- No carton is of an unknown SKU, and a carton of another SKU is refused before the key is asked (QA 15 K28a): the
-- line names a carton that does not exist either, and the SKU's key is the one that answers first.
SELECT pg_temp.expect_fail_by('I7 movement of an unknown sku', 'fk_stock_movement_item', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'NOPE', 'HCM-A01-2-B', 'HCM-A01-1-A', 'RETURN_REQUEST_LINE', gen_random_uuid(), NOW(), gen_random_uuid()) $q$);
SELECT pg_temp.expect_fail_by('I7a movement naming a carton that does not exist', 'fk_stock_movement_package', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-1-A', 'RETURN_REQUEST_LINE', gen_random_uuid(), NOW(), gen_random_uuid()) $q$);
SELECT pg_temp.expect_fail('I8 ledger row edited', $q$ UPDATE inventory.stock_movement SET reason = 'edited' WHERE id = md5('qa:mv:1')::uuid $q$);
SELECT pg_temp.expect_fail('I9 ledger row deleted', $q$ DELETE FROM inventory.stock_movement WHERE id = md5('qa:mv:1')::uuid $q$);

\echo '--- cycle count and stock adjustment (by carton since V20261113000300, team plan 4C-3)'
-- Two whole cartons of 4 cups on HCM-A01-2-B and HCM-A02-1-A: what a count line and an adjustment name.
-- One active carton per item (01 BR-03): the one before is retired.
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND inventory_item_id = (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH');
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa03:unit')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'QA03_CTN', 4);
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at,
                                     location_code, status, stock_status, lot_id, received_at)
VALUES (md5('qa03:pk1')::uuid, 'QA03-LPN-1', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa03:unit')::uuid, 4, NOW(),
        'HCM-A01-2-B', 'IN_STOCK', 'AVAILABLE', md5('qa03:lot')::uuid, NOW() - interval '1 day'),
       (md5('qa03:pk2')::uuid, 'QA03-LPN-2', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa03:unit')::uuid, 4, NOW(),
        'HCM-A02-1-A', 'IN_STOCK', 'AVAILABLE', md5('qa03:lot')::uuid, NOW() - interval '1 day');
INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status, started_at, assigned_user_id)
VALUES (md5('qa:cc:1')::uuid, 'CC-QA-1', md5('demo:wh:HCM')::uuid, 'SHORT_PICK', 'IN_PROGRESS', NOW(), md5('demo:user:editor')::uuid);
SELECT pg_temp.expect_fail('I10 counting a location of another warehouse', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:cc:1')::uuid, 'HN-RCV01', 'LY-GIAY-DEMO-12OZ-XANH', FALSE, md5('qa03:pk1')::uuid) $q$);
SELECT pg_temp.expect_fail_by('I10b a loose count line: a line names its carton (NOT NULL since V20261231000100)', '"stock_package_id"', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked)
    VALUES (gen_random_uuid(), md5('qa:cc:1')::uuid, 'HCM-A01-2-B', 'LY-GIAY-DEMO-12OZ-XANH', FALSE) $q$);
SELECT pg_temp.expect_ok('I11 a count line: the carton booked, not found', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, found, counted_by, counted_at, stock_package_id)
    VALUES (md5('qa:ccl:1')::uuid, md5('qa:cc:1')::uuid, 'HCM-A01-2-B', 'LY-GIAY-DEMO-12OZ-XANH', TRUE, FALSE, md5('demo:user:editor')::uuid, NOW(), md5('qa03:pk1')::uuid) $q$);
SELECT pg_temp.expect_fail_by('I11b a count line counted 3 of 4 booked: a carton is found or not, never a number of units', 'boolean', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, found, counted_by, counted_at, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:cc:1')::uuid, 'HCM-A02-1-A', 'LY-GIAY-DEMO-12OZ-XANH', TRUE, 3, md5('demo:user:editor')::uuid, NOW(), md5('qa03:pk2')::uuid) $q$);
SELECT pg_temp.expect_fail_by('I12 a line found without who/when', 'ck_cycle_count_line_counted', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, found, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:cc:1')::uuid, 'HCM-A02-1-A', 'LY-GIAY-DEMO-12OZ-XANH', TRUE, TRUE, md5('qa03:pk2')::uuid) $q$);
SELECT pg_temp.expect_fail_by('I13 the same carton twice in one count', 'uk_cycle_count_line_package', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa:cc:1')::uuid, 'HCM-A01-2-B', 'LY-GIAY-DEMO-12OZ-XANH', TRUE, md5('qa03:pk1')::uuid) $q$);
SELECT pg_temp.expect_fail('I14 COMPLETED count without completed_at', $q$
    UPDATE inventory.cycle_count SET status = 'COMPLETED' WHERE id = md5('qa:cc:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('I15 count variance adjustment without its count line', 'ck_stock_adjustment_count_variance', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code, requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA-X', 'HCM-A01-2-B', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'COUNT_VARIANCE', md5('demo:user:editor')::uuid, md5('qa03:pk1')::uuid) $q$);
SELECT pg_temp.expect_fail_by('I15b count variance adjustment of no carton', '"stock_package_id"', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code, cycle_count_line_id, requested_by)
    VALUES (gen_random_uuid(), 'ADJ-QA-Y', 'HCM-A01-2-B', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'COUNT_VARIANCE', md5('qa:ccl:1')::uuid, md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail_by('I15c count variance of part of a carton: a number of units is no direction', 'ck_stock_adjustment_direction', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code, cycle_count_line_id, requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA-Z', 'HCM-A01-2-B', 'LY-GIAY-DEMO-12OZ-XANH', '-2', 'COUNT_VARIANCE', md5('qa:ccl:1')::uuid, md5('demo:user:editor')::uuid, md5('qa03:pk1')::uuid) $q$);
SELECT pg_temp.expect_ok('I16 variance adjustment requested: the whole carton written off', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code, cycle_count_line_id, requested_by, stock_package_id)
    VALUES (md5('qa:adj:1')::uuid, 'ADJ-QA-1', 'HCM-A01-2-B', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'COUNT_VARIANCE', md5('qa:ccl:1')::uuid, md5('demo:user:editor')::uuid, md5('qa03:pk1')::uuid) $q$);
SELECT pg_temp.expect_fail('I17 adjustment approved by its requester', $q$
    UPDATE inventory.stock_adjustment SET status = 'APPROVED', decided_by = requested_by, decided_at = NOW() WHERE id = md5('qa:adj:1')::uuid $q$);
SELECT pg_temp.expect_fail('I18 rejection without a reason', $q$
    UPDATE inventory.stock_adjustment SET status = 'REJECTED', decided_by = md5('demo:user:approver')::uuid, decided_at = NOW() WHERE id = md5('qa:adj:1')::uuid $q$);
SELECT pg_temp.expect_fail_by('I19 an adjustment that goes neither way', '"direction"', $q$
    UPDATE inventory.stock_adjustment SET direction = NULL WHERE id = md5('qa:adj:1')::uuid $q$);
SELECT pg_temp.expect_ok('I20 approved by someone else, posted: the carton written off whole and LOST, with its ledger row', $q$
    UPDATE inventory.stock_adjustment SET status = 'APPROVED', decided_by = md5('demo:user:approver')::uuid, decided_at = NOW() WHERE id = md5('qa:adj:1')::uuid;
    UPDATE inventory.stock_adjustment SET status = 'POSTED', posted_at = NOW() WHERE id = md5('qa:adj:1')::uuid;
    UPDATE inventory.stock_package SET status = 'LOST', stock_status = NULL, closed_at = NOW() WHERE id = md5('qa03:pk1')::uuid;
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, reference_type, reference_id, actor_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'COUNT_CORRECTION', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'STOCK_ADJUSTMENT', md5('qa:adj:1')::uuid, md5('demo:user:approver')::uuid, NOW(), md5('qa03:pk1')::uuid) $q$);

\echo '--- inter-warehouse transfer (removed, V20261108000300)'
SELECT pg_temp.expect_fail('I21 transfer orders are gone: stock never changes warehouse', $q$
    SELECT 1 FROM inventory.transfer_order $q$);
SELECT pg_temp.expect_fail('I22 a putaway task has no transfer line to come from', $q$
    INSERT INTO warehouse.putaway_task (id, sku, quantity, status, created_at)
    VALUES (gen_random_uuid(), 'LY-GIAY-DEMO-12OZ-XANH', 4, 'PENDING', NOW()) $q$);

\echo '--- document numbers'
SELECT pg_temp.expect_ok('I36 two document types on one day', $q$
    INSERT INTO platform.document_sequence (document_type, sequence_date, last_value) VALUES ('GR', CURRENT_DATE, 1), ('TO', CURRENT_DATE, 1) $q$);
SELECT pg_temp.expect_fail('I37 lowercase document type', $q$
    INSERT INTO platform.document_sequence (document_type, sequence_date) VALUES ('gr', CURRENT_DATE) $q$);

\echo '--- holds: no deadline at all (V20261117000300; the column went with V20261231000500, ADR-0011)'
SELECT pg_temp.expect_ok('I38 a pinned hold (no deadline) on a carton', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                         created_at, contract_signed_at)
    VALUES (md5('qa:order:pinned')::uuid, 'SO-QA-PIN', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'CONFIRMED',
            10000000, 'VND', NOW(), NOW(), NOW());
    INSERT INTO inventory.stock_reservation (id, stock_package_id, order_id, root_request_id, request_id, cartons, units_per_carton,
                                            reserved_at, status)
    SELECT md5('qa:res:pinned')::uuid, p.id, md5('qa:order:pinned')::uuid, md5('qa:req:pinned')::uuid,
           md5('qa:req:pinned')::uuid, 1, p.nominal_qty, NOW(), 'HELD'
      FROM inventory.stock_package p WHERE p.id = md5('qa03x:demo:p25')::uuid $q$);
SELECT pg_temp.expect_fail('I39 a deadline on a hold: the column is gone', $q$
    UPDATE inventory.stock_reservation SET expires_at = NOW() + INTERVAL '30 minutes'
     WHERE id = md5('qa:res:pinned')::uuid $q$);
SELECT pg_temp.expect_ok('I40 a pinned hold released when its order is cancelled', $q$
    UPDATE inventory.stock_reservation SET status = 'RELEASED', release_reason = 'ORDER_CANCELLED',
           closed_at = NOW() WHERE id = md5('qa:res:pinned')::uuid $q$);

\echo '--- adjustment withdrawal; scrap and samples are reasons of the history (SCRUM-459; 20 BR-04)'
-- An adjustment names a carton (V20261231000100): the second carton of this script, in stock on HCM-A02-1-A. What the
-- table says of a SCRAP or SAMPLE row of before (ck_stock_adjustment_writes_down) is asked in 62_carton_counts.sql.
SELECT pg_temp.expect_fail_by('I41 a SCRAP adjustment that adds stock: no new SCRAP, whichever way', 'BR-04 lists six', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code, requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA-S1', 'HCM-A02-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'RESTOCK', 'SCRAP', md5('demo:user:editor')::uuid, md5('qa03:pk2')::uuid) $q$);
SELECT pg_temp.expect_fail_by('I41b a SAMPLE adjustment that takes stock: no new SAMPLE either', 'BR-04 lists six', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code, requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA-S1B', 'HCM-A02-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'SAMPLE', md5('demo:user:editor')::uuid, md5('qa03:pk2')::uuid) $q$);
SELECT pg_temp.expect_ok('I42 a DAMAGED adjustment that takes the carton', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code, requested_by, stock_package_id)
    VALUES (md5('qa:adj:sample')::uuid, 'ADJ-QA-S2', 'HCM-A02-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'DAMAGED', md5('demo:user:editor')::uuid, md5('qa03:pk2')::uuid) $q$);
SELECT pg_temp.expect_fail('I43 WITHDRAWN without the time it was withdrawn', $q$
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN' WHERE id = md5('qa:adj:sample')::uuid $q$);
SELECT pg_temp.expect_fail('I44 WITHDRAWN with a decider', $q$
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW(),
           decided_by = md5('demo:user:approver')::uuid, decided_at = NOW() WHERE id = md5('qa:adj:sample')::uuid $q$);
SELECT pg_temp.expect_ok('I45 withdrawn by its requester', $q$
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa:adj:sample')::uuid $q$);
SELECT pg_temp.expect_fail_by('I46 a pending adjustment that says it was withdrawn', 'ck_stock_adjustment_withdrawn', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code, requested_by, withdrawn_at, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA-S3', 'HCM-A02-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'DAMAGED', md5('demo:user:editor')::uuid, NOW(), md5('qa03:pk2')::uuid) $q$);

\echo '--- in-app inbox (SCRUM-147, V20261014000400); I50-I54 belong to the stock-alert migration'
INSERT INTO notification.user_notification (id, recipient_id, category, severity, title, dedupe_key, created_at)
VALUES (md5('qa:inbox:1')::uuid, md5('demo:user:editor')::uuid, 'QA', 'INFO', 'QA message', 'qa:key', NOW());
SELECT pg_temp.expect_ok('I55 the same dedupe_key for another recipient', $q$
    INSERT INTO notification.user_notification (id, recipient_id, category, severity, title, dedupe_key)
    VALUES (md5('qa:inbox:other')::uuid, md5('demo:user:approver')::uuid, 'QA', 'WARN', 'QA message', 'qa:key') $q$);
SELECT pg_temp.expect_fail('I55 the same dedupe_key twice for one recipient', $q$
    INSERT INTO notification.user_notification (id, recipient_id, category, severity, title, dedupe_key)
    VALUES (md5('qa:inbox:2')::uuid, md5('demo:user:editor')::uuid, 'QA', 'INFO', 'QA message again', 'qa:key') $q$);
SELECT pg_temp.expect_fail('I56 a message read before it was created', $q$
    UPDATE notification.user_notification SET read_at = created_at - INTERVAL '1 second'
     WHERE id = md5('qa:inbox:1')::uuid $q$);
SELECT pg_temp.expect_ok('I56 a message read after it was created', $q$
    UPDATE notification.user_notification SET read_at = created_at + INTERVAL '1 second'
     WHERE id = md5('qa:inbox:1')::uuid $q$);
SELECT pg_temp.expect_fail('I56 a severity the bell does not know', $q$
    INSERT INTO notification.user_notification (id, recipient_id, category, severity, title, dedupe_key)
    VALUES (md5('qa:inbox:3')::uuid, md5('demo:user:editor')::uuid, 'QA', 'URGENT', 'QA message', 'qa:key3') $q$);
SELECT pg_temp.expect_fail('I56 a link that leaves the application', $q$
    INSERT INTO notification.user_notification (id, recipient_id, category, severity, title, link, dedupe_key)
    VALUES (md5('qa:inbox:4')::uuid, md5('demo:user:editor')::uuid, 'QA', 'INFO', 'QA message', '//evil.example/x', 'qa:key4') $q$);
SELECT pg_temp.expect_fail('I56 a message for a user that does not exist', $q$
    INSERT INTO notification.user_notification (id, recipient_id, category, severity, title, dedupe_key)
    VALUES (md5('qa:inbox:5')::uuid, md5('qa:no-such-user')::uuid, 'QA', 'INFO', 'QA message', 'qa:key5') $q$);

-- I50-I54 (stock alerts and their dirty queue) went with the alerts (team plan 5-1, V20261111000300): see
-- 42_alerts_and_expiry_removed.sql.

ROLLBACK;
\echo 'PASS 03_inventory_ops'
