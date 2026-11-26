-- =============================================================================
-- Adversarial checks + happy paths: every unit in stock is in a whole LPN carton (V20261111000110, team plan 4C-1;
-- kltn-docs 20 BR-14, BR-18, BR-22, §4.6, §5.5; open question C45). Needs the demo seed. The migration put the demo's
-- loose furniture into cartons of one; the furniture left the demo with V20261206000100, and the demo cups stand in
-- its place in whole cartons (same LPNs, bins, lots and statuses, each with its opening RECEIPT line instead of the
-- boxing MOVE lines), so the checks of "the cartons it made" are made on those. Since V20261230000600 (ADR-0011) the
-- carton is the stock itself: the checks of a carton against its stock rows are gone with the rows, and what is left
-- is asked of the carton. Rolled back at the end.
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

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

\echo '--- nothing loose (20 BR-14)'
SELECT pg_temp.expect_true('LC1 no unit in stock lies outside a carton: there is no stock row to be loose',
    to_regclass('inventory.stock_item') IS NULL);
SELECT pg_temp.expect_true('LC2 every carton in stock was counted in and has a stock status and a numbered lot (BR-18, BR-22)',
    NOT EXISTS (SELECT 1 FROM inventory.stock_package p LEFT JOIN inventory.stock_lot l ON l.id = p.lot_id
                 WHERE p.status = 'IN_STOCK'
                   AND (p.counted_at IS NULL OR p.stock_status IS NULL
                        OR NULLIF(btrim(l.lot_number), '') IS NULL)));
SELECT pg_temp.expect_true('LC3 the demo cup is 33 whole cartons of 1,000, of lot UNTRACED (C45), where the boxed units were',
    (SELECT string_agg(location_code || '=' || n, ',' ORDER BY location_code)
       FROM (SELECT p.location_code, count(*) AS n FROM inventory.stock_package p
               JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
               JOIN inventory.stock_lot l ON l.id = p.lot_id
              WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.nominal_qty = 1000
                AND l.lot_number = 'UNTRACED' AND p.status = 'IN_STOCK'
                AND p.created_by = 'V20261206000100' AND p.lpn ~ '^LPN-[0-9]{8}-[0-9]{6}$'
              GROUP BY p.location_code) c) = 'HCM-A01-2-B=25,HCM-A02-1-A=8');
SELECT pg_temp.expect_true('LC4 the red cups keep their lots and the quarantined ones stay quarantined',
    (SELECT string_agg(lot_number || '/' || stock_status || '=' || n, ',' ORDER BY lot_number)
       FROM (SELECT l.lot_number, p.stock_status, count(*) AS n FROM inventory.stock_package p
               JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
               JOIN inventory.stock_lot l ON l.id = p.lot_id
              WHERE i.sku = 'LY-GIAY-DEMO-12OZ-DO' AND p.status = 'IN_STOCK' GROUP BY l.lot_number, p.stock_status) c)
        = 'LOT-2026-03/AVAILABLE=12,LOT-2026-04/QUARANTINE=30');
-- LC5 (the four loose demo rows are empty) went with them: V20261230000600 deleted the empty loose rows.
SELECT pg_temp.expect_true('LC6 each of the 75 demo cartons has the one ledger line that put it into stock, in its lot',
    (SELECT count(*) = 75 FROM inventory.stock_package WHERE created_by = 'V20261206000100')
    AND NOT EXISTS (SELECT 1 FROM inventory.stock_package p
                     WHERE p.created_by = 'V20261206000100' AND p.status = 'IN_STOCK'
                       AND (SELECT count(*) FROM inventory.stock_movement m
                             WHERE m.movement_type = 'RECEIPT' AND m.reference_type = 'STOCK_PACKAGE'
                               AND m.reference_id = p.id AND m.stock_package_id = p.id
                               AND m.to_location_code = p.location_code
                               AND m.lot_id = p.lot_id) <> 1));
SELECT pg_temp.expect_true('LC7 the carton''s guards are on',
    (SELECT count(*) = 3 AND bool_and(tgenabled = 'O') FROM pg_trigger
      WHERE tgname IN ('tg_stock_package_transition', 'tg_stock_package_lot', 'tg_stock_package_held_available')));

\echo '--- the ledger: a MOVE stays in place only to box units into the carton it names'
SELECT pg_temp.expect_fail_by('LC8 a MOVE in place for a move task', 'ck_stock_movement_shape', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code,
                                          reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-2-B', 'MOVE_TASK',
            gen_random_uuid(), NOW(),
            (SELECT p.id FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
              WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.location_code = 'HCM-A01-2-B' ORDER BY p.lpn LIMIT 1)) $q$);
SELECT pg_temp.expect_fail_by('LC9 a MOVE in place naming a carton that is not its reference', 'ck_stock_movement_shape', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code,
                                          reference_type, reference_id, stock_package_id, occurred_at)
    SELECT gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-2-B',
           'STOCK_PACKAGE', gen_random_uuid(), p.id, NOW()
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.location_code = 'HCM-A01-2-B' LIMIT 1 $q$);
SELECT pg_temp.expect_fail('LC10 a MOVE in place whose reference is a carton, naming no carton', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code,
                                          reference_type, reference_id, occurred_at)
    SELECT gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-2-B',
           'STOCK_PACKAGE', p.id, NOW()
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.location_code = 'HCM-A01-2-B' LIMIT 1 $q$);
SELECT pg_temp.expect_ok('LC11 a MOVE in place into the carton it names', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code, reference_type, reference_id, stock_package_id, occurred_at)
    SELECT gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', 'HCM-A01-2-B', 'HCM-A01-2-B',
           'STOCK_PACKAGE', p.id, p.id, NOW()
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.location_code = 'HCM-A01-2-B' LIMIT 1 $q$);
-- Not a move by hand (QA 41 has those): it names a return line, and its carton as every line does (V20261230000200).
SELECT pg_temp.expect_ok('LC12 a MOVE from one place to another is what it was', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code,
                                          reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-1-A', 'RETURN_REQUEST_LINE',
            gen_random_uuid(), NOW(),
            (SELECT p.id FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
              WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.location_code = 'HCM-A01-2-B' ORDER BY p.lpn LIMIT 1)) $q$);
SELECT pg_temp.expect_fail_by('LC13 a STATUS_CHANGE that changes no status', 'ck_stock_movement_shape', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code,
                                          from_status, to_status, reference_type, reference_id, occurred_at,
                                          stock_package_id)
    VALUES (gen_random_uuid(), 'STATUS_CHANGE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-2-B', 'AVAILABLE',
            'AVAILABLE', 'STOCK_PACKAGE', gen_random_uuid(), NOW(),
            (SELECT p.id FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
              WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.location_code = 'HCM-A01-2-B' ORDER BY p.lpn LIMIT 1)) $q$);

\echo '--- every unit has a lot (20 BR-22): a carton in stock names one'
-- 01 BR-03: an item has ONE active carton. The demo seed's own is retired, so the fixture's unit below is the one.
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND inventory_item_id IN (SELECT id FROM inventory.inventory_items WHERE sku IN ('LY-GIAY-DEMO-12OZ-XANH', 'LY-GIAY-DEMO-12OZ-DO'));
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa40:u')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'QA_QA40', 2);
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
SELECT md5('qa40:uo')::uuid, i.id, 'QA_QA40O', 2 FROM inventory.inventory_items i WHERE i.sku = 'LY-GIAY-DEMO-12OZ-DO';
INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at)
VALUES (md5('qa40:lot')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 'LOT-QA40', NOW(), 'UNTRACED', NOW());
SELECT pg_temp.expect_ok('LC14 a carton of the demo cup in stock, in its lot', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at, location_code, status,
                                         stock_status, lot_id, received_at)
    VALUES (md5('qa40:p')::uuid, 'QA-QA40-1', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa40:u')::uuid, 2, NOW(), 'HCM-A01-1-A', 'IN_STOCK',
            'AVAILABLE', md5('qa40:lot')::uuid, NOW()) $q$);
-- LC15 (units never added to a layer in place) is gone with the quantity: a carton cannot grow (QA 70).
SELECT pg_temp.expect_fail_by('LC16 stock of any SKU (the red cup) with no lot: every SKU is lot-tracked since 5-5 (QA 70)', 'ck_stock_package_lot', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at, location_code, status,
                                         stock_status, received_at)
    SELECT md5('qa40:po')::uuid, 'QA-QA40-DO', i.id, md5('qa40:uo')::uuid, 2, NOW(), 'HCM-A01-1-A', 'IN_STOCK', 'AVAILABLE', NOW()
      FROM inventory.inventory_items i WHERE i.sku = 'LY-GIAY-DEMO-12OZ-DO' $q$);

\echo '--- the cartons it made are cartons like any other'
SELECT pg_temp.expect_true('LC17 a carton it made is never opened: there is no flag to open it with (V20261113000300)',
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                   AND table_name = 'stock_package' AND column_name = 'unopened'));
-- LC18 (a demo carton holding more than its standard count) cannot be written: there is no quantity.
SELECT pg_temp.expect_fail_by('LC19 a demo carton with no stock status but still in stock (it was: emptied but still in stock)', 'ck_stock_package_stock_status', $q$
    UPDATE inventory.stock_package SET stock_status = NULL
     WHERE id = (SELECT id FROM inventory.stock_package WHERE created_by = 'V20261206000100' AND status = 'IN_STOCK'
                  ORDER BY lpn LIMIT 1) $q$);
SELECT pg_temp.expect_fail('LC20 a second LPN the same as one of the demo cartons', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    SELECT gen_random_uuid(), p.lpn, p.inventory_item_id, p.uom_conversion_id, p.nominal_qty, p.location_code
      FROM inventory.stock_package p WHERE p.created_by = 'V20261206000100' ORDER BY p.lpn LIMIT 1 $q$);
SELECT pg_temp.expect_true('LC21 the LPN sequence of each day it numbered on is past every LPN of the demo cartons',
    NOT EXISTS (SELECT 1 FROM inventory.stock_package p
                 WHERE p.created_by = 'V20261206000100'
                   AND substring(p.lpn FROM 14)::bigint > COALESCE(
                       (SELECT q.last_value FROM platform.document_sequence q
                         WHERE q.document_type = 'LPN'
                           AND q.sequence_date = to_date(substring(p.lpn FROM 5 FOR 8), 'YYYYMMDD')), 0)));

\echo '--- an order holds whole cartons (07 BR-03, 20 §5.6)'
SELECT pg_temp.expect_true('LC22 every live hold on a carton is of an AVAILABLE carton of its count, held by nothing else',
    NOT EXISTS (SELECT 1 FROM inventory.stock_reservation h JOIN inventory.stock_package p ON p.id = h.stock_package_id
                 WHERE h.status = 'HELD'
                   AND (p.stock_status IS DISTINCT FROM 'AVAILABLE' OR h.cartons <> 1
                        OR h.units_per_carton <> p.nominal_qty
                        OR EXISTS (SELECT 1 FROM inventory.stock_reservation o
                                    WHERE o.stock_package_id = p.id AND o.status = 'HELD' AND o.id <> h.id))));

ROLLBACK;
\echo 'PASS 40_loose_stock_to_cartons'
