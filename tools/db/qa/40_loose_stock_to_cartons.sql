-- =============================================================================
-- Adversarial checks + happy paths: every unit in stock is in a whole LPN carton (V20261111000110, team plan 4C-1;
-- kltn-docs 20 BR-14, BR-18, BR-22, §4.6, §5.5; open question C45). Needs the demo seed. The migration put the demo's
-- loose furniture into cartons of one; the furniture left the demo with V20261206000100, and the demo cups stand in
-- its place in whole cartons (same LPNs, bins, lots and statuses, each with its opening RECEIPT line instead of the
-- boxing MOVE lines), so the checks of "the cartons it made" are made on those. Rolled back at the end.
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
SELECT pg_temp.expect_true('LC1 no unit in stock lies outside a carton',
    NOT EXISTS (SELECT 1 FROM inventory.stock_item WHERE on_hand > 0 AND stock_package_id IS NULL));
SELECT pg_temp.expect_true('LC2 every carton in stock is one whole carton of its standard count, of one lot (BR-18)',
    NOT EXISTS (SELECT 1 FROM inventory.stock_package p
                 WHERE p.status = 'IN_STOCK'
                   AND (p.received_qty <> p.nominal_qty
                        OR (SELECT count(*) FROM inventory.stock_item s
                             WHERE s.stock_package_id = p.id AND s.on_hand > 0) <> 1
                        OR (SELECT min(s.on_hand) FROM inventory.stock_item s
                             WHERE s.stock_package_id = p.id AND s.on_hand > 0) <> p.nominal_qty
                        OR EXISTS (SELECT 1 FROM inventory.stock_item s WHERE s.stock_package_id = p.id
                                      AND s.on_hand > 0 AND NULLIF(btrim(s.lot_number), '') IS NULL))));
SELECT pg_temp.expect_true('LC3 the demo cup is 33 whole cartons of 1,000, of lot UNTRACED (C45), where the boxed units were',
    (SELECT string_agg(location_code || '=' || n, ',' ORDER BY location_code)
       FROM (SELECT s.location_code, count(*) AS n FROM inventory.stock_item s
               JOIN inventory.stock_package p ON p.id = s.stock_package_id
              WHERE s.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND s.on_hand = 1000 AND p.nominal_qty = 1000
                AND s.lot_number = 'UNTRACED' AND p.status = 'IN_STOCK'
                AND p.created_by = 'V20261206000100' AND p.lpn ~ '^LPN-[0-9]{8}-[0-9]{6}$'
              GROUP BY s.location_code) c) = 'HCM-A01-2-B=25,HCM-A02-1-A=8');
SELECT pg_temp.expect_true('LC4 the red cups keep their lots and the quarantined ones stay quarantined',
    (SELECT string_agg(lot_number || '/' || status || '=' || n, ',' ORDER BY lot_number)
       FROM (SELECT s.lot_number, s.status, count(*) AS n FROM inventory.stock_item s
              WHERE s.sku = 'LY-GIAY-DEMO-12OZ-DO' AND s.on_hand > 0 GROUP BY s.lot_number, s.status) c)
        = 'LOT-2026-03/AVAILABLE=12,LOT-2026-04/QUARANTINE=30');
SELECT pg_temp.expect_true('LC5 the four loose demo rows are empty: their units are in the cartons',
    (SELECT count(*) FROM inventory.stock_item s
      WHERE s.on_hand = 0
        AND s.id IN (md5('demo:cups:empty-stock:LY-GIAY-DEMO-12OZ-XANH:HCM-A01-2-B')::uuid, md5('demo:cups:empty-stock:LY-GIAY-DEMO-12OZ-XANH:HCM-A02-1-A')::uuid,
                     md5('demo:cups:empty-stock:LY-GIAY-DEMO-12OZ-DO:HCM-B01-1-A')::uuid, md5('demo:cups:empty-stock:LY-GIAY-DEMO-12OZ-DO:HCM-QC01')::uuid)) = 4);
SELECT pg_temp.expect_true('LC6 each of the 75 demo cartons has the ledger line that put its units into it, no more',
    (SELECT count(*) = 75 FROM inventory.stock_package WHERE created_by = 'V20261206000100')
    AND NOT EXISTS (SELECT 1 FROM inventory.stock_item s JOIN inventory.stock_package p ON p.id = s.stock_package_id
                     WHERE p.created_by = 'V20261206000100'
                       AND s.on_hand <> COALESCE((SELECT sum(m.quantity) FROM inventory.stock_movement m
                                                   WHERE m.movement_type = 'RECEIPT' AND m.reference_type = 'STOCK_PACKAGE'
                                                     AND m.reference_id = p.id AND m.stock_package_id = p.id
                                                     AND m.to_location_code = p.location_code
                                                     AND m.lot_id = s.lot_id), 0)));
SELECT pg_temp.expect_true('LC7 the guards the migration turned off are on again',
    (SELECT bool_and(tgenabled = 'O') FROM pg_trigger
      WHERE tgname IN ('trg_stock_sku_policy', 'tg_stock_package_transition')));

\echo '--- the ledger: a MOVE stays in place only to box units into the carton it names'
SELECT pg_temp.expect_fail_by('LC8 a MOVE in place for a move task', 'ck_stock_movement_shape', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
                                          reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-2-B', 1, 'MOVE_TASK',
            gen_random_uuid(), NOW(),
            (SELECT p.id FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
              WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.location_code = 'HCM-A01-2-B' ORDER BY p.lpn LIMIT 1)) $q$);
SELECT pg_temp.expect_fail_by('LC9 a MOVE in place naming a carton that is not its reference', 'ck_stock_movement_shape', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
                                          reference_type, reference_id, stock_package_id, occurred_at)
    SELECT gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-2-B', p.nominal_qty,
           'STOCK_PACKAGE', gen_random_uuid(), p.id, NOW()
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.location_code = 'HCM-A01-2-B' LIMIT 1 $q$);
SELECT pg_temp.expect_fail('LC10 a MOVE in place whose reference is a carton, naming no carton', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
                                          reference_type, reference_id, occurred_at)
    SELECT gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-2-B', p.nominal_qty,
           'STOCK_PACKAGE', p.id, NOW()
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.location_code = 'HCM-A01-2-B' LIMIT 1 $q$);
SELECT pg_temp.expect_ok('LC11 a MOVE in place into the carton it names', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          quantity, reference_type, reference_id, stock_package_id, occurred_at)
    SELECT gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'UNTRACED', 'HCM-A01-2-B', 'HCM-A01-2-B', p.nominal_qty,
           'STOCK_PACKAGE', p.id, p.id, NOW()
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.location_code = 'HCM-A01-2-B' LIMIT 1 $q$);
-- Not a move by hand (QA 41 has those): it names a return line, and its carton as every line does (V20261230000200).
SELECT pg_temp.expect_ok('LC12 a MOVE from one place to another is what it was', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
                                          reference_type, reference_id, occurred_at, stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-1-A', 1, 'RETURN_REQUEST_LINE',
            gen_random_uuid(), NOW(),
            (SELECT p.id FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
              WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.location_code = 'HCM-A01-2-B' ORDER BY p.lpn LIMIT 1)) $q$);
SELECT pg_temp.expect_fail_by('LC13 a STATUS_CHANGE that changes no status', 'ck_stock_movement_shape', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
                                          from_status, to_status, reference_type, reference_id, occurred_at,
                                          stock_package_id)
    VALUES (gen_random_uuid(), 'STATUS_CHANGE', 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-2-B', 'HCM-A01-2-B', 1, 'AVAILABLE',
            'AVAILABLE', 'STOCK_PACKAGE', gen_random_uuid(), NOW(),
            (SELECT p.id FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
              WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.location_code = 'HCM-A01-2-B' ORDER BY p.lpn LIMIT 1)) $q$);

\echo '--- every unit has a lot (20 BR-22): any SKU may carry one, a lot-tracked one must'
-- In a carton of 2: a row with units outside one is refused by ck_stock_item_whole_cartons (QA 41).
-- 01 BR-03: an item has ONE active carton. The demo seed's own is retired, so the fixture's unit below is the one.
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND inventory_item_id IN (SELECT id FROM inventory.inventory_items WHERE sku IN ('LY-GIAY-DEMO-12OZ-XANH'));
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa40:u')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'QA_QA40', 2);
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty, location_code, status)
VALUES (md5('qa40:p')::uuid, 'QA-QA40-1', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), md5('qa40:u')::uuid, 2, 2, 'HCM-A01-1-A', 'IN_STOCK');
SELECT pg_temp.expect_ok('LC14 a lot on stock of the demo cup', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                      received_at, stock_package_id)
    VALUES (md5('qa40:lot')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 'HCM-A01-1-A', 'LOT-QA40', 2, 0, 'AVAILABLE', NOW(), NOW(),
            md5('qa40:p')::uuid) $q$);
SELECT pg_temp.expect_fail_by('LC15 ... and units are never added to its layer in place: a new receipt makes a new layer (5-5, QA 70)', 'FIFO replenishment requires a new receipt layer', $q$
    UPDATE inventory.stock_item SET on_hand = on_hand + 1 WHERE id = md5('qa40:lot')::uuid $q$);
-- The red cup in a carton of its own, so that the lot rule is what the next statement meets (and not the carton rule).
-- 01 BR-03: an item has ONE active carton. The demo seed's own is retired, so the fixture's unit below is the one.
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND inventory_item_id IN (SELECT id FROM inventory.inventory_items WHERE sku IN ('LY-GIAY-DEMO-12OZ-DO'));
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
SELECT md5('qa40:uo')::uuid, i.id, 'QA_QA40O', 2 FROM inventory.inventory_items i WHERE i.sku = 'LY-GIAY-DEMO-12OZ-DO';
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty, location_code, status)
SELECT md5('qa40:po')::uuid, 'QA-QA40-DO', i.id, md5('qa40:uo')::uuid, 2, 2, 'HCM-A01-1-A', 'IN_STOCK'
  FROM inventory.inventory_items i WHERE i.sku = 'LY-GIAY-DEMO-12OZ-DO';
SELECT pg_temp.expect_fail('LC16 stock of any SKU (the red cup, in a carton) with no lot: every SKU is lot-tracked since 5-5 (QA 70)', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at, stock_package_id)
    VALUES (gen_random_uuid(), 'LY-GIAY-DEMO-12OZ-DO', 'HCM-A01-1-A', 2, 0, 'AVAILABLE', NOW(), NOW(), md5('qa40:po')::uuid) $q$);
-- ... and with one, so that the carton is whole and its deferred check does not answer for the statements below.
INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at, received_at,
                                  stock_package_id)
VALUES (md5('qa40:lot:do')::uuid, 'LY-GIAY-DEMO-12OZ-DO', 'HCM-A01-1-A', 'LOT-QA40-DO', 2, 0, 'AVAILABLE', NOW(), NOW(),
        md5('qa40:po')::uuid);

\echo '--- the cartons it made are cartons like any other'
SELECT pg_temp.expect_true('LC17 a carton it made is never opened: there is no flag to open it with (V20261113000300)',
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
                   AND table_name = 'stock_package' AND column_name = 'unopened'));
SELECT pg_temp.expect_fail_by('LC18 a demo carton holds more than its standard count',
    'FIFO replenishment requires a new receipt layer', $q$
    UPDATE inventory.stock_item SET on_hand = on_hand + 1
     WHERE stock_package_id = (SELECT id FROM inventory.stock_package WHERE created_by = 'V20261206000100'
                                ORDER BY lpn LIMIT 1) $q$);
SELECT pg_temp.expect_fail_by('LC19 a demo carton emptied but still in stock', 'was counted in at 1000 but holds 0', $q$
    UPDATE inventory.stock_item SET on_hand = 0
     WHERE stock_package_id = (SELECT id FROM inventory.stock_package WHERE created_by = 'V20261206000100'
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
SELECT pg_temp.expect_true('LC22 every live hold on stock is one whole carton, held by nothing else',
    NOT EXISTS (SELECT 1 FROM inventory.stock_reservation h JOIN inventory.stock_item s ON s.id = h.stock_item_id
                 WHERE h.status = 'HELD'
                   AND (s.stock_package_id IS NULL OR h.quantity <> s.on_hand
                        OR EXISTS (SELECT 1 FROM inventory.stock_reservation o
                                    WHERE o.stock_item_id = s.id AND o.status = 'HELD' AND o.id <> h.id))));

ROLLBACK;
\echo 'PASS 40_loose_stock_to_cartons'
