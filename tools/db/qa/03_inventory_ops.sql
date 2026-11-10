-- =============================================================================
-- Adversarial checks + happy paths: stock ledger, adjustments, cycle counts, move tasks (docs 07, 11). Transfers
-- between warehouses were removed (V20261108000300). Needs the demo seed. Rolled back at the end.
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

\echo '--- inter-warehouse transfer (removed, V20261108000300)'
SELECT pg_temp.expect_fail('I21 transfer orders are gone: stock never changes warehouse', $q$
    SELECT 1 FROM inventory.transfer_order $q$);
SELECT pg_temp.expect_fail('I22 a putaway task has no transfer line to come from', $q$
    INSERT INTO warehouse.putaway_task (id, sku, quantity, status, created_at)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 4, 'PENDING', NOW()) $q$);

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

\echo '--- holds: a deadline only while the order waits for payment (SCRUM-465, V20261013000100)'
SELECT pg_temp.expect_ok('I38 a pinned hold (no deadline) on a stock row', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                         created_at, contract_signed_at)
    VALUES (md5('qa:order:pinned')::uuid, 'SO-QA-PIN', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'CONFIRMED',
            10000000, 'VND', NOW(), NOW(), NOW());
    INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity,
                                            reserved_at, expires_at, status)
    SELECT md5('qa:res:pinned')::uuid, s.id, md5('qa:order:pinned')::uuid, md5('qa:req:pinned')::uuid,
           md5('qa:req:pinned')::uuid, 1, NOW(), NULL, 'HELD'
      FROM inventory.stock_item s WHERE s.sku = 'SOFA-3S-GREY' AND s.location_code = 'HCM-A01-2-B' LIMIT 1 $q$);
SELECT pg_temp.expect_fail('I39 an EXPIRED hold that never had a deadline', $q$
    UPDATE inventory.stock_reservation SET status = 'EXPIRED', release_reason = 'RESERVATION_EXPIRED',
           closed_at = NOW() WHERE id = md5('qa:res:pinned')::uuid $q$);
SELECT pg_temp.expect_ok('I40 a pinned hold released when its order is cancelled', $q$
    UPDATE inventory.stock_reservation SET status = 'RELEASED', release_reason = 'ORDER_CANCELLED',
           closed_at = NOW() WHERE id = md5('qa:res:pinned')::uuid $q$);

\echo '--- adjustment withdrawal, scrap and samples (SCRUM-459)'
SELECT pg_temp.expect_fail('I41 a SCRAP adjustment that adds stock', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code, requested_by)
    VALUES (gen_random_uuid(), 'ADJ-QA-S1', 'HCM-A01-2-B', 'SOFA-3S-GREY', 2, 'SCRAP', md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_ok('I42 a SAMPLE adjustment that takes stock', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code, requested_by)
    VALUES (md5('qa:adj:sample')::uuid, 'ADJ-QA-S2', 'HCM-A01-2-B', 'SOFA-3S-GREY', -1, 'SAMPLE', md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('I43 WITHDRAWN without the time it was withdrawn', $q$
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN' WHERE id = md5('qa:adj:sample')::uuid $q$);
SELECT pg_temp.expect_fail('I44 WITHDRAWN with a decider', $q$
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW(),
           decided_by = md5('demo:user:approver')::uuid, decided_at = NOW() WHERE id = md5('qa:adj:sample')::uuid $q$);
SELECT pg_temp.expect_ok('I45 withdrawn by its requester', $q$
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa:adj:sample')::uuid $q$);
SELECT pg_temp.expect_fail('I46 a pending adjustment that says it was withdrawn', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code, requested_by, withdrawn_at)
    VALUES (gen_random_uuid(), 'ADJ-QA-S3', 'HCM-A01-2-B', 'SOFA-3S-GREY', -1, 'DAMAGED', md5('demo:user:editor')::uuid, NOW()) $q$);

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

\echo '--- stock alerts and the dirty queue (SCRUM-147, V20261014000900)'
SELECT pg_temp.expect_ok('I50 one open REORDER alert for a SKU', $q$
    INSERT INTO inventory.stock_alert (id, kind, sku, observed_qty, threshold_qty, opened_at, last_observed_at)
    VALUES (md5('qa:alert:1')::uuid, 'REORDER', 'SOFA-3S-GREY', 3, 10, NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('I50 a second open alert of the same kind and SKU', $q$
    INSERT INTO inventory.stock_alert (id, kind, sku, observed_qty, threshold_qty, opened_at, last_observed_at)
    VALUES (md5('qa:alert:2')::uuid, 'REORDER', 'SOFA-3S-GREY', 2, 10, NOW(), NOW()) $q$);
SELECT pg_temp.expect_ok('I50 the same SKU under another kind', $q$
    INSERT INTO inventory.stock_alert (id, kind, sku, observed_qty, threshold_qty, opened_at, last_observed_at)
    VALUES (md5('qa:alert:3')::uuid, 'SAFETY', 'SOFA-3S-GREY', 3, 5, NOW(), NOW()) $q$);
SELECT pg_temp.expect_ok('I50 an acknowledged alert still blocks a duplicate (it is not resolved)', $q$
    UPDATE inventory.stock_alert SET status = 'ACKNOWLEDGED', acknowledged_by = md5('demo:user:editor')::uuid,
           acknowledged_at = NOW() WHERE id = md5('qa:alert:3')::uuid $q$);
SELECT pg_temp.expect_fail('I50 a duplicate of the acknowledged alert', $q$
    INSERT INTO inventory.stock_alert (id, kind, sku, observed_qty, threshold_qty, opened_at, last_observed_at)
    VALUES (md5('qa:alert:4')::uuid, 'SAFETY', 'SOFA-3S-GREY', 1, 5, NOW(), NOW()) $q$);
SELECT pg_temp.expect_ok('I50 once resolved, the condition can open a new episode', $q$
    UPDATE inventory.stock_alert SET status = 'RESOLVED', resolved_at = NOW(), resolution = 'RECOVERED'
     WHERE id = md5('qa:alert:1')::uuid;
    INSERT INTO inventory.stock_alert (id, kind, sku, observed_qty, threshold_qty, opened_at, last_observed_at)
    VALUES (md5('qa:alert:5')::uuid, 'REORDER', 'SOFA-3S-GREY', 1, 10, NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('I50 two open EXPIRING alerts for the same lot with no lot number', $q$
    INSERT INTO inventory.stock_alert (id, kind, sku, warehouse_id, expiry_date, observed_qty, opened_at, last_observed_at)
    VALUES (md5('qa:alert:6')::uuid, 'EXPIRING', 'SOFA-3S-GREY', md5('demo:wh:HCM')::uuid, CURRENT_DATE + 5, 4, NOW(), NOW()),
           (md5('qa:alert:7')::uuid, 'EXPIRING', 'SOFA-3S-GREY', md5('demo:wh:HCM')::uuid, CURRENT_DATE + 5, 4, NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('I51 RESOLVED without the time it was resolved', $q$
    UPDATE inventory.stock_alert SET status = 'RESOLVED', resolution = 'RECOVERED' WHERE id = md5('qa:alert:5')::uuid $q$);
SELECT pg_temp.expect_fail('I51 RESOLVED without how it was resolved', $q$
    UPDATE inventory.stock_alert SET status = 'RESOLVED', resolved_at = NOW() WHERE id = md5('qa:alert:5')::uuid $q$);
SELECT pg_temp.expect_fail('I51 an open alert that says it was resolved', $q$
    UPDATE inventory.stock_alert SET resolved_at = NOW(), resolution = 'RECOVERED' WHERE id = md5('qa:alert:5')::uuid $q$);
SELECT pg_temp.expect_fail('I51 ACKNOWLEDGED with nobody who acknowledged it', $q$
    UPDATE inventory.stock_alert SET status = 'ACKNOWLEDGED' WHERE id = md5('qa:alert:5')::uuid $q$);
SELECT pg_temp.expect_fail('I51 a resolution the screen does not know', $q$
    UPDATE inventory.stock_alert SET status = 'RESOLVED', resolved_at = NOW(), resolution = 'FORGOTTEN'
     WHERE id = md5('qa:alert:5')::uuid $q$);
SELECT pg_temp.expect_fail('I51 a REORDER alert resolved as EXPIRED_OUT', $q$
    UPDATE inventory.stock_alert SET status = 'RESOLVED', resolved_at = NOW(), resolution = 'EXPIRED_OUT'
     WHERE id = md5('qa:alert:5')::uuid $q$);
SELECT pg_temp.expect_fail('I52 an EXPIRING alert without an expiry date', $q$
    INSERT INTO inventory.stock_alert (id, kind, sku, warehouse_id, observed_qty, opened_at, last_observed_at)
    VALUES (md5('qa:alert:8')::uuid, 'EXPIRING', 'SOFA-3S-GREY', md5('demo:wh:HCM')::uuid, 4, NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('I52 an EXPIRED alert without a warehouse', $q$
    INSERT INTO inventory.stock_alert (id, kind, sku, expiry_date, observed_qty, opened_at, last_observed_at)
    VALUES (md5('qa:alert:9')::uuid, 'EXPIRED', 'SOFA-3S-GREY', CURRENT_DATE - 1, 4, NOW(), NOW()) $q$);
SELECT pg_temp.expect_ok('I52 an EXPIRED alert with a warehouse, a lot, a date and a held reservation', $q$
    INSERT INTO inventory.stock_alert (id, kind, sku, warehouse_id, lot_number, expiry_date, observed_qty,
                                       reservation_impaired, opened_at, last_observed_at)
    VALUES (md5('qa:alert:10')::uuid, 'EXPIRED', 'SOFA-3S-GREY', md5('demo:wh:HCM')::uuid, 'LOT-QA', CURRENT_DATE - 1, 4,
            TRUE, NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('I53 a REORDER alert that names a warehouse', $q$
    INSERT INTO inventory.stock_alert (id, kind, sku, warehouse_id, observed_qty, threshold_qty, opened_at, last_observed_at)
    VALUES (md5('qa:alert:11')::uuid, 'REORDER', 'SOFA-3S-GREY', md5('demo:wh:HCM')::uuid, 1, 10, NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('I53 a SAFETY alert that names a lot', $q$
    INSERT INTO inventory.stock_alert (id, kind, sku, lot_number, observed_qty, threshold_qty, opened_at, last_observed_at)
    VALUES (md5('qa:alert:12')::uuid, 'SAFETY', 'SOFA-3S-GREY', 'LOT-1', 1, 10, NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('I53 a REORDER alert without its threshold', $q$
    INSERT INTO inventory.stock_alert (id, kind, sku, observed_qty, opened_at, last_observed_at)
    VALUES (md5('qa:alert:13')::uuid, 'REORDER', 'SOFA-3S-GREY', 1, NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('I53 a held reservation flagged on a REORDER alert', $q$
    UPDATE inventory.stock_alert SET reservation_impaired = TRUE WHERE id = md5('qa:alert:5')::uuid $q$);
SELECT pg_temp.expect_fail('I53 an alert for a SKU that has no inventory item', $q$
    INSERT INTO inventory.stock_alert (id, kind, sku, observed_qty, threshold_qty, opened_at, last_observed_at)
    VALUES (md5('qa:alert:14')::uuid, 'REORDER', 'NO-SUCH-SKU', 1, 10, NOW(), NOW()) $q$);
DELETE FROM inventory.stock_alert_dirty;
SELECT pg_temp.expect_ok('I54 a stock row whose on_hand changes marks its SKU dirty', $q$
    UPDATE inventory.stock_item SET on_hand = on_hand + 1
     WHERE sku = 'SOFA-3S-GREY' AND location_code = 'HCM-A01-2-B';
    DO $d$ BEGIN
        IF (SELECT count(*) FROM inventory.stock_alert_dirty WHERE sku = 'SOFA-3S-GREY') <> 1 THEN
            RAISE EXCEPTION 'on_hand change did not mark the SKU dirty exactly once';
        END IF;
    END $d$ $q$);
DELETE FROM inventory.stock_alert_dirty;
SELECT pg_temp.expect_ok('I54 an UPDATE that does not change on_hand, status or expiry does not mark it', $q$
    UPDATE inventory.stock_item SET on_hand = on_hand, last_modified_by = 'qa'
     WHERE sku = 'SOFA-3S-GREY' AND location_code = 'HCM-A01-2-B';
    DO $d$ BEGIN
        IF EXISTS (SELECT 1 FROM inventory.stock_alert_dirty) THEN
            RAISE EXCEPTION 'a write that changed nothing the thresholds read marked the SKU dirty';
        END IF;
    END $d$ $q$);
SELECT pg_temp.expect_ok('I54 a status change and a new stock row mark the SKU dirty', $q$
    UPDATE inventory.stock_item SET status = 'QUARANTINE'
     WHERE sku = 'SOFA-3S-GREY' AND location_code = 'HCM-A01-2-B' AND status = 'AVAILABLE';
    DO $d$ BEGIN
        IF NOT EXISTS (SELECT 1 FROM inventory.stock_alert_dirty WHERE sku = 'SOFA-3S-GREY') THEN
            RAISE EXCEPTION 'a status change did not mark the SKU dirty';
        END IF;
    END $d$ $q$);

ROLLBACK;
\echo 'PASS 03_inventory_ops'
