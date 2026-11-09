-- =============================================================================
-- Adversarial checks + happy paths: every unit in stock is in a whole LPN carton (V20261111000110, team plan 4C-1;
-- kltn-docs 20 BR-14, BR-18, BR-22, §4.6, §5.5; open question C45). Needs the demo seed, whose loose sofas and oak
-- tables the migration put into cartons of one (V20261111000100). Rolled back at the end.
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

\echo '--- nothing loose (20 BR-14)'
SELECT pg_temp.expect_true('LC1 no unit in stock lies outside a carton',
    NOT EXISTS (SELECT 1 FROM inventory.stock_item WHERE on_hand > 0 AND stock_package_id IS NULL));
SELECT pg_temp.expect_true('LC2 every carton in stock is one sealed carton of its standard count, of one lot (BR-18)',
    NOT EXISTS (SELECT 1 FROM inventory.stock_package p
                 WHERE p.status = 'IN_STOCK'
                   AND (NOT p.unopened OR p.received_qty <> p.nominal_qty
                        OR (SELECT count(*) FROM inventory.stock_item s
                             WHERE s.stock_package_id = p.id AND s.on_hand > 0) <> 1
                        OR (SELECT min(s.on_hand) FROM inventory.stock_item s
                             WHERE s.stock_package_id = p.id AND s.on_hand > 0) <> p.nominal_qty
                        OR EXISTS (SELECT 1 FROM inventory.stock_item s WHERE s.stock_package_id = p.id
                                      AND s.on_hand > 0 AND NULLIF(btrim(s.lot_number), '') IS NULL))));
SELECT pg_temp.expect_true('LC3 the demo sofa is 33 cartons of one, of lot UNTRACED (C45), where its units were',
    (SELECT string_agg(location_code || '=' || n, ',' ORDER BY location_code)
       FROM (SELECT s.location_code, count(*) AS n FROM inventory.stock_item s
               JOIN inventory.stock_package p ON p.id = s.stock_package_id
              WHERE s.sku = 'SOFA-3S-GREY' AND s.on_hand = 1 AND s.lot_number = 'UNTRACED' AND p.status = 'IN_STOCK'
                AND p.created_by = 'V20261111000110' AND p.lpn ~ '^LPN-[0-9]{8}-[0-9]{6}$'
              GROUP BY s.location_code) c) = 'HCM-A01-2-B=25,HCM-A02-1-A=8');
SELECT pg_temp.expect_true('LC4 the oak tables keep their lots and the quarantined ones stay quarantined',
    (SELECT string_agg(lot_number || '/' || status || '=' || n, ',' ORDER BY lot_number)
       FROM (SELECT s.lot_number, s.status, count(*) AS n FROM inventory.stock_item s
              WHERE s.sku = 'TABLE-OAK-160' AND s.on_hand > 0 GROUP BY s.lot_number, s.status) c)
        = 'LOT-2026-03/AVAILABLE=12,LOT-2026-04/QUARANTINE=30');
SELECT pg_temp.expect_true('LC5 the demo rows were archived as they were, and are empty now',
    (SELECT count(*) FROM platform.legacy_archive a JOIN inventory.stock_item s ON s.id = a.row_id
      WHERE a.source_table = 'inventory.stock_item' AND s.on_hand = 0 AND (a.row_data ->> 'on_hand')::int > 0
        AND s.id IN ('11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222',
                     '33333333-3333-4333-8333-333333333333', '44444444-4444-4444-8444-444444444444')) = 4);
SELECT pg_temp.expect_true('LC6 each new carton has the ledger lines that put its units into it, no more',
    NOT EXISTS (SELECT 1 FROM inventory.stock_item s JOIN inventory.stock_package p ON p.id = s.stock_package_id
                 WHERE p.created_by = 'V20261111000110'
                   AND s.on_hand <> COALESCE((SELECT sum(m.quantity) FROM inventory.stock_movement m
                                               WHERE m.movement_type = 'MOVE' AND m.reference_type = 'STOCK_PACKAGE'
                                                 AND m.reference_id = p.id AND m.to_stock_package_id = p.id
                                                 AND m.from_location_code = m.to_location_code
                                                 AND m.lot_id = s.lot_id), 0)));
SELECT pg_temp.expect_true('LC7 the guards the migration turned off are on again',
    (SELECT bool_and(tgenabled = 'O') FROM pg_trigger
      WHERE tgname IN ('trg_stock_sku_policy', 'tg_stock_package_transition')));

\echo '--- the ledger: a MOVE stays in place only to box units into the carton it names'
SELECT pg_temp.expect_fail('LC8 a MOVE in place for a move task', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
                                          reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'HCM-A01-2-B', 'HCM-A01-2-B', 1, 'MOVE_TASK',
            gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_fail('LC9 a MOVE in place naming a carton it does not put the units into', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
                                          reference_type, reference_id, to_stock_package_id, occurred_at)
    SELECT gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'HCM-A01-2-B', 'HCM-A01-2-B', 1, 'STOCK_PACKAGE',
           gen_random_uuid(), p.id, NOW()
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE i.sku = 'SOFA-3S-GREY' AND p.location_code = 'HCM-A01-2-B' LIMIT 1 $q$);
SELECT pg_temp.expect_fail('LC10 a MOVE in place naming a carton and putting the units nowhere', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
                                          reference_type, reference_id, occurred_at)
    SELECT gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'HCM-A01-2-B', 'HCM-A01-2-B', 1, 'STOCK_PACKAGE', p.id, NOW()
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE i.sku = 'SOFA-3S-GREY' AND p.location_code = 'HCM-A01-2-B' LIMIT 1 $q$);
SELECT pg_temp.expect_ok('LC11 a MOVE in place into the carton it names', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          quantity, reference_type, reference_id, to_stock_package_id, occurred_at)
    SELECT gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-A01-2-B', 'HCM-A01-2-B', 1, 'STOCK_PACKAGE',
           p.id, p.id, NOW()
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE i.sku = 'SOFA-3S-GREY' AND p.location_code = 'HCM-A01-2-B' LIMIT 1 $q$);
-- A loose one names a return line: a person's move names its carton since V20261111000200 (QA 41).
SELECT pg_temp.expect_ok('LC12 a MOVE from one place to another is what it was', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
                                          reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'HCM-A01-2-B', 'HCM-A01-1-A', 1, 'RETURN_REQUEST_LINE',
            gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_fail('LC13 a STATUS_CHANGE that changes no status', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
                                          from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'STATUS_CHANGE', 'SOFA-3S-GREY', 'HCM-A01-2-B', 'HCM-A01-2-B', 1, 'AVAILABLE',
            'AVAILABLE', 'STOCK_PACKAGE', gen_random_uuid(), NOW()) $q$);

\echo '--- every unit has a lot (20 BR-22): any SKU may carry one, a lot-tracked one must'
SELECT pg_temp.expect_ok('LC14 a lot on stock of a SKU that is not lot-tracked (the demo sofa)', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                      received_at)
    VALUES (md5('qa40:lot')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 'LOT-QA40', 2, 0, 'AVAILABLE', NOW(), NOW()) $q$);
SELECT pg_temp.expect_ok('LC15 ... and more of it later', $q$
    UPDATE inventory.stock_item SET on_hand = on_hand + 1 WHERE id = md5('qa40:lot')::uuid $q$);
SELECT pg_temp.expect_fail('LC16 stock of a lot-tracked SKU (the oak table) with no lot', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, created_at, received_at)
    VALUES (gen_random_uuid(), 'TABLE-OAK-160', 'HCM-A01-1-A', 2, 0, 'AVAILABLE', NOW(), NOW()) $q$);

\echo '--- the cartons it made are cartons like any other'
SELECT pg_temp.expect_fail('LC17 a carton it made is opened and sealed again', $q$
    UPDATE inventory.stock_package SET unopened = FALSE
     WHERE id = (SELECT id FROM inventory.stock_package WHERE created_by = 'V20261111000110' ORDER BY lpn LIMIT 1);
    UPDATE inventory.stock_package SET unopened = TRUE
     WHERE id = (SELECT id FROM inventory.stock_package WHERE created_by = 'V20261111000110' ORDER BY lpn LIMIT 1) $q$);
SELECT pg_temp.expect_fail('LC18 a carton it made holds more than its standard count', $q$
    UPDATE inventory.stock_item SET on_hand = 2
     WHERE stock_package_id = (SELECT id FROM inventory.stock_package WHERE created_by = 'V20261111000110'
                                ORDER BY lpn LIMIT 1) $q$);
SELECT pg_temp.expect_fail('LC19 a carton it made emptied but still in stock', $q$
    UPDATE inventory.stock_item SET on_hand = 0
     WHERE stock_package_id = (SELECT id FROM inventory.stock_package WHERE created_by = 'V20261111000110'
                                ORDER BY lpn LIMIT 1) $q$);
SELECT pg_temp.expect_fail('LC20 a second LPN the same as one it handed out', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    SELECT gen_random_uuid(), p.lpn, p.inventory_item_id, p.uom_conversion_id, p.nominal_qty, p.location_code
      FROM inventory.stock_package p WHERE p.created_by = 'V20261111000110' ORDER BY p.lpn LIMIT 1 $q$);
SELECT pg_temp.expect_true('LC21 the LPN sequence of each day it numbered on is past every LPN it handed out that day',
    NOT EXISTS (SELECT 1 FROM inventory.stock_package p
                 WHERE p.created_by = 'V20261111000110'
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
