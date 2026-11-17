-- =============================================================================
-- Adversarial checks + happy paths: quarantine and release of whole cartons (V20261112000200, team plan 4D-1;
-- kltn-docs 20 §4.8, BR-01, BR-16, BR-21; 05 §4.4, BR-01, BR-14). Needs the demo seed (HCM-QC01 is a QUARANTINE
-- area). Rolled back at the end.
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

-- Fixtures: a carton unit of 4 sofas; a free carton on a bin (FREE), one held for an order (HELD), and one already in
-- quarantine at HCM-QC01 (QRN).
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)
VALUES (md5('qa51:unit')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 'QA51_CTN', 4);
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, received_qty,
                                     location_code, status)
VALUES (md5('qa51:free')::uuid, 'QA51-LPN-F', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa51:unit')::uuid, 4, 4,
        'HCM-A01-1-A', 'IN_STOCK'),
       (md5('qa51:held')::uuid, 'QA51-LPN-H', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa51:unit')::uuid, 4, 4,
        'HCM-A01-1-A', 'IN_STOCK'),
       (md5('qa51:qrn')::uuid, 'QA51-LPN-Q', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa51:unit')::uuid, 4, 4,
        'HCM-QC01', 'IN_STOCK');
INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, on_hand, reserved, status, created_at,
                                  received_at, stock_package_id)
VALUES (md5('qa51:free:row')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 'UNTRACED', 4, 0, 'AVAILABLE', NOW(),
        NOW() - interval '3 days', md5('qa51:free')::uuid),
       (md5('qa51:held:row')::uuid, 'SOFA-3S-GREY', 'HCM-A01-1-A', 'UNTRACED', 4, 4, 'AVAILABLE', NOW(),
        NOW() - interval '2 days', md5('qa51:held')::uuid),
       (md5('qa51:qrn:row')::uuid, 'SOFA-3S-GREY', 'HCM-QC01', 'UNTRACED', 4, 0, 'QUARANTINE', NOW(),
        NOW() - interval '1 day', md5('qa51:qrn')::uuid);

\echo '--- only a free carton is quarantined, and quarantined stock is held for no order (20 BR-01, BR-21)'
SELECT pg_temp.expect_fail('QR1 a carton held for an order is quarantined', $q$
    UPDATE inventory.stock_item SET status = 'QUARANTINE' WHERE id = md5('qa51:held:row')::uuid $q$);
SELECT pg_temp.expect_fail('QR2 quarantined stock takes a hold', $q$
    UPDATE inventory.stock_item SET reserved = 1 WHERE id = md5('qa51:qrn:row')::uuid $q$);
SELECT pg_temp.expect_ok('QR3 a free carton is quarantined, moved whole into the quarantine area', $q$
    UPDATE inventory.stock_package SET location_code = 'HCM-QC01' WHERE id = md5('qa51:free')::uuid;
    UPDATE inventory.stock_item SET status = 'QUARANTINE', location_code = 'HCM-QC01'
     WHERE id = md5('qa51:free:row')::uuid $q$);

\echo '--- the ledger: one STATUS_CHANGE names where the carton was and the quarantine area (20 §4.8, BR-16)'
SELECT pg_temp.expect_ok('QR4 a quarantine''s STATUS_CHANGE from the bin into the quarantine area', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          quantity, from_status, to_status, reference_type, reference_id, occurred_at,
                                          from_stock_package_id, to_stock_package_id)
    VALUES (gen_random_uuid(), 'STATUS_CHANGE', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-A01-1-A', 'HCM-QC01', 4,
            'AVAILABLE', 'QUARANTINE', 'STOCK_PACKAGE', md5('qa51:qrn')::uuid, NOW(), md5('qa51:qrn')::uuid,
            md5('qa51:qrn')::uuid) $q$);
SELECT pg_temp.expect_fail('QR5 a STATUS_CHANGE across two places for another document', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          quantity, from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'STATUS_CHANGE', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-A01-1-A', 'HCM-QC01', 4,
            'AVAILABLE', 'QUARANTINE', 'MOVE_TASK', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_fail('QR6 a STATUS_CHANGE across two places out of quarantine', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          quantity, from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'STATUS_CHANGE', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-QC01', 'HCM-A01-1-A', 4,
            'QUARANTINE', 'AVAILABLE', 'STOCK_PACKAGE', gen_random_uuid(), NOW()) $q$);

\echo '--- quarantined stock leaves only by an approved adjustment or its release''s putaway (20 BR-21, 05 BR-14)'
SELECT pg_temp.expect_fail('QR7 quarantined stock picked for an order', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, quantity,
                                          from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'PICK', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-QC01', 4, 'QUARANTINE', 'QUARANTINE',
            'PICK_LINE', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_fail('QR8 quarantined stock made sellable by a hand move', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          quantity, from_status, to_status, reference_type, reference_id, occurred_at,
                                          from_stock_package_id, to_stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-QC01', 'HCM-A01-1-A', 4, 'QUARANTINE',
            'AVAILABLE', 'MOVE_TASK', gen_random_uuid(), NOW(), md5('qa51:qrn')::uuid, md5('qa51:qrn')::uuid) $q$);
SELECT pg_temp.expect_fail('QR9 quarantined stock written off without an adjustment (a scrap line)', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, quantity,
                                          from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'SCRAP', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-QC01', 4, 'QUARANTINE', 'QUARANTINE',
            'DAMAGE_REPORT', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_ok('QR10 quarantined stock written off by an approved adjustment', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, quantity,
                                          from_status, to_status, reference_type, reference_id, occurred_at,
                                          from_stock_package_id)
    VALUES (gen_random_uuid(), 'ADJUSTMENT', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-QC01', 4, 'QUARANTINE', 'QUARANTINE',
            'STOCK_ADJUSTMENT', gen_random_uuid(), NOW(), md5('qa51:qrn')::uuid) $q$);
SELECT pg_temp.expect_ok('QR11 a released carton put away into a bin as AVAILABLE', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          quantity, from_status, to_status, reference_type, reference_id, occurred_at,
                                          from_stock_package_id, to_stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-QC01', 'HCM-A01-1-A', 4, 'QUARANTINE',
            'AVAILABLE', 'PUTAWAY_TASK', gen_random_uuid(), NOW(), md5('qa51:qrn')::uuid, md5('qa51:qrn')::uuid) $q$);
SELECT pg_temp.expect_ok('QR12 a quarantined carton moved inside quarantine stays QUARANTINE', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                          quantity, from_status, to_status, reference_type, reference_id, occurred_at,
                                          from_stock_package_id, to_stock_package_id)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'UNTRACED', 'HCM-QC01', 'HCM-RTV01', 4, 'QUARANTINE',
            'QUARANTINE', 'MOVE_TASK', gen_random_uuid(), NOW(), md5('qa51:qrn')::uuid, md5('qa51:qrn')::uuid) $q$);

\echo '--- putaway tasks: RECEIPT or QUARANTINE_RELEASE, each in its own shape (05 BR-01)'
SELECT pg_temp.expect_fail('QR13 a RECEIPT task naming no receipt line', $q$
    INSERT INTO warehouse.putaway_task (id, source, sku, quantity, status, created_at, stock_package_id)
    VALUES (gen_random_uuid(), 'RECEIPT', 'SOFA-3S-GREY', 4, 'PENDING', NOW(), md5('qa51:qrn')::uuid) $q$);
SELECT pg_temp.expect_fail('QR14 a release naming a receipt line', $q$
    INSERT INTO warehouse.putaway_task (id, source, goods_receipt_id, goods_receipt_line_id, sku, quantity, status,
                                        created_at, stock_package_id)
    VALUES (gen_random_uuid(), 'QUARANTINE_RELEASE', gen_random_uuid(), gen_random_uuid(), 'SOFA-3S-GREY', 4,
            'PENDING', NOW(), md5('qa51:qrn')::uuid) $q$);
SELECT pg_temp.expect_fail('QR15 a release of no carton', $q$
    INSERT INTO warehouse.putaway_task (id, source, sku, quantity, status, created_at)
    VALUES (gen_random_uuid(), 'QUARANTINE_RELEASE', 'SOFA-3S-GREY', 4, 'PENDING', NOW()) $q$);
SELECT pg_temp.expect_fail('QR16 a source of the removed flows (a transfer, a return line)', $q$
    INSERT INTO warehouse.putaway_task (id, source, sku, quantity, status, created_at, stock_package_id)
    VALUES (gen_random_uuid(), 'RETURN', 'SOFA-3S-GREY', 4, 'PENDING', NOW(), md5('qa51:qrn')::uuid) $q$);
SELECT pg_temp.expect_fail('QR17 a release of a carton that is not quarantined', $q$
    INSERT INTO warehouse.putaway_task (id, source, sku, quantity, status, created_at, stock_package_id)
    VALUES (gen_random_uuid(), 'QUARANTINE_RELEASE', 'SOFA-3S-GREY', 4, 'PENDING', NOW(),
            md5('qa51:held')::uuid) $q$);
SELECT pg_temp.expect_ok('QR18 a release of a quarantined carton', $q$
    INSERT INTO warehouse.putaway_task (id, source, sku, quantity, status, created_at, stock_package_id)
    VALUES (md5('qa51:task')::uuid, 'QUARANTINE_RELEASE', 'SOFA-3S-GREY', 4, 'PENDING', NOW(),
            md5('qa51:qrn')::uuid) $q$);
SELECT pg_temp.expect_fail('QR19 a second open task for the same carton', $q$
    INSERT INTO warehouse.putaway_task (id, source, sku, quantity, status, created_at, stock_package_id)
    VALUES (gen_random_uuid(), 'QUARANTINE_RELEASE', 'SOFA-3S-GREY', 4, 'PENDING', NOW(),
            md5('qa51:qrn')::uuid) $q$);
SELECT pg_temp.expect_fail('QR20 a task changing its source', $q$
    UPDATE warehouse.putaway_task SET source = 'RECEIPT' WHERE id = md5('qa51:task')::uuid $q$);

\echo '--- the shape of the schema'
SELECT pg_temp.expect_true('QR21 the legacy return-line source column is gone',
    NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'warehouse'
                   AND table_name = 'putaway_task' AND column_name = 'return_request_line_id'));
SELECT pg_temp.expect_true('QR22 every putaway task written before is a RECEIPT task of a receipt line',
    NOT EXISTS (SELECT 1 FROM warehouse.putaway_task
                 WHERE id <> md5('qa51:task')::uuid AND (source <> 'RECEIPT' OR goods_receipt_line_id IS NULL)));
SELECT pg_temp.expect_true('QR23 the quarantine checks are validated on the demo data',
    (SELECT bool_and(convalidated) FROM pg_constraint
      WHERE conname IN ('ck_stock_item_quarantine_unheld', 'ck_stock_movement_quarantine_exit',
                        'ck_putaway_task_source')));

ROLLBACK;
\echo 'PASS 51_carton_quarantine'
