-- =============================================================================
-- Adversarial checks + happy paths: counts and adjustments by carton, a lot on every stock row, one row per carton
-- (V20261113000300, team plan 4C-3; kltn-docs 20 §4.2, §4.3, BR-04, BR-09, BR-10, BR-11, BR-14, BR-18, BR-22). Needs the
-- demo seed. Rolled back at the end.
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

-- Fixtures: a carton unit of 4 sofas; three cartons in stock (two on a bin, one on another) each with its one row;
-- a MANUAL count of the bin with a line for the first carton; the editor and the approver of the demo.
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa62:unit')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 'QA62_CTN', 4);
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty,
                                     location_code, status)
VALUES (md5('qa62:a')::uuid, 'QA62-LPN-A', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa62:unit')::uuid, 4, 4,
        'HCM-A01-1-A', 'IN_STOCK'),
       (md5('qa62:b')::uuid, 'QA62-LPN-B', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa62:unit')::uuid, 4, 4,
        'HCM-A01-1-A', 'IN_STOCK'),
       (md5('qa62:c')::uuid, 'QA62-LPN-C', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa62:unit')::uuid, 4, 4,
        'HCM-A01-1-B', 'IN_STOCK');
INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                  received_at, stock_package_id)
VALUES (md5('qa62:a:row')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 'UNTRACED', 4, 0, 'AVAILABLE', NOW(),
        NOW() - interval '1 day', md5('qa62:a')::uuid),
       (md5('qa62:b:row')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 'UNTRACED', 4, 0, 'AVAILABLE', NOW(),
        NOW() - interval '1 day', md5('qa62:b')::uuid),
       (md5('qa62:c:row')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-B', 'UNTRACED', 4, 0, 'AVAILABLE', NOW(),
        NOW() - interval '1 day', md5('qa62:c')::uuid);
INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status, started_at)
VALUES (md5('qa62:count')::uuid, 'CC-QA-62', md5('demo:wh:HCM')::uuid, 'MANUAL', 'IN_PROGRESS', NOW());

\echo '--- a lot on every stock row (20 BR-22)'
SELECT pg_temp.expect_true('CL1 stock_item.lot_number is NOT NULL',
    (SELECT is_nullable = 'NO' FROM information_schema.columns
      WHERE table_schema = 'inventory' AND table_name = 'stock_item' AND column_name = 'lot_number'));
SELECT pg_temp.expect_true('CL2 stock_item.lot_id is NOT NULL',
    (SELECT is_nullable = 'NO' FROM information_schema.columns
      WHERE table_schema = 'inventory' AND table_name = 'stock_item' AND column_name = 'lot_id'));
SELECT pg_temp.expect_fail('CL3 a loose row with no lot (the sofa is not lot-tracked: still refused)', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 'HCM-A02-1-B', 1, 0, 'AVAILABLE', NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('CL4 a row whose lot number is blank', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                      received_at)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 'HCM-A02-1-B', '   ', 1, 0, 'AVAILABLE', NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('CL5 a row emptied of its lot', $q$
    UPDATE inventory.stock_item SET lot_number = NULL WHERE id = md5('qa62:a:row')::uuid $q$);
SELECT pg_temp.expect_fail('CL6 a row whose lot is blanked', $q$
    UPDATE inventory.stock_item SET lot_number = ' ' WHERE id = md5('qa62:a:row')::uuid $q$);
SELECT pg_temp.expect_ok('CL7 a loose row with the lot UNTRACED (still written by a put-back, a return, a damage report)', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                      received_at)
    VALUES (md5('qa62:loose')::uuid, 'SOFA-3S-GREY', 'HCM-A02-1-B', 'UNTRACED', 1, 0, 'AVAILABLE', NOW(), NOW()) $q$);

\echo '--- a carton is one stock row (20 BR-18)'
SELECT pg_temp.expect_true('CR1 uk_stock_item_package exists, partial on stock_package_id',
    EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'inventory' AND indexname = 'uk_stock_item_package'
               AND indexdef LIKE '%UNIQUE%(stock_package_id)%WHERE%stock_package_id IS NOT NULL%'));
SELECT pg_temp.expect_true('CR2 the old layer index is gone',
    NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'inventory' AND indexname = 'uk_stock_item_package_layer'));
SELECT pg_temp.expect_fail('CR3 a second row of one carton, emptied, on the same bin', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                      received_at, stock_package_id)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 'HCM-A01-1-A', 'UNTRACED', 0, 0, 'AVAILABLE', NOW(),
            NOW() - interval '3 day', md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail('CR4 a second row of one carton, of another lot', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                      received_at, stock_package_id)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 'HCM-A01-1-A', 'LOT-QA62', 0, 0, 'AVAILABLE', NOW(), NOW(),
            md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_ok('CR5 two loose rows (no carton) are not one another''s duplicate', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                      received_at)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 'HCM-A02-1-B', 'LOT-QA62-X', 1, 0, 'AVAILABLE', NOW(), NOW()),
           (gen_random_uuid(), 'SOFA-3S-GREY', 'HCM-A02-1-B', 'LOT-QA62-Y', 1, 0, 'AVAILABLE', NOW(), NOW()) $q$);

\echo '--- a carton is counted, and is in stock, at its standard count only (20 BR-18)'
SELECT pg_temp.expect_fail('CS1 a carton made IN_STOCK counted in at 3 of 4', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty,
                                         location_code, status)
    VALUES (gen_random_uuid(), 'QA62-LPN-S1', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa62:unit')::uuid, 4, 3,
            'HCM-A01-1-A', 'IN_STOCK') $q$);
SELECT pg_temp.expect_fail('CS2 a carton made RECEIVING counted in at 5 of 4', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty,
                                         location_code)
    VALUES (gen_random_uuid(), 'QA62-LPN-S2', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa62:unit')::uuid, 4, 5,
            'HCM-RCV01') $q$);
SELECT pg_temp.expect_ok('CS3 a carton made RECEIVING not yet counted, then counted at its standard', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (md5('qa62:s3')::uuid, 'QA62-LPN-S3', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa62:unit')::uuid, 4,
            'HCM-RCV01');
    UPDATE inventory.stock_package SET received_qty = 4 WHERE id = md5('qa62:s3')::uuid $q$);
SELECT pg_temp.expect_fail('CS4 counted again at another figure', $q$
    UPDATE inventory.stock_package SET received_qty = 3 WHERE id = md5('qa62:s3')::uuid $q$);
SELECT pg_temp.expect_fail('CS5 an IN_STOCK carton counted again', $q$
    UPDATE inventory.stock_package SET received_qty = 3 WHERE id = md5('qa62:a')::uuid $q$);
SELECT pg_temp.expect_fail('CS6 an IN_STOCK carton holding 3 of its 4 (a carton in stock is whole)', $q$
    UPDATE inventory.stock_item SET on_hand = 3 WHERE id = md5('qa62:a:row')::uuid $q$);
SELECT pg_temp.expect_fail('CS7 an IN_STOCK carton holding 5 of its 4', $q$
    UPDATE inventory.stock_item SET on_hand = 5 WHERE id = md5('qa62:a:row')::uuid $q$);

\echo '--- the balance view: one row per carton, no unopened, no expiry'
SELECT pg_temp.expect_true('CV1 no column unopened in stock_package or its view',
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                   AND table_name IN ('stock_package', 'stock_package_balance') AND column_name = 'unopened'));
SELECT pg_temp.expect_true('CV2 the view carries its stock row and the row''s status',
    (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'inventory'
        AND table_name = 'stock_package_balance' AND column_name IN ('stock_item_id', 'stock_status')) = 2);
SELECT pg_temp.expect_true('CV3 one row per carton, whatever its status',
    (SELECT count(*) = count(DISTINCT stock_package_id) FROM inventory.stock_package_balance)
    AND (SELECT count(*) FROM inventory.stock_package_balance) = (SELECT count(*) FROM inventory.stock_package));
SELECT pg_temp.expect_true('CV4 a carton read through the view: LPN, lot, row status, on hand, available',
    (SELECT lpn || ':' || lot_number || ':' || stock_status || ':' || on_hand || ':' || available || ':' || status
       FROM inventory.stock_package_balance WHERE stock_package_id = md5('qa62:a')::uuid)
    = 'QA62-LPN-A:UNTRACED:AVAILABLE:4:4:IN_STOCK');
SELECT pg_temp.expect_true('CV5 a carton with no row yet (RECEIVING) is read with nothing on hand',
    (SELECT on_hand = 0 AND stock_item_id IS NULL FROM inventory.stock_package_balance
      WHERE stock_package_id = md5('qa62:s3')::uuid));

\echo '--- a count line is a carton, whole (20 §4.2 b4, BR-14)'
SELECT pg_temp.expect_fail('CC1 a loose count line', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty)
    VALUES (gen_random_uuid(), md5('qa62:count')::uuid, 'HCM-A01-1-A', 'SOFA-3S-GREY', 4) $q$);
SELECT pg_temp.expect_fail('CC2 a carton line booked at 3 of 4', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa62:count')::uuid, 'HCM-A01-1-A', 'SOFA-3S-GREY', 3, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail('CC3 a carton line counted at 3 of 4', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty, counted_qty,
                                            counted_by, counted_at, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa62:count')::uuid, 'HCM-A01-1-A', 'SOFA-3S-GREY', 4, 3,
            md5('demo:user:editor')::uuid, NOW(), md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail('CC4 a carton line booked above its standard count', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa62:count')::uuid, 'HCM-A01-1-A', 'SOFA-3S-GREY', 8, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_ok('CC5 a carton line booked whole, not yet counted', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty, stock_package_id)
    VALUES (md5('qa62:ccl:a')::uuid, md5('qa62:count')::uuid, 'HCM-A01-1-A', 'SOFA-3S-GREY', 4, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_ok('CC6 a carton line booked not at all (a LOST carton scanned), found whole', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty, counted_qty,
                                            counted_by, counted_at, stock_package_id)
    VALUES (md5('qa62:ccl:b')::uuid, md5('qa62:count')::uuid, 'HCM-A01-1-A', 'SOFA-3S-GREY', 0, 4,
            md5('demo:user:editor')::uuid, NOW(), md5('qa62:b')::uuid) $q$);
SELECT pg_temp.expect_ok('CC7 the carton counted not found', $q$
    UPDATE inventory.cycle_count_line SET counted_qty = 0, counted_by = md5('demo:user:editor')::uuid,
           counted_at = NOW() WHERE id = md5('qa62:ccl:a')::uuid $q$);
SELECT pg_temp.expect_fail('CC8 a count line counted afterwards at part of a carton', $q$
    UPDATE inventory.cycle_count_line SET counted_qty = 2 WHERE id = md5('qa62:ccl:a')::uuid $q$);
SELECT pg_temp.expect_fail('CC9 a count line that stops naming a carton', $q$
    UPDATE inventory.cycle_count_line SET stock_package_id = NULL WHERE id = md5('qa62:ccl:a')::uuid $q$);
SELECT pg_temp.expect_ok('CC10 a figure-neutral change of a line (recount flag) is not judged again', $q$
    UPDATE inventory.cycle_count_line SET recount_required = TRUE WHERE id = md5('qa62:ccl:a')::uuid $q$);
SELECT pg_temp.expect_fail('CC11 the same carton twice in one count', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa62:count')::uuid, 'HCM-A01-1-A', 'SOFA-3S-GREY', 4, md5('qa62:a')::uuid) $q$);

\echo '--- an adjustment of a carton moves it whole, for a reason of BR-04 (20 §4.3)'
SELECT pg_temp.expect_fail('CA1 a write-off of 3 of a carton of 4', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-1', 'HCM-A01-1-A', 'SOFA-3S-GREY', -3, 'DAMAGED',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail('CA2 a restock of 5 of a carton of 4', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-2', 'HCM-A01-1-A', 'SOFA-3S-GREY', 5, 'FOUND',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail('CA3 an adjustment of a carton for EXPIRED (a reason of the history)', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-3', 'HCM-A01-1-A', 'SOFA-3S-GREY', -4, 'EXPIRED',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail('CA4 an adjustment of a carton for SCRAP', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-4', 'HCM-A01-1-A', 'SOFA-3S-GREY', -4, 'SCRAP',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail('CA5 an adjustment of a carton for SAMPLE', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-5', 'HCM-A01-1-A', 'SOFA-3S-GREY', -4, 'SAMPLE',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail('CA6 an adjustment of a carton for SUBCONTRACT_LOSS', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-6', 'HCM-A01-1-A', 'SOFA-3S-GREY', -4, 'SUBCONTRACT_LOSS',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail('CA7 a count variance of no carton', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            cycle_count_line_id, requested_by)
    VALUES (gen_random_uuid(), 'ADJ-QA62-7', 'HCM-A01-1-A', 'SOFA-3S-GREY', -4, 'COUNT_VARIANCE',
            md5('qa62:ccl:a')::uuid, md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail('CA8 a count variance of a whole carton with no count line', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-8', 'HCM-A01-1-A', 'SOFA-3S-GREY', -4, 'COUNT_VARIANCE',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_ok('CA9 each of the six reasons of BR-04 on a whole carton, one pending at a time', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id, note)
    VALUES (md5('qa62:adj:damaged')::uuid, 'ADJ-QA62-D', 'HCM-A01-1-B', 'SOFA-3S-GREY', -4, 'DAMAGED',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid, NULL);
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa62:adj:damaged')::uuid;
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (md5('qa62:adj:lost')::uuid, 'ADJ-QA62-L', 'HCM-A01-1-B', 'SOFA-3S-GREY', -4, 'LOST',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid);
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa62:adj:lost')::uuid;
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (md5('qa62:adj:found')::uuid, 'ADJ-QA62-F', 'HCM-A01-1-B', 'SOFA-3S-GREY', 4, 'FOUND',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid);
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa62:adj:found')::uuid;
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (md5('qa62:adj:fix')::uuid, 'ADJ-QA62-C', 'HCM-A01-1-B', 'SOFA-3S-GREY', -4, 'DATA_CORRECTION',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid);
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa62:adj:fix')::uuid;
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id, note)
    VALUES (md5('qa62:adj:other')::uuid, 'ADJ-QA62-O', 'HCM-A01-1-B', 'SOFA-3S-GREY', -4, 'OTHER',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid, 'Handed to the supplier');
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa62:adj:other')::uuid;
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            cycle_count_line_id, requested_by, stock_package_id)
    VALUES (md5('qa62:adj:count')::uuid, 'ADJ-QA62-V', 'HCM-A01-1-A', 'SOFA-3S-GREY', -4, 'COUNT_VARIANCE',
            md5('qa62:ccl:a')::uuid, md5('demo:user:editor')::uuid, md5('qa62:a')::uuid);
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa62:adj:count')::uuid $q$);
SELECT pg_temp.expect_ok('CA10 a loose adjustment keeps any reason of the history (the returns flow, damage reports)', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            lot_number, requested_by)
    VALUES (md5('qa62:adj:loose')::uuid, 'ADJ-QA62-X', 'HCM-A02-1-B', 'SOFA-3S-GREY', -1, 'SCRAP', 'UNTRACED',
            md5('demo:user:editor')::uuid) $q$);

\echo '--- one adjustment of a carton waits for approval at a time (BR-05)'
SELECT pg_temp.expect_ok('CP1 a first write-off asked of a carton', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (md5('qa62:p1')::uuid, 'ADJ-QA62-P1', 'HCM-A01-1-A', 'SOFA-3S-GREY', -4, 'DAMAGED',
            md5('demo:user:editor')::uuid, md5('qa62:b')::uuid) $q$);
SELECT pg_temp.expect_fail('CP2 a second one while the first waits', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-P2', 'HCM-A01-1-A', 'SOFA-3S-GREY', -4, 'LOST',
            md5('demo:user:editor')::uuid, md5('qa62:b')::uuid) $q$);
SELECT pg_temp.expect_fail('CP3 a second one of the other direction while the first waits', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-P3', 'HCM-A01-1-A', 'SOFA-3S-GREY', 4, 'FOUND',
            md5('demo:user:editor')::uuid, md5('qa62:b')::uuid) $q$);
SELECT pg_temp.expect_ok('CP4 another carton is not blocked by it', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-P4', 'HCM-A01-1-B', 'SOFA-3S-GREY', -4, 'DAMAGED',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid) $q$);
SELECT pg_temp.expect_ok('CP5 once rejected, the carton can be asked about again', $q$
    UPDATE inventory.stock_adjustment SET status = 'REJECTED', decided_by = md5('demo:user:approver')::uuid,
           decided_at = NOW(), rejection_reason = 'Not damaged' WHERE id = md5('qa62:p1')::uuid;
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-P5', 'HCM-A01-1-A', 'SOFA-3S-GREY', -4, 'LOST',
            md5('demo:user:editor')::uuid, md5('qa62:b')::uuid) $q$);
SELECT pg_temp.expect_fail('CP6 ... and not twice again', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-P6', 'HCM-A01-1-A', 'SOFA-3S-GREY', -4, 'DAMAGED',
            md5('demo:user:editor')::uuid, md5('qa62:b')::uuid) $q$);
SELECT pg_temp.expect_true('CP7 the partial index is on pending adjustments of a carton only',
    EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'inventory' AND indexname = 'uk_stock_adjustment_pending_package'
               AND indexdef LIKE '%UNIQUE%(stock_package_id)%PENDING_APPROVAL%'));

\echo '--- the lifecycle a count''s shortage and a found carton walk (20 §4.6 step 7)'
SELECT pg_temp.expect_ok('CF1 a shortage approved: the carton written off whole and LOST', $q$
    UPDATE inventory.stock_item SET on_hand = 0 WHERE id = md5('qa62:c:row')::uuid;
    UPDATE inventory.stock_package SET status = 'LOST', closed_at = NOW() WHERE id = md5('qa62:c')::uuid $q$);
SELECT pg_temp.expect_ok('CF2 found again whole: IN_STOCK on the row it was emptied from', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL WHERE id = md5('qa62:c')::uuid;
    UPDATE inventory.stock_item SET on_hand = 4 WHERE id = md5('qa62:c:row')::uuid $q$);
SELECT pg_temp.expect_fail('CF3 found again with its row still empty', $q$
    UPDATE inventory.stock_item SET on_hand = 0 WHERE id = md5('qa62:c:row')::uuid;
    UPDATE inventory.stock_package SET status = 'LOST', closed_at = NOW() WHERE id = md5('qa62:c')::uuid;
    UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL WHERE id = md5('qa62:c')::uuid $q$);
SELECT pg_temp.expect_fail('CF4 a LOST carton moved to CONSUMED', $q$
    UPDATE inventory.stock_item SET on_hand = 0 WHERE id = md5('qa62:c:row')::uuid;
    UPDATE inventory.stock_package SET status = 'LOST', closed_at = NOW() WHERE id = md5('qa62:c')::uuid;
    UPDATE inventory.stock_package SET status = 'CONSUMED' WHERE id = md5('qa62:c')::uuid $q$);

\echo '--- a count holds at most 500 cartons (20 BR-12), found ones included'
INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status, started_at)
VALUES (md5('qa62:big')::uuid, 'CC-QA-62B', md5('demo:wh:HCM')::uuid, 'MANUAL', 'IN_PROGRESS', NOW());
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty,
                                     location_code, status)
SELECT md5('qa62:m:' || n)::uuid, 'QA62-M-' || n, md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa62:unit')::uuid, 4, 4,
       'HCM-A02-1-B', 'IN_STOCK'
  FROM generate_series(1, 501) n;
INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                  received_at, stock_package_id)
SELECT md5('qa62:m:row:' || n)::uuid, 'SOFA-3S-GREY', 'HCM-A02-1-B', 'UNTRACED', 4, 0, 'AVAILABLE', NOW(),
       NOW() - interval '1 day', md5('qa62:m:' || n)::uuid
  FROM generate_series(1, 501) n;
SELECT pg_temp.expect_ok('CX1 500 carton lines on one count', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty, stock_package_id)
    SELECT gen_random_uuid(), md5('qa62:big')::uuid, 'HCM-A02-1-B', 'SOFA-3S-GREY', 0, md5('qa62:m:' || n)::uuid
      FROM generate_series(1, 500) n $q$);
SELECT pg_temp.expect_fail('CX2 a 501st line, planned or found', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa62:big')::uuid, 'HCM-A02-1-B', 'SOFA-3S-GREY', 0, md5('qa62:m:501')::uuid) $q$);
SELECT pg_temp.expect_true('CX3 the count holds 500',
    (SELECT count(*) = 500 FROM inventory.cycle_count_line WHERE cycle_count_id = md5('qa62:big')::uuid));
SELECT pg_temp.expect_ok('CX4 another count is not full', $q$
    INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status, started_at)
    VALUES (md5('qa62:small')::uuid, 'CC-QA-62C', md5('demo:wh:HCM')::uuid, 'MANUAL', 'IN_PROGRESS', NOW());
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, expected_qty, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa62:small')::uuid, 'HCM-A02-1-B', 'SOFA-3S-GREY', 0, md5('qa62:m:501')::uuid) $q$);

\echo '--- what the migration kept'
SELECT pg_temp.expect_true('CM1 the unopened flags were archived whole before the column went',
    EXISTS (SELECT 1 FROM platform.legacy_archive WHERE source_table = 'inventory.stock_package.unopened'
               AND row_data ? 'unopened' AND row_data ? 'lpn'));
SELECT pg_temp.expect_true('CM2 the new triggers and checks are in place',
    (SELECT count(*) FROM pg_trigger WHERE tgname IN ('tg_cycle_count_line_carton', 'tg_stock_adjustment_whole_carton')
        AND NOT tgisinternal) = 2
    AND EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_stock_package_standard_count')
    AND EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_stock_item_lot'));

ROLLBACK;
\echo 'PASS 62_carton_counts'
