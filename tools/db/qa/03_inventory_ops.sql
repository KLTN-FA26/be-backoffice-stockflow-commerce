-- =============================================================================
-- Adversarial checks + happy paths: stock ledger, adjustments, cycle counts, transfer orders,
-- move tasks (docs 07, 10, 11). Needs the demo seed. Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;

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
INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
VALUES (md5('qa:loc:HN-RCV')::uuid, md5('qa:wh:HN')::uuid, 'AREA', 'HN-RCV01', 'NORMAL', FALSE, FALSE, 'ACTIVE');
INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation, is_obstacle, status)
VALUES (gen_random_uuid(), md5('qa:wh:HN')::uuid, md5('qa:loc:HN-RCV')::uuid, 'RCV01', 'RECEIVING', 'x', 1, 1, 5, 5, 0, FALSE, 'ACTIVE');

\echo '--- stock movement ledger (docs 11 BR-06)'
SELECT pg_temp.expect_ok('I1 a MOVE between two bins', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
        reference_type, reference_id, actor_id, occurred_at)
    VALUES (md5('qa:mv:1')::uuid, 'MOVE', 'SOFA-3S-GREY', 'HCM-A01-2-B', 'HCM-A01-1-A', 2, 'MOVE_TASK', gen_random_uuid(),
        md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('I2 a MOVE to the same location', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'HCM-A01-2-B', 'HCM-A01-2-B', 1, 'MOVE_TASK', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_fail('I3 a RECEIPT that comes from a location', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'RECEIPT', 'SOFA-3S-GREY', 'HCM-A01-2-B', 'HCM-RCV01', 1, 'GOODS_RECEIPT_LINE', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_fail('I4 an ADJUSTMENT with both sides', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'ADJUSTMENT', 'SOFA-3S-GREY', 'HCM-A01-2-B', 'HCM-A01-1-A', 1, 'STOCK_ADJUSTMENT', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_fail('I5 a STATUS_CHANGE that changes nothing', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity, from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'STATUS_CHANGE', 'SOFA-3S-GREY', 'HCM-QC01', 'HCM-QC01', 1, 'QUARANTINE', 'QUARANTINE', 'QC_INSPECTION', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_fail('I6 movement to a location that does not exist', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'RECEIPT', 'SOFA-3S-GREY', 'HCM-NOWHERE', 1, 'GOODS_RECEIPT_LINE', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_fail('I7 movement of an unknown sku', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, to_location_code, quantity, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'RECEIPT', 'NOPE', 'HCM-RCV01', 1, 'GOODS_RECEIPT_LINE', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_fail('I8 ledger row edited', $q$ UPDATE inventory.stock_movement SET quantity = 3 WHERE id = md5('qa:mv:1')::uuid $q$);
SELECT pg_temp.expect_fail('I9 ledger row deleted', $q$ DELETE FROM inventory.stock_movement WHERE id = md5('qa:mv:1')::uuid $q$);

\echo '--- cycle count and stock adjustment'
INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status, started_at, assigned_user_id)
VALUES (md5('qa:cc:1')::uuid, 'CC-QA-1', md5('demo:wh:HCM')::uuid, 'SHORT_PICK', 'IN_PROGRESS', NOW(), md5('demo:user:editor')::uuid);
SELECT pg_temp.expect_fail('I10 counting a location of another warehouse', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty)
    VALUES (gen_random_uuid(), md5('qa:cc:1')::uuid, 'HN-RCV01', 'SOFA-3S-GREY', 0) $q$);
SELECT pg_temp.expect_ok('I11 a count line, counted 23 of 25 expected', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty, counted_qty, counted_by, counted_at)
    VALUES (md5('qa:ccl:1')::uuid, md5('qa:cc:1')::uuid, 'HCM-A01-2-B', 'SOFA-3S-GREY', 25, 23, md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('I12 counted quantity without who/when', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty, counted_qty)
    VALUES (gen_random_uuid(), md5('qa:cc:1')::uuid, 'HCM-A02-1-A', 'SOFA-3S-GREY', 8, 8) $q$);
SELECT pg_temp.expect_fail('I13 the same slot twice in one count', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty)
    VALUES (gen_random_uuid(), md5('qa:cc:1')::uuid, 'HCM-A01-2-B', 'SOFA-3S-GREY', 25) $q$);
SELECT pg_temp.expect_fail('I14 COMPLETED count without completed_at', $q$
    UPDATE inventory.cycle_count SET status = 'COMPLETED' WHERE id = md5('qa:cc:1')::uuid $q$);
SELECT pg_temp.expect_fail('I15 count variance adjustment without its count line', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code, requested_by)
    VALUES (gen_random_uuid(), 'ADJ-QA-X', 'HCM-A01-2-B', 'SOFA-3S-GREY', -2, 'COUNT_VARIANCE', md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_ok('I16 variance adjustment requested', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code, cycle_count_line_id, requested_by)
    VALUES (md5('qa:adj:1')::uuid, 'ADJ-QA-1', 'HCM-A01-2-B', 'SOFA-3S-GREY', -2, 'COUNT_VARIANCE', md5('qa:ccl:1')::uuid, md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('I17 adjustment approved by its requester', $q$
    UPDATE inventory.stock_adjustment SET status = 'APPROVED', decided_by = requested_by, decided_at = NOW() WHERE id = md5('qa:adj:1')::uuid $q$);
SELECT pg_temp.expect_fail('I18 rejection without a reason', $q$
    UPDATE inventory.stock_adjustment SET status = 'REJECTED', decided_by = md5('demo:user:approver')::uuid, decided_at = NOW() WHERE id = md5('qa:adj:1')::uuid $q$);
SELECT pg_temp.expect_fail('I19 zero adjustment', $q$
    UPDATE inventory.stock_adjustment SET quantity_delta = 0 WHERE id = md5('qa:adj:1')::uuid $q$);
SELECT pg_temp.expect_ok('I20 approved by someone else, posted, with its ledger row', $q$
    UPDATE inventory.stock_adjustment SET status = 'APPROVED', decided_by = md5('demo:user:approver')::uuid, decided_at = NOW() WHERE id = md5('qa:adj:1')::uuid;
    UPDATE inventory.stock_adjustment SET status = 'POSTED', posted_at = NOW() WHERE id = md5('qa:adj:1')::uuid;
    UPDATE inventory.stock_item SET on_hand = on_hand - 2 WHERE id = '11111111-1111-4111-8111-111111111111';
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, quantity, reference_type, reference_id, actor_id, occurred_at)
    VALUES (gen_random_uuid(), 'COUNT_CORRECTION', 'SOFA-3S-GREY', 'HCM-A01-2-B', 2, 'STOCK_ADJUSTMENT', md5('qa:adj:1')::uuid, md5('demo:user:approver')::uuid, NOW()) $q$);

\echo '--- inter-warehouse transfer (docs 10)'
SELECT pg_temp.expect_fail('I21 transfer within one warehouse (BR-01)', $q$
    INSERT INTO inventory.transfer_order (id, transfer_number, from_warehouse_id, to_warehouse_id)
    VALUES (gen_random_uuid(), 'TO-QA-X', md5('demo:wh:HCM')::uuid, md5('demo:wh:HCM')::uuid) $q$);
INSERT INTO inventory.transfer_order (id, transfer_number, from_warehouse_id, to_warehouse_id, reason)
VALUES (md5('qa:to:1')::uuid, 'TO-QA-1', md5('demo:wh:HCM')::uuid, md5('qa:wh:HN')::uuid, 'REBALANCING');
INSERT INTO inventory.transfer_order_line (id, transfer_order_id, line_no, sku, requested_qty)
VALUES (md5('qa:tol:1')::uuid, md5('qa:to:1')::uuid, 1, 'SOFA-3S-GREY', 5);
SELECT pg_temp.expect_fail('I22 approved by its submitter', $q$
    UPDATE inventory.transfer_order SET status = 'APPROVED', submitted_by = md5('demo:user:editor')::uuid, submitted_at = NOW(),
        approved_by = md5('demo:user:editor')::uuid, approved_at = NOW() WHERE id = md5('qa:to:1')::uuid $q$);
SELECT pg_temp.expect_fail('I23 shipping more than requested', $q$
    UPDATE inventory.transfer_order_line SET shipped_qty = 6 WHERE id = md5('qa:tol:1')::uuid $q$);
SELECT pg_temp.expect_fail('I24 IN_TRANSIT without dispatch evidence', $q$
    UPDATE inventory.transfer_order SET status = 'IN_TRANSIT', submitted_by = md5('demo:user:editor')::uuid, submitted_at = NOW(),
        approved_by = md5('demo:user:approver')::uuid, approved_at = NOW() WHERE id = md5('qa:to:1')::uuid $q$);
SELECT pg_temp.expect_ok('I25 submit, approve, dispatch 5', $q$
    UPDATE inventory.transfer_order SET status = 'IN_TRANSIT', submitted_by = md5('demo:user:editor')::uuid, submitted_at = NOW(),
        approved_by = md5('demo:user:approver')::uuid, approved_at = NOW(),
        dispatched_by = md5('demo:user:editor')::uuid, dispatched_at = NOW() WHERE id = md5('qa:to:1')::uuid;
    UPDATE inventory.transfer_order_line SET shipped_qty = 5 WHERE id = md5('qa:tol:1')::uuid $q$);
SELECT pg_temp.expect_fail('I26 cancelled after dispatch (BR-04)', $q$
    UPDATE inventory.transfer_order SET status = 'CANCELLED', cancel_reason = 'x' WHERE id = md5('qa:to:1')::uuid $q$);
SELECT pg_temp.expect_fail('I27 destination receives more than was shipped', $q$
    UPDATE inventory.transfer_order_line SET received_qty = 6 WHERE id = md5('qa:tol:1')::uuid $q$);
SELECT pg_temp.expect_fail('I28 short receipt without a discrepancy note (BR-05)', $q$
    UPDATE inventory.transfer_order_line SET received_qty = 4 WHERE id = md5('qa:tol:1')::uuid $q$);
SELECT pg_temp.expect_ok('I29 4 good + 1 damaged, noted; putaway at the destination from the TO line', $q$
    UPDATE inventory.transfer_order_line SET received_qty = 4, damaged_qty = 1, received_by = md5('demo:user:approver')::uuid WHERE id = md5('qa:tol:1')::uuid;
    UPDATE inventory.transfer_order SET status = 'RECEIVED', received_at = NOW() WHERE id = md5('qa:to:1')::uuid;
    INSERT INTO warehouse.putaway_task (id, transfer_order_line_id, sku, quantity, status, created_at)
    VALUES (gen_random_uuid(), md5('qa:tol:1')::uuid, 'SOFA-3S-GREY', 4, 'PENDING', NOW()) $q$);
SELECT pg_temp.expect_fail('I30 CLOSED (short-close) without a reason', $q$
    UPDATE inventory.transfer_order SET status = 'CLOSED', closed_at = NOW() WHERE id = md5('qa:to:1')::uuid $q$);

\echo '--- intra-warehouse move task (docs 11)'
SELECT pg_temp.expect_fail('I31 move to a location of another warehouse (BR-01)', $q$
    INSERT INTO warehouse.move_task (id, task_number, warehouse_id, origin, sku, from_location_id, to_location_id, requested_qty)
    VALUES (gen_random_uuid(), 'MT-QA-X', md5('demo:wh:HCM')::uuid, 'MANUAL', 'SOFA-3S-GREY',
        md5('demo:loc:HCM-A01-2-B')::uuid, md5('qa:loc:HN-RCV')::uuid, 1) $q$);
SELECT pg_temp.expect_fail('I32 a manual move waiting as SUGGESTED', $q$
    INSERT INTO warehouse.move_task (id, task_number, warehouse_id, origin, status, sku, from_location_id, to_location_id, requested_qty)
    VALUES (gen_random_uuid(), 'MT-QA-Y', md5('demo:wh:HCM')::uuid, 'MANUAL', 'SUGGESTED', 'SOFA-3S-GREY',
        md5('demo:loc:HCM-A01-2-B')::uuid, md5('demo:loc:HCM-A01-1-A')::uuid, 1) $q$);
SELECT pg_temp.expect_fail('I33 re-slotting move executed without approval', $q$
    INSERT INTO warehouse.move_task (id, task_number, warehouse_id, origin, status, sku, from_location_id, to_location_id, requested_qty)
    VALUES (gen_random_uuid(), 'MT-QA-Z', md5('demo:wh:HCM')::uuid, 'RESLOTTING', 'PENDING', 'SOFA-3S-GREY',
        md5('demo:loc:HCM-A01-2-B')::uuid, md5('demo:loc:HCM-A01-1-A')::uuid, 1) $q$);
SELECT pg_temp.expect_ok('I34 replenishment move, assigned, done', $q$
    INSERT INTO warehouse.move_task (id, task_number, warehouse_id, origin, reason, status, sku, from_location_id, to_location_id,
        requested_qty, picked_qty, placed_qty, assigned_user_id, completed_at)
    VALUES (gen_random_uuid(), 'MT-QA-1', md5('demo:wh:HCM')::uuid, 'REPLENISHMENT', 'REPLENISHMENT', 'COMPLETED', 'SOFA-3S-GREY',
        md5('demo:loc:HCM-A01-2-B')::uuid, md5('demo:loc:HCM-A01-1-A')::uuid, 3, 3, 3, md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail('I35 DISCREPANCY with matching quantities', $q$
    INSERT INTO warehouse.move_task (id, task_number, warehouse_id, origin, status, sku, from_location_id, to_location_id,
        requested_qty, picked_qty, placed_qty)
    VALUES (gen_random_uuid(), 'MT-QA-2', md5('demo:wh:HCM')::uuid, 'MANUAL', 'DISCREPANCY', 'SOFA-3S-GREY',
        md5('demo:loc:HCM-A01-2-B')::uuid, md5('demo:loc:HCM-A01-1-A')::uuid, 3, 3, 3) $q$);

\echo '--- document numbers'
SELECT pg_temp.expect_ok('I36 two document types on one day', $q$
    INSERT INTO platform.document_sequence (document_type, sequence_date, last_value) VALUES ('GR', CURRENT_DATE, 1), ('TO', CURRENT_DATE, 1) $q$);
SELECT pg_temp.expect_fail('I37 lowercase document type', $q$
    INSERT INTO platform.document_sequence (document_type, sequence_date) VALUES ('gr', CURRENT_DATE) $q$);

ROLLBACK;
\echo 'PASS 03_inventory_ops'
