-- =============================================================================
-- Adversarial checks + happy paths: the carton is the stock (V20261231000600, ADR-0011 step 3d). inventory.stock_item,
-- on_hand and reserved are gone; a carton (inventory.stock_package) carries its stock status, lot and FIFO date, and
-- "is this carton taken" is the one HELD row of inventory.stock_reservation that names it. One section per rule of the
-- step's invariants table. Needs the demo seed (the blue and the red demo cup, the HCM and DNI warehouses, the staging
-- area HCM-STG01, one return request). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail_by(label TEXT, rule TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        -- Deferred constraints and constraint triggers fire here, still inside the sub-transaction:
        -- tg_stock_package_held_available is one of them.
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

-- Fixtures. A carton unit of 6 blue cups (01 BR-03: a variant has ONE active carton, V20261231000700; the demo seed's own is retired), a
-- lot of them, and cartons of it named by what they are for:
--   f1 f2 f3   free AVAILABLE cartons on a bin of HCM          blk    BLOCKED on that bin
--   und        AVAILABLE, its receipt time not known           stg    AVAILABLE, standing in the staging area
--   dni        AVAILABLE on a bin of the other warehouse       rcv    RECEIVING: labelled and counted, not in stock
--   opn        QUARANTINE, came back opened on a return        ret    QUARANTINE, back on a return still PENDING
--   cmp        QUARANTINE, back on a return that made it the company's
--   cus        CONSUMED, the customer's (held for them)
-- and one carton of the red cup (red) for "another SKU".
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND variant_id = (SELECT variant_id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH');
INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor)
VALUES (md5('qa82:unit')::uuid, (SELECT variant_id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'QA82_CTN', 6);
INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at)
VALUES (md5('qa82:lot')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 'QA82-LOT', NOW() - interval '5 days', 'UNTRACED', NOW()),
       (md5('qa82:lot2')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 'QA82-LOT-2', NOW() - interval '5 days', 'UNTRACED', NOW());
CREATE FUNCTION pg_temp.carton(k TEXT, loc TEXT, stock TEXT, received TEXT DEFAULT 'NOW() - interval ''5 days''',
                               lot TEXT DEFAULT 'md5(''qa82:lot'')::uuid') RETURNS TEXT LANGUAGE sql AS $f$
    SELECT format($i$
        INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at,
                                             location_code, status, stock_status, lot_id, received_at)
        SELECT md5('qa82:' || %L)::uuid, 'QA82-' || upper(%L),
               (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), u.id, u.factor, NOW(), %L,
               'IN_STOCK', %s, %s, %s
          FROM inventory.uom_conversions u WHERE u.id = md5('qa82:unit')::uuid $i$,
        k, k, loc, CASE WHEN stock IS NULL THEN 'NULL' ELSE quote_literal(stock) END, lot, received)
$f$;
DO $$
DECLARE
    spec RECORD;
BEGIN
    FOR spec IN SELECT * FROM (VALUES ('f1', 'HCM-A01-1-A', 'AVAILABLE'), ('f2', 'HCM-A01-1-A', 'AVAILABLE'),
                                      ('f3', 'HCM-A01-1-A', 'AVAILABLE'), ('blk', 'HCM-A01-1-A', 'BLOCKED'),
                                      ('stg', 'HCM-STG01', 'AVAILABLE'), ('dni', 'DNI-A01-1-A', 'AVAILABLE'),
                                      ('opn', 'HCM-QC01', 'QUARANTINE'), ('ret', 'HCM-QC01', 'QUARANTINE'),
                                      ('cmp', 'HCM-QC01', 'QUARANTINE'), ('cus', 'HCM-QC01', 'QUARANTINE'))
                             v(k, loc, stock) LOOP
        EXECUTE pg_temp.carton(spec.k, spec.loc, spec.stock);
    END LOOP;
    EXECUTE pg_temp.carton('und', 'HCM-A01-1-A', 'AVAILABLE', 'NULL');
END $$;
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at, location_code)
SELECT md5('qa82:rcv')::uuid, 'QA82-RCV', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), u.id, u.factor, NOW(), 'HCM-RCV01'
  FROM inventory.uom_conversions u WHERE u.id = md5('qa82:unit')::uuid;
UPDATE inventory.stock_package SET return_opened_at = NOW() WHERE id = md5('qa82:opn')::uuid;
UPDATE inventory.stock_package SET return_request_id = (SELECT id FROM ordering.return_request ORDER BY id LIMIT 1),
       return_ownership = 'PENDING' WHERE id = md5('qa82:ret')::uuid;
UPDATE inventory.stock_package SET return_request_id = (SELECT id FROM ordering.return_request ORDER BY id LIMIT 1),
       return_ownership = 'COMPANY' WHERE id = md5('qa82:cmp')::uuid;
UPDATE inventory.stock_package SET status = 'CONSUMED', stock_status = NULL, closed_at = NOW(),
       return_request_id = (SELECT id FROM ordering.return_request ORDER BY id LIMIT 1),
       return_ownership = 'CUSTOMER_HELD', custody_held_at = NOW() WHERE id = md5('qa82:cus')::uuid;
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
CREATE TEMP TABLE qa82_red AS
SELECT p.id, p.nominal_qty FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
 WHERE i.sku = 'LY-GIAY-DEMO-12OZ-DO' AND p.stock_status = 'AVAILABLE' AND p.location_code LIKE 'HCM-%'
   AND NOT EXISTS (SELECT 1 FROM inventory.stock_reservation a WHERE a.stock_package_id = p.id AND a.status = 'HELD')
 ORDER BY p.lpn LIMIT 1;

-- Two orders of the first customer, and a SKU-level hold of the first: 2 cartons of 6 at HCM.
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                     placed_at, version, created_at)
SELECT md5('qa82:order:' || k)::uuid, 'SO-QA-82' || k, c.id, gen_random_uuid(), 'PENDING_PAYMENT', 1000, 'VND', NOW(), 0, NOW()
  FROM (SELECT id FROM customer.customer ORDER BY id LIMIT 1) c, (VALUES ('A'), ('B')) v(k);
INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, cartons, units_per_carton,
                                         reserved_at, status, warehouse_code)
VALUES (md5('qa82:soft')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa82:order:A')::uuid, md5('qa82:soft')::uuid,
        gen_random_uuid(), 2, 6, NOW(), 'HELD', 'HCM');
-- An allocation of carton "k" made from that hold, as a release makes it; "units" and "cartons" for the wrong ones.
CREATE FUNCTION pg_temp.alloc(id_key TEXT, carton TEXT, units INT DEFAULT 6, cartons INT DEFAULT 1,
                              sku TEXT DEFAULT 'LY-GIAY-DEMO-12OZ-XANH', order_key TEXT DEFAULT 'A',
                              status TEXT DEFAULT 'HELD') RETURNS TEXT LANGUAGE sql AS $f$
    SELECT format($i$
        INSERT INTO inventory.stock_reservation (id, stock_package_id, sku, order_id, allocated_from_id, root_request_id,
            request_id, cartons, units_per_carton, reserved_at, status, closed_at)
        VALUES (md5('qa82:alloc:' || %L)::uuid, %s, %L, md5('qa82:order:' || %L)::uuid, md5('qa82:soft')::uuid,
                md5('qa82:soft')::uuid, gen_random_uuid(), %s, %s, NOW(), %L, %s) $i$,
        id_key, carton, sku, order_key, cartons, units, status, CASE WHEN status = 'HELD' THEN 'NULL' ELSE 'NOW()' END)
$f$;

\echo '--- the old shape is gone, and nothing live reads it'
SELECT pg_temp.expect_true('G1 inventory.stock_item and the view inventory.stock_package_balance are gone',
    to_regclass('inventory.stock_item') IS NULL AND to_regclass('inventory.stock_package_balance') IS NULL);
SELECT pg_temp.expect_true('G2 no table or view of the application has a column on_hand, reserved or stock_item_id',
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
                   AND column_name IN ('on_hand', 'reserved', 'stock_item_id')));
-- Postgres does not track what a function body mentions: a function still reading a dropped table fails only when it
-- runs. "reserved" is looked for as a column (alias.reserved, or compared / assigned), not as a word of a message.
SELECT pg_temp.expect_true('G3 no live function reads stock_item, on_hand or a reserved column',
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                   AND (p.prosrc ~* 'stock_item' OR p.prosrc ~* '\mon_hand\M' OR p.prosrc ~* '\.reserved\M'
                        OR p.prosrc ~* '\mreserved\s*(=|<|>|\+|-)' OR p.prosrc ~* 'same_receipt_merge')));
SELECT pg_temp.expect_true('G4 no view, CHECK, trigger condition or index mentions them',
    NOT EXISTS (SELECT 1 FROM pg_views WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
                   AND definition ~* 'stock_item|\mon_hand\M|\.reserved\M')
    AND NOT EXISTS (SELECT 1 FROM pg_matviews WHERE definition ~* 'stock_item|\mon_hand\M|\.reserved\M')
    AND NOT EXISTS (SELECT 1 FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
                     WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                       AND (pg_get_constraintdef(c.oid) ~* 'stock_item|\mon_hand\M|\mreserved\M'
                            OR c.conname ~* 'stock_item'))
    AND NOT EXISTS (SELECT 1 FROM pg_trigger t WHERE NOT t.tgisinternal
                       AND pg_get_triggerdef(t.oid) ~* 'stock_item|\mon_hand\M|\mreserved\M')
    AND NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
                       AND indexdef ~* 'stock_item|\mon_hand\M|\mreserved\M'));
SELECT pg_temp.expect_true('G5 the functions that only served the stock rows are gone',
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'inventory'
                   AND p.proname IN ('check_stock_item_package', 'check_stock_package_contents',
                                     'check_stock_package_rows', 'resolve_stock_item_lot', 'enforce_sku_policy',
                                     'refuse_new_expired_stock', 'refuse_opened_return_carton_on_sale',
                                     'check_stock_item_returned_carton', 'check_allocation_whole_carton')));
SELECT pg_temp.expect_true('G6 what an allocation and a release read is indexed',
    (SELECT count(*) FROM pg_indexes WHERE schemaname = 'inventory'
        AND indexname IN ('uk_stock_reservation_live_carton', 'ix_stock_reservation_package',
                          'ix_stock_package_available', 'ix_stock_package_lot',
                          'ix_stock_reservation_sku_held', 'ix_stock_reservation_warehouse_held')) = 6
    AND (SELECT indexdef LIKE '%UNIQUE%(stock_package_id)%WHERE%status%HELD%' FROM pg_indexes
          WHERE indexname = 'uk_stock_reservation_live_carton'));

\echo '--- a stock status exactly while in stock; a lot once in stock; lot and FIFO date frozen'
SELECT pg_temp.expect_fail_by('S1 a carton in stock with no stock status', 'ck_stock_package_stock_status', $q$
    UPDATE inventory.stock_package SET stock_status = NULL WHERE id = md5('qa82:f2')::uuid $q$);
SELECT pg_temp.expect_fail_by('S2 a carton out of stock keeping one', 'ck_stock_package_stock_status', $q$
    UPDATE inventory.stock_package SET status = 'CONSUMED', closed_at = NOW() WHERE id = md5('qa82:f2')::uuid $q$);
SELECT pg_temp.expect_fail_by('S3 a carton still RECEIVING given one', 'ck_stock_package_stock_status', $q$
    UPDATE inventory.stock_package SET stock_status = 'INBOUND' WHERE id = md5('qa82:rcv')::uuid $q$);
SELECT pg_temp.expect_fail_by('S4 a stock status nobody knows', 'ck_stock_package_stock_status_value', $q$
    UPDATE inventory.stock_package SET stock_status = 'ON_SALE' WHERE id = md5('qa82:f2')::uuid $q$);
SELECT pg_temp.expect_fail_by('S5 newly EXPIRED: stock has no expiry (20 BR-13)', 'cannot become EXPIRED', $q$
    UPDATE inventory.stock_package SET stock_status = 'EXPIRED' WHERE id = md5('qa82:f2')::uuid $q$);
SELECT pg_temp.expect_true('L1 a carton still RECEIVING has no lot, and may',
    (SELECT lot_id IS NULL AND received_at IS NULL AND stock_status IS NULL FROM inventory.stock_package
      WHERE id = md5('qa82:rcv')::uuid));
SELECT pg_temp.expect_fail_by('L2 it goes into stock with no lot', 'ck_stock_package_lot', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'INBOUND', received_at = NOW()
     WHERE id = md5('qa82:rcv')::uuid $q$);
SELECT pg_temp.expect_fail_by('L3 a carton made IN_STOCK with no lot', 'ck_stock_package_lot',
    pg_temp.carton('nolot', 'HCM-A01-1-A', 'AVAILABLE', 'NOW()', 'NULL'));
-- A carton cannot be created CONSUMED or LOST (tg_stock_package_created); with the row triggers off the CHECK alone
-- still refuses one that has been in stock and has no lot.
SELECT pg_temp.expect_fail_by('L4 a CONSUMED carton with no lot: the CHECK alone', 'ck_stock_package_lot', $q$
    SET LOCAL session_replication_role = replica;
    UPDATE inventory.stock_package SET lot_id = NULL WHERE id = md5('qa82:cus')::uuid $q$);
SELECT pg_temp.expect_fail_by('L5 a LOST carton with no lot: the CHECK alone', 'ck_stock_package_lot', $q$
    SET LOCAL session_replication_role = replica;
    UPDATE inventory.stock_package SET status = 'LOST', stock_status = NULL, closed_at = NOW(), lot_id = NULL
     WHERE id = md5('qa82:f2')::uuid $q$);
SET LOCAL session_replication_role = origin;
SELECT pg_temp.expect_fail_by('L6 it goes into stock in a lot of another SKU', 'but lot', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'INBOUND', received_at = NOW(),
           lot_id = (SELECT id FROM inventory.stock_lot WHERE sku = 'LY-GIAY-DEMO-12OZ-DO' ORDER BY id LIMIT 1)
     WHERE id = md5('qa82:rcv')::uuid $q$);
SELECT pg_temp.expect_fail_by('L6b it goes into stock in a lot with no lot number (01 BR-17)', 'needs a lot number', $q$
    INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, created_at)
    VALUES (md5('qa82:blank')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', '   ', NULL, 'UNTRACED', NOW());
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'INBOUND', received_at = NOW(),
           lot_id = md5('qa82:blank')::uuid
     WHERE id = md5('qa82:rcv')::uuid $q$);
SELECT pg_temp.expect_fail_by('L7 it goes into stock received tomorrow', 'invalid receipt date', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'INBOUND', lot_id = md5('qa82:lot')::uuid,
           received_at = NOW() + interval '1 day' WHERE id = md5('qa82:rcv')::uuid $q$);
SELECT pg_temp.expect_ok('L8 RECEIVING -> IN_STOCK: this is when the lot and the FIFO date are written', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'INBOUND', lot_id = md5('qa82:lot')::uuid,
           received_at = NOW() WHERE id = md5('qa82:rcv')::uuid $q$);
SELECT pg_temp.expect_fail_by('L9 its lot changed once in stock', 'never changed (FIFO)', $q$
    UPDATE inventory.stock_package SET lot_id = md5('qa82:lot2')::uuid WHERE id = md5('qa82:rcv')::uuid $q$);
SELECT pg_temp.expect_fail_by('L10 its receipt date changed once in stock', 'never changed (FIFO)', $q$
    UPDATE inventory.stock_package SET received_at = received_at - interval '30 days' WHERE id = md5('qa82:rcv')::uuid $q$);
SELECT pg_temp.expect_fail_by('L11 an undated carton dated on the shelf: it stays undated and sorts last', 'never changed (FIFO)', $q$
    UPDATE inventory.stock_package SET received_at = NOW() - interval '1 year' WHERE id = md5('qa82:und')::uuid $q$);
SELECT pg_temp.expect_ok('L12 a carton out of stock and back keeps both: restocked, it is as old as it was', $q$
    UPDATE inventory.stock_package SET status = 'CONSUMED', stock_status = NULL, closed_at = NOW() WHERE id = md5('qa82:f2')::uuid;
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'AVAILABLE', closed_at = NULL WHERE id = md5('qa82:f2')::uuid $q$);
SELECT pg_temp.expect_fail_by('L13 ... and is not given another lot or date as it comes back', 'never changed (FIFO)', $q$
    UPDATE inventory.stock_package SET status = 'LOST', stock_status = NULL, closed_at = NOW() WHERE id = md5('qa82:f2')::uuid;
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'AVAILABLE', closed_at = NULL,
           received_at = NOW() WHERE id = md5('qa82:f2')::uuid $q$);

\echo '--- returns: an opened carton is never on sale; a returned one stays in quarantine while it is not the company''s'
SELECT pg_temp.expect_fail_by('R1 a carton that came back opened made AVAILABLE', 'ck_stock_package_opened_return', $q$
    UPDATE inventory.stock_package SET stock_status = 'AVAILABLE' WHERE id = md5('qa82:opn')::uuid $q$);
SELECT pg_temp.expect_fail_by('R2 an AVAILABLE carton marked as come back opened', 'ck_stock_package_opened_return', $q$
    UPDATE inventory.stock_package SET return_opened_at = NOW() WHERE id = md5('qa82:f2')::uuid $q$);
SELECT pg_temp.expect_fail_by('R3 a carton whose return is PENDING leaves quarantine', 'ck_stock_package_returned_in_quarantine', $q$
    UPDATE inventory.stock_package SET stock_status = 'AVAILABLE' WHERE id = md5('qa82:ret')::uuid $q$);
SELECT pg_temp.expect_fail_by('R4 ... or is blocked instead', 'ck_stock_package_returned_in_quarantine', $q$
    UPDATE inventory.stock_package SET stock_status = 'BLOCKED' WHERE id = md5('qa82:ret')::uuid $q$);
SELECT pg_temp.expect_fail_by('R5 the customer''s carton brought back into stock on sale', 'ck_stock_package_returned_in_quarantine', $q$
    UPDATE inventory.stock_package SET status = 'IN_STOCK', stock_status = 'AVAILABLE', closed_at = NULL
     WHERE id = md5('qa82:cus')::uuid $q$);
SELECT pg_temp.expect_fail_by('R6 an AVAILABLE carton said to be back on a PENDING return', 'ck_stock_package_returned_in_quarantine', $q$
    UPDATE inventory.stock_package SET return_request_id = (SELECT id FROM ordering.return_request ORDER BY id LIMIT 1),
           return_ownership = 'PENDING' WHERE id = md5('qa82:f2')::uuid $q$);
SELECT pg_temp.expect_ok('R7 a carton the refund made the company''s is released: AVAILABLE', $q$
    UPDATE inventory.stock_package SET stock_status = 'AVAILABLE' WHERE id = md5('qa82:cmp')::uuid $q$);

\echo '--- an allocation: one free AVAILABLE carton of its hold''s SKU, order, warehouse and count, never out of staging'
SELECT pg_temp.expect_fail_by('A1 of a BLOCKED carton', 'only AVAILABLE stock is allocated',
    pg_temp.alloc('x', 'md5(''qa82:blk'')::uuid'));
SELECT pg_temp.expect_fail_by('A2 of a QUARANTINE carton', 'only AVAILABLE stock is allocated',
    pg_temp.alloc('x', 'md5(''qa82:ret'')::uuid'));
SELECT pg_temp.expect_fail_by('A3 of a carton that is not in stock', 'only AVAILABLE stock is allocated',
    pg_temp.alloc('x', 'md5(''qa82:cus'')::uuid'));
SELECT pg_temp.expect_fail_by('A4 of a carton standing in a STAGING area (20 BR-20)', 'stands in staging area',
    pg_temp.alloc('x', 'md5(''qa82:stg'')::uuid'));
SELECT pg_temp.expect_fail_by('A5 of a carton of another SKU', 'but stock package',
    pg_temp.alloc('x', '(SELECT id FROM qa82_red)', (SELECT nominal_qty FROM qa82_red)));
SELECT pg_temp.expect_fail_by('A6 of a carton in another warehouse than its hold is held at (20 BR-17)', 'the stock is at',
    pg_temp.alloc('x', 'md5(''qa82:dni'')::uuid'));
SELECT pg_temp.expect_fail_by('A7 of another count than the carton''s (1,000 on a carton of 6)', 'an allocation is its carton',
    pg_temp.alloc('x', 'md5(''qa82:f1'')::uuid', 1000));
SELECT pg_temp.expect_fail_by('A8 of part of the carton (3 of 6)', 'an allocation is its carton',
    pg_temp.alloc('x', 'md5(''qa82:f1'')::uuid', 3));
SELECT pg_temp.expect_fail_by('A9 of two cartons: an allocation is one', 'ck_stock_reservation_kind',
    pg_temp.alloc('x', 'md5(''qa82:f1'')::uuid', 6, 2));
SELECT pg_temp.expect_fail_by('A10 for another order than its hold''s', 'for the same order',
    pg_temp.alloc('x', 'md5(''qa82:f1'')::uuid', 6, 1, 'LY-GIAY-DEMO-12OZ-XANH', 'B'));
SELECT pg_temp.expect_fail_by('A11 ALLOCATED on a carton: that is a SKU-level hold''s status', 'ck_stock_reservation_kind',
    pg_temp.alloc('x', 'md5(''qa82:f1'')::uuid', 6, 1, 'LY-GIAY-DEMO-12OZ-XANH', 'A', 'ALLOCATED'));
SELECT pg_temp.expect_fail_by('A12 a SKU-level hold that names a carton', 'ck_stock_reservation_warehouse', $q$
    UPDATE inventory.stock_reservation SET stock_package_id = md5('qa82:f1')::uuid, cartons = 1
     WHERE id = md5('qa82:soft')::uuid $q$);
SELECT pg_temp.expect_fail_by('A13 a SKU-level hold CONSUMED: only a carton is picked', 'ck_stock_reservation_kind', $q$
    UPDATE inventory.stock_reservation SET status = 'CONSUMED', closed_at = NOW() WHERE id = md5('qa82:soft')::uuid $q$);
SELECT pg_temp.expect_fail_by('A14 an allocation of a carton that does not exist (the SKU check meets it first)', 'but stock package',
    pg_temp.alloc('x', 'gen_random_uuid()'));
SELECT pg_temp.expect_ok('A15 the allocation of a free AVAILABLE carton: f1 is taken',
    pg_temp.alloc('f1', 'md5(''qa82:f1'')::uuid'));
SELECT pg_temp.expect_fail_by('A16 a second live allocation of that carton', 'uk_stock_reservation_live_carton',
    pg_temp.alloc('f1b', 'md5(''qa82:f1'')::uuid'));
SELECT pg_temp.expect_ok('A17 history beside it is free: a closed allocation of the same carton',
    pg_temp.alloc('f1old', 'md5(''qa82:f1'')::uuid', 6, 1, 'LY-GIAY-DEMO-12OZ-XANH', 'A', 'RELEASED'));
SELECT pg_temp.expect_fail_by('A18 ... but not made live again while the carton is taken', 'uk_stock_reservation_live_carton', $q$
    UPDATE inventory.stock_reservation SET status = 'HELD', closed_at = NULL WHERE id = md5('qa82:alloc:f1old')::uuid $q$);
SELECT pg_temp.expect_fail_by('A19 the live allocation moved onto a carton that is not AVAILABLE', 'only AVAILABLE stock is allocated', $q$
    UPDATE inventory.stock_reservation SET stock_package_id = md5('qa82:blk')::uuid WHERE id = md5('qa82:alloc:f1')::uuid $q$);

\echo '--- a held carton stays AVAILABLE (checked at commit), but moves with its allocation'
SELECT pg_temp.expect_fail_by('H1 the held carton quarantined', 'is held for order', $q$
    UPDATE inventory.stock_package SET stock_status = 'QUARANTINE', location_code = 'HCM-QC01' WHERE id = md5('qa82:f1')::uuid $q$);
SELECT pg_temp.expect_fail_by('H2 the held carton blocked', 'is held for order', $q$
    UPDATE inventory.stock_package SET stock_status = 'BLOCKED' WHERE id = md5('qa82:f1')::uuid $q$);
SELECT pg_temp.expect_fail_by('H3 the held carton written off (CONSUMED) with its allocation still live', 'is held for order', $q$
    UPDATE inventory.stock_package SET status = 'CONSUMED', stock_status = NULL, closed_at = NOW() WHERE id = md5('qa82:f1')::uuid $q$);
SELECT pg_temp.expect_fail_by('H4 the held carton LOST with its allocation still live', 'is held for order', $q$
    UPDATE inventory.stock_package SET status = 'LOST', stock_status = NULL, closed_at = NOW() WHERE id = md5('qa82:f1')::uuid $q$);
SELECT pg_temp.expect_ok('H5 quarantined in the transaction that gives its hold up (a short-reported carton): passes', $q$
    UPDATE inventory.stock_package SET stock_status = 'QUARANTINE', location_code = 'HCM-QC01' WHERE id = md5('qa82:f1')::uuid;
    UPDATE inventory.stock_reservation SET status = 'RELEASED', release_reason = 'SHORT_PICKED', closed_at = NOW()
     WHERE id = md5('qa82:alloc:f1')::uuid $q$);
-- f3 for the rest: allocated, moved by a hand's ledger line (refused), staged, picked.
SELECT pg_temp.expect_ok('H6 f3 allocated', pg_temp.alloc('f3', 'md5(''qa82:f3'')::uuid'));
SELECT pg_temp.expect_fail_by('H7 a hand MOVE ledger line of the held carton (11 BR-05)', 'it is not moved by hand', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          reference_type, reference_id, stock_package_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'LY-GIAY-DEMO-12OZ-XANH', 'QA82-LOT', 'HCM-A01-1-A', 'HCM-A01-1-B', 'MOVE_TASK',
            gen_random_uuid(), md5('qa82:f3')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('H8 staged: the held carton moved into the staging area, still AVAILABLE, its allocation still HELD', $q$
    UPDATE inventory.stock_package SET location_code = 'HCM-STG01' WHERE id = md5('qa82:f3')::uuid;
    UPDATE inventory.stock_reservation SET order_number = order_number WHERE id = md5('qa82:alloc:f3')::uuid $q$);
SELECT pg_temp.expect_true('H9 ... it is: HELD, AVAILABLE, in HCM-STG01',
    (SELECT p.location_code = 'HCM-STG01' AND p.stock_status = 'AVAILABLE' AND r.status = 'HELD'
       FROM inventory.stock_package p JOIN inventory.stock_reservation r ON r.stock_package_id = p.id
      WHERE r.id = md5('qa82:alloc:f3')::uuid));
SELECT pg_temp.expect_ok('H10 picked: the allocation CONSUMED and the carton CONSUMED, one transaction', $q$
    UPDATE inventory.stock_package SET status = 'CONSUMED', stock_status = NULL, closed_at = NOW() WHERE id = md5('qa82:f3')::uuid;
    UPDATE inventory.stock_reservation SET status = 'CONSUMED', closed_at = NOW() WHERE id = md5('qa82:alloc:f3')::uuid $q$);

\echo '--- SKU-level holds are covered by free cartons of their count, system-wide and per warehouse (20 BR-17)'
-- Free cartons of 6 now: f2 and und at HCM (f1 quarantined, f3 picked, blk BLOCKED, cmp released AVAILABLE in the
-- quarantine area, stg AVAILABLE in the staging area - "every AVAILABLE carton wherever it stands"), dni at DNI.
CREATE TEMP TABLE qa82_free AS
SELECT count(*) FILTER (WHERE split_part(p.location_code, '-', 1) = 'HCM') AS hcm, count(*) AS everywhere
  FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
 WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH' AND p.nominal_qty = 6 AND p.stock_status = 'AVAILABLE'
   AND NOT EXISTS (SELECT 1 FROM inventory.stock_reservation a WHERE a.stock_package_id = p.id AND a.status = 'HELD');
SELECT pg_temp.expect_true('C1 four cartons of 6 stand free at HCM (f2, und, stg, cmp) and five anywhere (dni)',
    (SELECT hcm = 4 AND everywhere = 5 FROM qa82_free));
SELECT pg_temp.expect_ok('C2 the hold grown to all four at HCM', $q$
    UPDATE inventory.stock_reservation SET cartons = 4 WHERE id = md5('qa82:soft')::uuid $q$);
SELECT pg_temp.expect_fail_by('C3 a fifth: one stands free, but at DNI', 'exceed its free available cartons of that count there (4)', $q$
    UPDATE inventory.stock_reservation SET cartons = 5 WHERE id = md5('qa82:soft')::uuid $q$);
SELECT pg_temp.expect_ok('C4 that one held at DNI by the other order', $q$
    INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, cartons, units_per_carton,
        reserved_at, status, warehouse_code)
    VALUES (md5('qa82:soft:dni')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa82:order:B')::uuid, gen_random_uuid(),
            gen_random_uuid(), 1, 6, NOW(), 'HELD', 'DNI') $q$);
SELECT pg_temp.expect_fail_by('C5 a hold at no warehouse: every carton of 6 is spoken for over the system', 'exceed its free available cartons of that count (5)', $q$
    INSERT INTO inventory.stock_reservation (id, sku, order_id, root_request_id, request_id, cartons, units_per_carton,
        reserved_at, status)
    VALUES (gen_random_uuid(), 'LY-GIAY-DEMO-12OZ-XANH', md5('qa82:order:B')::uuid, gen_random_uuid(), gen_random_uuid(),
            1, 6, NOW(), 'HELD') $q$);
SELECT pg_temp.expect_ok('C6 the hold at HCM cut back to two', $q$
    UPDATE inventory.stock_reservation SET cartons = 2 WHERE id = md5('qa82:soft')::uuid $q$);
-- A held carton is not free: once f2 is allocated to the other order, HCM has three free cartons of 6.
INSERT INTO inventory.stock_reservation (id, stock_package_id, sku, order_id, root_request_id, request_id, cartons,
                                         units_per_carton, reserved_at, status)
VALUES (md5('qa82:alloc:f2')::uuid, md5('qa82:f2')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', md5('qa82:order:B')::uuid,
        gen_random_uuid(), gen_random_uuid(), 1, 6, NOW(), 'HELD');
-- The system-wide figure is checked first: four here and the one at DNI are five holds over four free cartons.
SELECT pg_temp.expect_fail_by('C7 a held carton does not cover a hold: four no longer fit at HCM', 'exceed its free available cartons of that count (4)', $q$
    UPDATE inventory.stock_reservation SET cartons = 4 WHERE id = md5('qa82:soft')::uuid $q$);
SELECT pg_temp.expect_fail_by('C7a ... nor with the hold at DNI given up: three stand free at HCM', 'exceed its free available cartons of that count there (3)', $q$
    UPDATE inventory.stock_reservation SET status = 'RELEASED', release_reason = 'MANUAL_OVERRIDE', closed_at = NOW()
     WHERE id = md5('qa82:soft:dni')::uuid;
    UPDATE inventory.stock_reservation SET cartons = 4 WHERE id = md5('qa82:soft')::uuid $q$);
SELECT pg_temp.expect_ok('C8 three do', $q$
    UPDATE inventory.stock_reservation SET cartons = 3 WHERE id = md5('qa82:soft')::uuid $q$);
SELECT pg_temp.expect_true('C9 the cover check takes the SKU''s advisory lock, the one the application takes (key 701)',
    (SELECT prosrc LIKE '%pg_advisory_xact_lock(hashtextextended(NEW.sku, 701))%' FROM pg_proc
      WHERE proname = 'check_sku_reservation_covered' AND pronamespace = 'inventory'::regnamespace));
SELECT pg_temp.expect_true('C10 the allocation trigger locks the carton row before it reads it',
    (SELECT position('FOR NO KEY UPDATE' IN prosrc) > 0
            AND position('FOR NO KEY UPDATE' IN prosrc) < position('carton.stock_status' IN prosrc)
       FROM pg_proc WHERE proname = 'check_stock_reservation_carton' AND pronamespace = 'inventory'::regnamespace));

\echo '--- the demo data stands by every rule above'
SELECT pg_temp.expect_true('D1 every carton has a stock status exactly while in stock, and a lot once it has been',
    NOT EXISTS (SELECT 1 FROM inventory.stock_package
                 WHERE (status = 'IN_STOCK') <> (stock_status IS NOT NULL)
                    OR (lot_id IS NULL AND status NOT IN ('RECEIVING', 'VOID'))));
SELECT pg_temp.expect_true('D2 every allocation names a carton and is one; every SKU-level hold names none',
    NOT EXISTS (SELECT 1 FROM inventory.stock_reservation r
                 WHERE (r.allocated_from_id IS NOT NULL AND (r.stock_package_id IS NULL OR r.cartons <> 1))
                    OR (r.stock_package_id IS NOT NULL AND r.status = 'ALLOCATED')));
SELECT pg_temp.expect_true('D3 the new checks and foreign keys are validated',
    (SELECT count(*) = 8 AND bool_and(convalidated) FROM pg_constraint
      WHERE conname IN ('ck_stock_package_stock_status', 'ck_stock_package_stock_status_value', 'ck_stock_package_lot',
                        'ck_stock_package_opened_return', 'ck_stock_package_returned_in_quarantine',
                        'fk_stock_package_lot', 'fk_stock_reservation_package', 'ck_stock_reservation_kind')));

ROLLBACK;
\echo 'PASS 82_carton_is_the_stock'
