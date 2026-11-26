-- =============================================================================
-- Adversarial checks + happy paths: counts and adjustments by carton, a lot on every stock row, one row per carton
-- (V20261113000300, team plan 4C-3; kltn-docs 20 §4.2, §4.3, BR-04, BR-09, BR-10, BR-11, BR-14, BR-18, BR-22). Since
-- V20261230000100 a count line says booked / found and an adjustment a direction, never units, and both name their
-- carton: what used to be refused as "part of a carton" cannot be written at all. Needs the demo seed. Rolled back at
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

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

-- Fixtures: a carton unit of 4 cups; three cartons in stock (two on a bin, one on another), in one lot;
-- a MANUAL count of the bin with a line for the first carton; the editor and the approver of the demo.
-- 01 BR-03: an item has ONE active carton. The demo seed's own is retired, so the fixture's unit below is the one.
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND inventory_item_id IN (SELECT id FROM inventory.inventory_items WHERE sku IN ('LY-GIAY-DEMO-12OZ-XANH'));
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa62:unit')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'QA62_CTN', 4);
INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at)
VALUES (md5('qa62:lot')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', NOW() - interval '1 day', 'UNTRACED', NOW());
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at,
                                     location_code, status, stock_status, lot_id, received_at)
VALUES (md5('qa62:a')::uuid, 'QA62-LPN-A', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa62:unit')::uuid, 4, NOW(),
        'HCM-A01-1-A', 'IN_STOCK', 'AVAILABLE', md5('qa62:lot')::uuid, NOW() - interval '1 day'),
       (md5('qa62:b')::uuid, 'QA62-LPN-B', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa62:unit')::uuid, 4, NOW(),
        'HCM-A01-1-A', 'IN_STOCK', 'AVAILABLE', md5('qa62:lot')::uuid, NOW() - interval '1 day'),
       (md5('qa62:c')::uuid, 'QA62-LPN-C', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa62:unit')::uuid, 4, NOW(),
        'HCM-A01-1-B', 'IN_STOCK', 'AVAILABLE', md5('qa62:lot')::uuid, NOW() - interval '1 day');
INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status, started_at)
VALUES (md5('qa62:count')::uuid, 'CC-QA-62', md5('demo:wh:HCM')::uuid, 'MANUAL', 'IN_PROGRESS', NOW());

\echo '--- a lot on every carton that has been in stock (20 BR-22)'
-- CL1, CL2 and CL4 to CL7 were about the stock row's own lot_number and lot_id (NOT NULL, not blank, never loose): the
-- carton names its lot by id and has no lot number of its own (V20261230000600).
SELECT pg_temp.expect_true('CL1 a carton names its lot: stock_package.lot_id, required once it has been in stock',
    EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_stock_package_lot'
               AND conrelid = 'inventory.stock_package'::regclass AND convalidated)
    AND NOT EXISTS (SELECT 1 FROM inventory.stock_package WHERE lot_id IS NULL AND status NOT IN ('RECEIVING', 'VOID')));
SELECT pg_temp.expect_fail_by('CL3 a carton in stock with no lot (the cup is not lot-tracked: still refused)', 'ck_stock_package_lot', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at,
                                         location_code, status, stock_status)
    VALUES (gen_random_uuid(), 'QA62-LPN-NL', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa62:unit')::uuid, 4, NOW(), 'HCM-A02-1-B', 'IN_STOCK', 'AVAILABLE') $q$);
SELECT pg_temp.expect_fail_by('CL5 a carton emptied of its lot', 'never changed (FIFO)', $q$
    UPDATE inventory.stock_package SET lot_id = NULL WHERE id = md5('qa62:a')::uuid $q$);

\echo '--- a carton is the stock: one row, no stock row beside it (20 BR-18)'
-- CR1 to CR5 were about uk_stock_item_package (one stock row per carton) and the loose rows left beside it.
SELECT pg_temp.expect_true('CR1 the stock rows and their indexes are gone',
    to_regclass('inventory.stock_item') IS NULL
    AND NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'inventory'
                     AND indexname IN ('uk_stock_item_package', 'uk_stock_item_package_layer')));

\echo '--- a carton is counted in, and is in stock, at its standard count only (20 BR-18; counted_at since V20261230000300)'
SELECT pg_temp.expect_fail('CS1 a carton made IN_STOCK that was never counted in', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                                         location_code, status)
    VALUES (gen_random_uuid(), 'QA62-LPN-S1', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa62:unit')::uuid, 4,
            'HCM-A01-1-A', 'IN_STOCK') $q$);
SELECT pg_temp.expect_true('CS2 a carton cannot be counted in at 5 of 4: there is no figure to write (received_qty is gone)',
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                   AND table_name = 'stock_package' AND column_name = 'received_qty'));
SELECT pg_temp.expect_ok('CS3 a carton made RECEIVING not yet counted, then counted in', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (md5('qa62:s3')::uuid, 'QA62-LPN-S3', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa62:unit')::uuid, 4,
            'HCM-RCV01');
    UPDATE inventory.stock_package SET counted_at = NOW() WHERE id = md5('qa62:s3')::uuid $q$);
SELECT pg_temp.expect_ok('CS4 counted again while still RECEIVING: a recount of the draft moves the moment, nothing else', $q$
    UPDATE inventory.stock_package SET counted_at = counted_at + interval '1 minute' WHERE id = md5('qa62:s3')::uuid $q$);
SELECT pg_temp.expect_fail('CS5 an IN_STOCK carton counted again', $q$
    UPDATE inventory.stock_package SET counted_at = counted_at + interval '1 minute' WHERE id = md5('qa62:a')::uuid $q$);
-- CS6 and CS7 (an IN_STOCK carton holding 3, or 5, of its 4) cannot be written: a carton holds no quantity.

\echo '--- the balance view went with the stock rows; the carton answers for itself (V20261230000600)'
SELECT pg_temp.expect_true('CV1 no column unopened in stock_package, and no balance view',
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                   AND table_name = 'stock_package' AND column_name = 'unopened')
    AND to_regclass('inventory.stock_package_balance') IS NULL);
SELECT pg_temp.expect_true('CV2 the carton carries its stock status, its lot and its receipt date',
    (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'inventory'
        AND table_name = 'stock_package' AND column_name IN ('stock_status', 'lot_id', 'received_at')) = 3);
SELECT pg_temp.expect_true('CV4 a carton read: LPN, lot, stock status, status',
    (SELECT p.lpn || ':' || l.lot_number || ':' || p.stock_status || ':' || p.nominal_qty || ':' || p.status
       FROM inventory.stock_package p JOIN inventory.stock_lot l ON l.id = p.lot_id WHERE p.id = md5('qa62:a')::uuid)
    = 'QA62-LPN-A:UNTRACED:AVAILABLE:4:IN_STOCK');
SELECT pg_temp.expect_true('CV5 a carton still RECEIVING has no stock status and no lot',
    (SELECT stock_status IS NULL AND lot_id IS NULL AND received_at IS NULL FROM inventory.stock_package
      WHERE id = md5('qa62:s3')::uuid));

\echo '--- a count line names a carton and says booked or not, found or not: no units (20 §4.2 b4, BR-14; V20261230000100)'
SELECT pg_temp.expect_true('CC0 the unit figures are gone: booked is a yes/no that is always said, found one that waits for the count',
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                   AND table_name = 'cycle_count_line' AND column_name IN ('expected_qty', 'counted_qty'))
    AND (SELECT string_agg(column_name || ':' || data_type || ':' || is_nullable, ' ' ORDER BY column_name)
           FROM information_schema.columns WHERE table_schema = 'inventory' AND table_name = 'cycle_count_line'
            AND column_name IN ('booked', 'found', 'stock_package_id'))
        = 'booked:boolean:NO found:boolean:YES stock_package_id:uuid:NO');
SELECT pg_temp.expect_fail_by('CC1 a loose count line: a line names its carton', '"stock_package_id"', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked)
    VALUES (gen_random_uuid(), md5('qa62:count')::uuid, 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', TRUE) $q$);
SELECT pg_temp.expect_fail_by('CC2 a carton line that does not say whether the books had the carton', '"booked"', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa62:count')::uuid, 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', NULL, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CC3 a carton line counted at 3 of 4: found takes an answer, not a number of units', 'boolean', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, found,
                                            counted_by, counted_at, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa62:count')::uuid, 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', TRUE, 3,
            md5('demo:user:editor')::uuid, NOW(), md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CC4 a carton line found, with nobody and no time it was counted', 'ck_cycle_count_line_counted', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, found, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa62:count')::uuid, 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', TRUE, TRUE, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CC4b a carton line with a counter and no answer', 'ck_cycle_count_line_counted', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, counted_by, counted_at,
                                            stock_package_id)
    VALUES (gen_random_uuid(), md5('qa62:count')::uuid, 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', TRUE,
            md5('demo:user:editor')::uuid, NOW(), md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_ok('CC5 a carton line booked, not yet counted', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, stock_package_id)
    VALUES (md5('qa62:ccl:a')::uuid, md5('qa62:count')::uuid, 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', TRUE, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_true('CC5b ... and until it is counted, found says nothing',
    (SELECT found IS NULL AND counted_by IS NULL AND counted_at IS NULL FROM inventory.cycle_count_line
      WHERE id = md5('qa62:ccl:a')::uuid));
SELECT pg_temp.expect_ok('CC6 a carton line not booked (a LOST carton scanned), found', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, found,
                                            counted_by, counted_at, stock_package_id)
    VALUES (md5('qa62:ccl:b')::uuid, md5('qa62:count')::uuid, 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', FALSE, TRUE,
            md5('demo:user:editor')::uuid, NOW(), md5('qa62:b')::uuid) $q$);
SELECT pg_temp.expect_ok('CC7 the carton counted not found', $q$
    UPDATE inventory.cycle_count_line SET found = FALSE, counted_by = md5('demo:user:editor')::uuid,
           counted_at = NOW() WHERE id = md5('qa62:ccl:a')::uuid $q$);
SELECT pg_temp.expect_fail_by('CC8 the answer of a counted line taken back, its counter kept', 'ck_cycle_count_line_counted', $q$
    UPDATE inventory.cycle_count_line SET found = NULL WHERE id = md5('qa62:ccl:a')::uuid $q$);
SELECT pg_temp.expect_fail_by('CC8b the time of a counted line taken back', 'ck_cycle_count_line_counted', $q$
    UPDATE inventory.cycle_count_line SET counted_at = NULL WHERE id = md5('qa62:ccl:a')::uuid $q$);
SELECT pg_temp.expect_fail_by('CC8c a counted line that stops saying whether the books had the carton', '"booked"', $q$
    UPDATE inventory.cycle_count_line SET booked = NULL WHERE id = md5('qa62:ccl:a')::uuid $q$);
SELECT pg_temp.expect_fail_by('CC9 a count line that stops naming a carton', '"stock_package_id"', $q$
    UPDATE inventory.cycle_count_line SET stock_package_id = NULL WHERE id = md5('qa62:ccl:a')::uuid $q$);
SELECT pg_temp.expect_ok('CC10 a counted line sent for recount, and counted again the other way', $q$
    UPDATE inventory.cycle_count_line SET recount_required = TRUE WHERE id = md5('qa62:ccl:a')::uuid;
    UPDATE inventory.cycle_count_line SET found = TRUE WHERE id = md5('qa62:ccl:a')::uuid;
    UPDATE inventory.cycle_count_line SET found = FALSE WHERE id = md5('qa62:ccl:a')::uuid $q$);
SELECT pg_temp.expect_fail_by('CC11 the same carton twice in one count', 'uk_cycle_count_line_package', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa62:count')::uuid, 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', TRUE, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CC12 a count line naming a carton at another bin', 'names a carton at', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa62:count')::uuid, 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', TRUE, md5('qa62:c')::uuid) $q$);
SELECT pg_temp.expect_true('CC13 the index that told loose lines apart is gone; one line per carton stays',
    NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'inventory' AND indexname = 'uk_cycle_count_line_slot')
    AND EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'inventory' AND indexname = 'uk_cycle_count_line_package'));

\echo '--- an adjustment names a carton and says which way it goes, whole, for a reason of BR-04 (20 §4.3; V20261230000100)'
SELECT pg_temp.expect_true('CA0 the unit delta is gone: a direction that is always said, of a carton that is always named',
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                   AND table_name = 'stock_adjustment' AND column_name = 'quantity_delta')
    AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_stock_adjustment_delta')
    AND (SELECT string_agg(column_name || ':' || is_nullable, ' ' ORDER BY column_name)
           FROM information_schema.columns WHERE table_schema = 'inventory' AND table_name = 'stock_adjustment'
            AND column_name IN ('direction', 'stock_package_id')) = 'direction:NO stock_package_id:NO');
SELECT pg_temp.expect_fail_by('CA1 an adjustment that does not say which way', '"direction"', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-1', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', NULL, 'DAMAGED',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CA2 a write-off of 3 of a carton of 4: a number of units is no direction', 'ck_stock_adjustment_direction', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-2', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', '-3', 'DAMAGED',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CA2b a direction that is neither WRITE_OFF nor RESTOCK', 'ck_stock_adjustment_direction', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-2B', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'write_off', 'FOUND',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CA3 an adjustment of a carton for EXPIRED (a reason of the history)', 'cannot be for EXPIRED stock', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-3', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'EXPIRED',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CA4 an adjustment of a carton for SCRAP', 'BR-04 lists six', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-4', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'SCRAP',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CA5 an adjustment of a carton for SAMPLE', 'BR-04 lists six', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-5', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'SAMPLE',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CA6 an adjustment of a carton for SUBCONTRACT_LOSS', 'BR-04 lists six', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-6', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'SUBCONTRACT_LOSS',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CA7 a count variance of no carton', '"stock_package_id"', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            cycle_count_line_id, requested_by)
    VALUES (gen_random_uuid(), 'ADJ-QA62-7', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'COUNT_VARIANCE',
            md5('qa62:ccl:a')::uuid, md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CA7b an adjustment asked by hand of no carton: there is no loose stock to adjust', '"stock_package_id"', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            lot_number, requested_by)
    VALUES (gen_random_uuid(), 'ADJ-QA62-7B', 'HCM-A02-1-B', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'DAMAGED', 'UNTRACED',
            md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CA8 a count variance of a whole carton with no count line', 'ck_stock_adjustment_count_variance', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-8', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'COUNT_VARIANCE',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_ok('CA9 each of the six reasons of BR-04 on a whole carton, one pending at a time', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id, note)
    VALUES (md5('qa62:adj:damaged')::uuid, 'ADJ-QA62-D', 'HCM-A01-1-B', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'DAMAGED',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid, NULL);
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa62:adj:damaged')::uuid;
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (md5('qa62:adj:lost')::uuid, 'ADJ-QA62-L', 'HCM-A01-1-B', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'LOST',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid);
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa62:adj:lost')::uuid;
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (md5('qa62:adj:found')::uuid, 'ADJ-QA62-F', 'HCM-A01-1-B', 'LY-GIAY-DEMO-12OZ-XANH', 'RESTOCK', 'FOUND',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid);
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa62:adj:found')::uuid;
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (md5('qa62:adj:fix')::uuid, 'ADJ-QA62-C', 'HCM-A01-1-B', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'DATA_CORRECTION',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid);
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa62:adj:fix')::uuid;
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id, note)
    VALUES (md5('qa62:adj:other')::uuid, 'ADJ-QA62-O', 'HCM-A01-1-B', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'OTHER',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid, 'Handed to the supplier');
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa62:adj:other')::uuid;
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            cycle_count_line_id, requested_by, stock_package_id)
    VALUES (md5('qa62:adj:count')::uuid, 'ADJ-QA62-V', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'COUNT_VARIANCE',
            md5('qa62:ccl:a')::uuid, md5('demo:user:editor')::uuid, md5('qa62:a')::uuid);
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa62:adj:count')::uuid $q$);
-- SCRAP and SAMPLE are reasons of the history: tg_stock_adjustment_whole_carton refuses a new one (CA4, CA5), so what
-- the CHECKs say of them is asked with that trigger off, as a row written before it would be.
ALTER TABLE inventory.stock_adjustment DISABLE TRIGGER tg_stock_adjustment_whole_carton;
SELECT pg_temp.expect_fail_by('CA10 a SCRAP adjustment that restocks', 'ck_stock_adjustment_writes_down', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-10', 'HCM-A01-1-B', 'LY-GIAY-DEMO-12OZ-XANH', 'RESTOCK', 'SCRAP',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CA11 a SAMPLE adjustment that restocks', 'ck_stock_adjustment_writes_down', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-11', 'HCM-A01-1-B', 'LY-GIAY-DEMO-12OZ-XANH', 'RESTOCK', 'SAMPLE',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid) $q$);
SELECT pg_temp.expect_ok('CA12 a SCRAP and a SAMPLE adjustment that write off', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (md5('qa62:adj:scrap')::uuid, 'ADJ-QA62-12', 'HCM-A01-1-B', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'SCRAP',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid);
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa62:adj:scrap')::uuid;
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (md5('qa62:adj:sample')::uuid, 'ADJ-QA62-12B', 'HCM-A01-1-B', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'SAMPLE',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid);
    UPDATE inventory.stock_adjustment SET status = 'WITHDRAWN', withdrawn_at = NOW() WHERE id = md5('qa62:adj:sample')::uuid $q$);
SELECT pg_temp.expect_fail_by('CA13 a hold named by an adjustment that is not a SCRAP write-off: another reason', 'ck_stock_adjustment_reservation_scrap', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id, reservation_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-13', 'HCM-A01-1-B', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'DAMAGED',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid, gen_random_uuid()) $q$);
ALTER TABLE inventory.stock_adjustment ENABLE TRIGGER tg_stock_adjustment_whole_carton;

\echo '--- one adjustment of a carton waits for approval at a time (BR-05)'
SELECT pg_temp.expect_ok('CP1 a first write-off asked of a carton', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (md5('qa62:p1')::uuid, 'ADJ-QA62-P1', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'DAMAGED',
            md5('demo:user:editor')::uuid, md5('qa62:b')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CP2 a second one while the first waits', 'uk_stock_adjustment_pending_package', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-P2', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'LOST',
            md5('demo:user:editor')::uuid, md5('qa62:b')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CP3 a second one of the other direction while the first waits', 'uk_stock_adjustment_pending_package', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-P3', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'RESTOCK', 'FOUND',
            md5('demo:user:editor')::uuid, md5('qa62:b')::uuid) $q$);
SELECT pg_temp.expect_ok('CP4 another carton is not blocked by it', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-P4', 'HCM-A01-1-B', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'DAMAGED',
            md5('demo:user:editor')::uuid, md5('qa62:c')::uuid) $q$);
SELECT pg_temp.expect_ok('CP5 once rejected, the carton can be asked about again', $q$
    UPDATE inventory.stock_adjustment SET status = 'REJECTED', decided_by = md5('demo:user:approver')::uuid,
           decided_at = NOW(), rejection_reason = 'Not damaged' WHERE id = md5('qa62:p1')::uuid;
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-P5', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'LOST',
            md5('demo:user:editor')::uuid, md5('qa62:b')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CP6 ... and not twice again', 'uk_stock_adjustment_pending_package', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJ-QA62-P6', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'DAMAGED',
            md5('demo:user:editor')::uuid, md5('qa62:b')::uuid) $q$);
SELECT pg_temp.expect_true('CP7 the partial index is on pending adjustments of a carton only',
    EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'inventory' AND indexname = 'uk_stock_adjustment_pending_package'
               AND indexdef LIKE '%UNIQUE%(stock_package_id)%PENDING_APPROVAL%'));

\echo '--- the lifecycle a count''s shortage and a found carton walk (20 §4.6 step 7)'
SELECT pg_temp.expect_ok('CF1 a shortage approved: the carton written off whole and LOST', $q$
    UPDATE inventory.stock_package SET status = 'LOST', stock_status = NULL, closed_at = NOW() WHERE id = md5('qa62:c')::uuid $q$);
SELECT pg_temp.expect_ok('CF2 found again whole: IN_STOCK as AVAILABLE, the same lot and receipt date', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'AVAILABLE', closed_at = NULL
     WHERE id = md5('qa62:c')::uuid $q$);
SELECT pg_temp.expect_fail_by('CF3 found again without saying as what (it was: with its row still empty)', 'ck_stock_package_stock_status', $q$
    UPDATE inventory.stock_package SET status = 'LOST', stock_status = NULL, closed_at = NOW() WHERE id = md5('qa62:c')::uuid;
    UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL WHERE id = md5('qa62:c')::uuid $q$);
SELECT pg_temp.expect_fail_by('CF4 a LOST carton moved to CONSUMED', 'cannot go from LOST to CONSUMED', $q$
    UPDATE inventory.stock_package SET status = 'LOST', stock_status = NULL, closed_at = NOW() WHERE id = md5('qa62:c')::uuid;
    UPDATE inventory.stock_package SET status = 'CONSUMED' WHERE id = md5('qa62:c')::uuid $q$);

\echo '--- a count holds at most 500 cartons (20 BR-12), found ones included'
INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status, started_at)
VALUES (md5('qa62:big')::uuid, 'CC-QA-62B', md5('demo:wh:HCM')::uuid, 'MANUAL', 'IN_PROGRESS', NOW());
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at,
                                     location_code, status, stock_status, lot_id, received_at)
SELECT md5('qa62:m:' || n)::uuid, 'QA62-M-' || n, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa62:unit')::uuid, 4, NOW(),
       'HCM-A02-1-B', 'IN_STOCK', 'AVAILABLE', md5('qa62:lot')::uuid, NOW() - interval '1 day'
  FROM generate_series(1, 501) n;
SELECT pg_temp.expect_ok('CX1 500 carton lines on one count', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, stock_package_id)
    SELECT gen_random_uuid(), md5('qa62:big')::uuid, 'HCM-A02-1-B', 'LY-GIAY-DEMO-12OZ-XANH', FALSE, md5('qa62:m:' || n)::uuid
      FROM generate_series(1, 500) n $q$);
SELECT pg_temp.expect_fail_by('CX2 a 501st line, planned or found', 'already has 500 lines', $q$
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa62:big')::uuid, 'HCM-A02-1-B', 'LY-GIAY-DEMO-12OZ-XANH', FALSE, md5('qa62:m:501')::uuid) $q$);
SELECT pg_temp.expect_true('CX3 the count holds 500',
    (SELECT count(*) = 500 FROM inventory.cycle_count_line WHERE cycle_count_id = md5('qa62:big')::uuid));
SELECT pg_temp.expect_ok('CX4 another count is not full', $q$
    INSERT INTO inventory.cycle_count (id, count_number, warehouse_id, trigger_type, status, started_at)
    VALUES (md5('qa62:small')::uuid, 'CC-QA-62C', md5('demo:wh:HCM')::uuid, 'MANUAL', 'IN_PROGRESS', NOW());
    INSERT INTO inventory.cycle_count_line (id, cycle_count_id, location_code, sku, booked, stock_package_id)
    VALUES (gen_random_uuid(), md5('qa62:small')::uuid, 'HCM-A02-1-B', 'LY-GIAY-DEMO-12OZ-XANH', FALSE, md5('qa62:m:501')::uuid) $q$);

\echo '--- the counter of a count''s variance does not decide it, and the ledger names the people (V20261121000200)'
INSERT INTO identity.app_user (id, username, email, status, version, created_at)
VALUES (md5('qa62:counter')::uuid, 'qa62.counter', 'qa62.counter@example.com', 'ACTIVE', 0, NOW());
SELECT pg_temp.expect_fail_by('CD1 a counter on an adjustment nobody counted (asked by hand)', 'ck_stock_adjustment_counted_by_count', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            requested_by, stock_package_id, counted_by)
    VALUES (gen_random_uuid(), 'ADJ-QA62-C1', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'DAMAGED',
            md5('demo:user:editor')::uuid, md5('qa62:a')::uuid, md5('qa62:counter')::uuid) $q$);
SELECT pg_temp.expect_ok('CD2 a count''s variance keeps who counted its line', $q$
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, direction, reason_code,
                                            cycle_count_line_id, requested_by, stock_package_id, counted_by)
    VALUES (md5('qa62:adj:cv')::uuid, 'ADJ-QA62-C2', 'HCM-A01-1-A', 'LY-GIAY-DEMO-12OZ-XANH', 'WRITE_OFF', 'COUNT_VARIANCE',
            md5('qa62:ccl:a')::uuid, md5('demo:user:editor')::uuid, md5('qa62:a')::uuid, md5('qa62:counter')::uuid) $q$);
SELECT pg_temp.expect_fail_by('CD3 rejected by the person who counted the line', 'ck_stock_adjustment_counter_not_decider', $q$
    UPDATE inventory.stock_adjustment SET status = 'REJECTED', decided_by = md5('qa62:counter')::uuid,
           decided_at = NOW(), rejection_reason = 'Counted it myself' WHERE id = md5('qa62:adj:cv')::uuid $q$);
SELECT pg_temp.expect_fail_by('CD4 approved by the person who counted the line', 'ck_stock_adjustment_counter_not_decider', $q$
    UPDATE inventory.stock_adjustment SET status = 'POSTED', decided_by = md5('qa62:counter')::uuid,
           decided_at = NOW(), posted_at = NOW() WHERE id = md5('qa62:adj:cv')::uuid $q$);
SELECT pg_temp.expect_fail('CD5 a ledger line of that adjustment naming another requester', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, from_status,
                                          reference_type, reference_id, actor_id, occurred_at, stock_package_id,
                                          requested_by, counted_by)
    VALUES (gen_random_uuid(), 'ADJUSTMENT', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-1-A', 'AVAILABLE', 'STOCK_ADJUSTMENT',
            md5('qa62:adj:cv')::uuid, md5('demo:user:approver')::uuid, NOW(), md5('qa62:a')::uuid,
            md5('demo:user:approver')::uuid, md5('qa62:counter')::uuid) $q$);
SELECT pg_temp.expect_fail('CD6 a ledger line of that adjustment naming nobody', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, from_status,
                                          reference_type, reference_id, actor_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'ADJUSTMENT', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-1-A', 'AVAILABLE', 'STOCK_ADJUSTMENT',
            md5('qa62:adj:cv')::uuid, md5('demo:user:approver')::uuid, NOW(), md5('qa62:a')::uuid) $q$);
SELECT pg_temp.expect_ok('CD7 approved by a third person, its ledger line naming requester and counter', $q$
    UPDATE inventory.stock_adjustment SET status = 'POSTED', decided_by = md5('demo:user:approver')::uuid,
           decided_at = NOW(), posted_at = NOW() WHERE id = md5('qa62:adj:cv')::uuid;
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, from_status,
                                          reference_type, reference_id, actor_id, occurred_at, stock_package_id,
                                          requested_by, counted_by)
    VALUES (md5('qa62:mv:cv')::uuid, 'ADJUSTMENT', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-1-A', 'AVAILABLE', 'STOCK_ADJUSTMENT',
            md5('qa62:adj:cv')::uuid, md5('demo:user:approver')::uuid, NOW(), md5('qa62:a')::uuid,
            md5('demo:user:editor')::uuid, md5('qa62:counter')::uuid) $q$);
SELECT pg_temp.expect_fail('CD8 who asked, named on a line that posts no adjustment', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, from_status,
                                          reference_type, reference_id, actor_id, occurred_at, stock_package_id,
                                          requested_by)
    VALUES (gen_random_uuid(), 'PICK', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-1-A', 'AVAILABLE', 'PICK_LINE', gen_random_uuid(),
            md5('demo:user:approver')::uuid, NOW(), md5('qa62:a')::uuid, md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_true('CD9 the two CHECKs and the trigger are in place',
    (SELECT count(*) FROM pg_constraint WHERE conname IN ('ck_stock_adjustment_counter_not_decider',
        'ck_stock_adjustment_counted_by_count', 'ck_stock_movement_adjustment_people')) = 3
    AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'tg_stock_movement_adjustment_people' AND NOT tgisinternal));

\echo '--- what the migration kept'
SELECT pg_temp.expect_true('CM1 the 500-line cap is asked when a line is added, not when one is counted (V20261230000100)',
    (SELECT (tgtype & 4) <> 0 AND (tgtype & 16) = 0 FROM pg_trigger
      WHERE tgname = 'tg_cycle_count_line_carton' AND NOT tgisinternal));
SELECT pg_temp.expect_true('CM2 the new triggers and checks are in place',
    (SELECT count(*) FROM pg_trigger WHERE tgname IN ('tg_cycle_count_line_carton', 'tg_stock_adjustment_whole_carton')
        AND NOT tgisinternal) = 2
    AND EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_stock_package_counted')
    AND EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_stock_adjustment_direction')
    AND EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_stock_adjustment_writes_down'
                   AND pg_get_constraintdef(oid) LIKE '%direction%')
    AND EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_cycle_count_line_counted'
                   AND pg_get_constraintdef(oid) LIKE '%found IS NULL%')
    AND EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_stock_package_lot'));

ROLLBACK;
\echo 'PASS 62_carton_counts'
