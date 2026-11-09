-- =============================================================================
-- Adversarial checks + happy paths: storage class gone, bin capacity is a computed volume, a carton fits the bin it
-- goes into, OVERFLOW (V20261112000100, team plan 5-4; kltn-docs 06 BR-01, BR-19; 05 BR-03, BR-04, BR-13; 07 BR-12;
-- 11 BR-10). Needs the demo seed: HCM bins are 5 m x 1.2 m on levels 1.4 m clear. Rolled back at the end.
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

-- Fixtures: a carton unit of the sofa measuring 60 x 50 x 40 cm (the demo's own unit has no size), a non-rotatable
-- variant of it on the oak table, and two small bins on a new level of B01: N (0.55 x 0.55, too narrow either way)
-- and T (0.6 wide x 0.5 long: only a carton turned on the horizontal fits), both on a level 0.45 m clear.
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, length_cm, width_cm, height_cm)
VALUES (md5('qa50:unit:sofa')::uuid, md5('demo:item:SOFA-3S-GREY')::uuid, 'QA50_CTN', 1, 60, 50, 40),
       (md5('qa50:unit:table')::uuid, md5('demo:item:TABLE-OAK-160')::uuid, 'QA50_CTN', 1, 60, 50, 40);
UPDATE inventory.inventory_items SET rotatable = FALSE WHERE sku = 'TABLE-OAK-160';
INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
VALUES (md5('qa50:level')::uuid, md5('demo:shelf:HCM-B01')::uuid, 5, 3, 0.45, 500);
INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, max_weight, is_pickable,
                                        is_putaway_target, status)
VALUES (md5('qa50:loc:N')::uuid, md5('demo:wh:HCM')::uuid, 'BIN', 'HCM-B01-5-N', NULL, TRUE, TRUE, 'ACTIVE'),
       (md5('qa50:loc:T')::uuid, md5('demo:wh:HCM')::uuid, 'BIN', 'HCM-B01-5-T', NULL, TRUE, TRUE, 'ACTIVE');
INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
VALUES (md5('qa50:bin:N')::uuid, md5('qa50:level')::uuid, md5('qa50:loc:N')::uuid, 'N', 0, 0, 0.55, 0.55, 0, 'STANDARD'),
       (md5('qa50:bin:T')::uuid, md5('qa50:level')::uuid, md5('qa50:loc:T')::uuid, 'T', 1, 0, 0.6, 0.5, 0, 'STANDARD');
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

\echo '--- storage class and unit capacity are gone (06, 2026-11-05; BR-19)'
SELECT pg_temp.expect_true('BV1 no storage-class column is left anywhere',
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE (table_schema, table_name, column_name) IN
                       (('warehouse', 'storage_location', 'storage_class'), ('warehouse', 'shelf', 'default_storage_class'),
                        ('warehouse', 'bin', 'storage_class_override'), ('inventory', 'inventory_items', 'storage_class'))));
SELECT pg_temp.expect_true('BV2 no unit capacity, no carrier flags on the item',
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE (table_schema, table_name, column_name) IN
                       (('warehouse', 'storage_location', 'capacity_units'),
                        ('inventory', 'inventory_items', 'requires_adult_signature'),
                        ('inventory', 'inventory_items', 'shipping_restriction_note'))));
SELECT pg_temp.expect_true('BV3 what was dropped is archived: every location, every shelf, every item',
    (SELECT count(*) FROM platform.legacy_archive WHERE source_table = 'warehouse.storage_location'
                                                    AND row_data ? 'storage_class') >= 6
    AND (SELECT count(*) FROM platform.legacy_archive WHERE source_table = 'warehouse.shelf'
                                                        AND row_data ? 'default_storage_class') >= 3
    AND (SELECT count(*) FROM platform.legacy_archive WHERE source_table = 'inventory.inventory_items'
                                                        AND row_data ? 'requires_adult_signature') >= 2);
SELECT pg_temp.expect_fail('BV4 a location can no longer be given a storage class', $q$
    UPDATE warehouse.storage_location SET storage_class = 'COLD' WHERE location_code = 'HCM-A01-1-A' $q$);
SELECT pg_temp.expect_true('BV5 the putaway index no longer names a storage class',
    (SELECT indexdef FROM pg_indexes WHERE schemaname = 'warehouse' AND indexname = 'ix_storage_location_putaway')
        NOT LIKE '%storage_class%');

\echo '--- a level carries its clear height (06 BR-19)'
SELECT pg_temp.expect_fail('BV6 a new level without a clear height', $q$
    INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
    VALUES (gen_random_uuid(), md5('demo:shelf:HCM-B01')::uuid, 6, 4, NULL, 500) $q$);
SELECT pg_temp.expect_fail('BV7 an edit that clears a level''s clear height', $q$
    UPDATE warehouse.shelf_level SET usable_height = NULL WHERE id = md5('demo:level:HCM-B01-1')::uuid $q$);
SELECT pg_temp.expect_true('BV8 the check is NOT VALID: a legacy level without one may stay',
    (SELECT NOT convalidated FROM pg_constraint WHERE conname = 'ck_shelf_level_usable_height_required'));
SELECT pg_temp.expect_true('BV9 the volume is computed from the map: 5 x 1.2 x 1.4 = 8.4 m³ for HCM-A01-1-A',
    (SELECT b.width * b.length * l.usable_height FROM warehouse.bin b JOIN warehouse.shelf_level l ON l.id = b.level_id
      WHERE b.location_id = md5('demo:loc:HCM-A01-1-A')::uuid) = 8.4);

\echo '--- a carton fits the bin it goes into (06 BR-01, 11 BR-10; tg_stock_package_fits_bin)'
SELECT pg_temp.expect_fail('BV10 a carton written into a bin too narrow for it either way', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA50-N', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa50:unit:sofa')::uuid, 1,
            'HCM-B01-5-N') $q$);
SELECT pg_temp.expect_ok('BV11 a rotatable carton turned on the horizontal fits T (0.6 wide x 0.5 long)', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (md5('qa50:pk:sofa')::uuid, 'QA50-T', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa50:unit:sofa')::uuid, 1,
            'HCM-RCV01');
    UPDATE inventory.stock_package SET location_code = 'HCM-B01-5-T' WHERE id = md5('qa50:pk:sofa')::uuid $q$);
SELECT pg_temp.expect_fail('BV12 a carton that may not be turned does not fit T', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA50-X', md5('demo:item:TABLE-OAK-160')::uuid, md5('qa50:unit:table')::uuid, 1,
            'HCM-B01-5-T') $q$);
SELECT pg_temp.expect_fail('BV13 a carton taller than the level''s clear height (0.45 m) does not fit', $q$
    UPDATE inventory.uom_conversions SET height_cm = 46 WHERE id = md5('qa50:unit:sofa')::uuid;
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA50-H', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa50:unit:sofa')::uuid, 1,
            'HCM-B01-5-T') $q$);
SELECT pg_temp.expect_ok('BV14 any carton goes into an area: size is not checked there', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA50-A', md5('demo:item:TABLE-OAK-160')::uuid, md5('qa50:unit:table')::uuid, 1,
            'HCM-RCV01') $q$);
SELECT pg_temp.expect_ok('BV15 a carton nobody measured is not judged (the demo carton unit has no size)', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA50-U', md5('demo:item:SOFA-3S-GREY')::uuid,
            md5('demo:unit:SOFA-3S-GREY:CARTON')::uuid, 1, 'HCM-B01-5-N') $q$);
SELECT pg_temp.expect_ok('BV16 a carton already standing in a bin is not judged again until it moves', $q$
    UPDATE inventory.uom_conversions SET height_cm = 46 WHERE id = md5('qa50:unit:sofa')::uuid;
    UPDATE inventory.stock_package SET last_modified_by = 'qa50' WHERE id = md5('qa50:pk:sofa')::uuid;
    UPDATE inventory.uom_conversions SET height_cm = 40 WHERE id = md5('qa50:unit:sofa')::uuid $q$);
\echo '--- boundaries: exactly equal fits, a millimetre over does not'
SELECT pg_temp.expect_ok('BV21 a carton exactly the bin''s width, length and clear height fits (table, not turnable)', $q$
    INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
    VALUES (md5('qa50:level:E')::uuid, md5('demo:shelf:HCM-B01')::uuid, 7, 5, 0.40, 500);
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, max_weight, is_pickable,
                                            is_putaway_target, status)
    VALUES (md5('qa50:loc:E')::uuid, md5('demo:wh:HCM')::uuid, 'BIN', 'HCM-B01-7-E', NULL, TRUE, TRUE, 'ACTIVE');
    INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
    VALUES (md5('qa50:bin:E')::uuid, md5('qa50:level:E')::uuid, md5('qa50:loc:E')::uuid, 'E', 0, 0, 0.5, 0.6, 0,
            'STANDARD');
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA50-EQ', md5('demo:item:TABLE-OAK-160')::uuid, md5('qa50:unit:table')::uuid, 1,
            'HCM-B01-7-E') $q$);
SELECT pg_temp.expect_fail('BV22 a millimetre longer than the bin does not fit', $q$
    UPDATE inventory.uom_conversions SET length_cm = 60.1 WHERE id = md5('qa50:unit:table')::uuid;
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA50-L1', md5('demo:item:TABLE-OAK-160')::uuid, md5('qa50:unit:table')::uuid, 1,
            'HCM-B01-7-E') $q$);
SELECT pg_temp.expect_fail('BV23 a millimetre wider than the bin does not fit', $q$
    UPDATE inventory.uom_conversions SET width_cm = 50.1 WHERE id = md5('qa50:unit:table')::uuid;
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA50-W1', md5('demo:item:TABLE-OAK-160')::uuid, md5('qa50:unit:table')::uuid, 1,
            'HCM-B01-7-E') $q$);
SELECT pg_temp.expect_fail('BV24 a millimetre taller than the clear height does not fit', $q$
    UPDATE inventory.uom_conversions SET height_cm = 40.1 WHERE id = md5('qa50:unit:table')::uuid;
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA50-H1', md5('demo:item:TABLE-OAK-160')::uuid, md5('qa50:unit:table')::uuid, 1,
            'HCM-B01-7-E') $q$);

\echo '--- a legacy level without a clear height (written before V20261112000100)'
SELECT pg_temp.expect_ok('BV25 fixture: a legacy level without a clear height, written while the check was off', $q$
    ALTER TABLE warehouse.shelf_level DROP CONSTRAINT ck_shelf_level_usable_height_required;
    INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
    VALUES (md5('qa50:level:legacy')::uuid, md5('demo:shelf:HCM-B01')::uuid, 8, 6, NULL, 500);
    ALTER TABLE warehouse.shelf_level ADD CONSTRAINT ck_shelf_level_usable_height_required
        CHECK (usable_height IS NOT NULL) NOT VALID $q$);
SELECT pg_temp.expect_fail('BV26 any edit of the legacy level that leaves it without a clear height is refused', $q$
    UPDATE warehouse.shelf_level SET max_weight = 400 WHERE id = md5('qa50:level:legacy')::uuid $q$);
SELECT pg_temp.expect_ok('BV27 the same edit with a clear height is accepted', $q$
    UPDATE warehouse.shelf_level SET max_weight = 400, usable_height = 1.2 WHERE id = md5('qa50:level:legacy')::uuid $q$);

SELECT pg_temp.expect_ok('BV17 a level without a clear height does not limit the height (legacy data)', $q$
    ALTER TABLE warehouse.shelf_level DROP CONSTRAINT ck_shelf_level_usable_height_required;
    UPDATE warehouse.shelf_level SET usable_height = NULL WHERE id = md5('qa50:level')::uuid;
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA50-L', md5('demo:item:SOFA-3S-GREY')::uuid, md5('qa50:unit:sofa')::uuid, 1,
            'HCM-B01-5-T') $q$);

\echo '--- OVERFLOW (05 BR-13)'
SELECT pg_temp.expect_true('BV18 OVERFLOW is an area type a storage area may have',
    (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_area_type') LIKE '%OVERFLOW%');
SELECT pg_temp.expect_true('BV19 the demo HCM has an OVERFLOW area with a location, ACTIVE, not a putaway target',
    EXISTS (SELECT 1 FROM warehouse.area a JOIN warehouse.storage_location l ON l.id = a.location_id
             WHERE a.warehouse_id = md5('demo:wh:HCM')::uuid AND a.type = 'OVERFLOW' AND l.location_code = 'HCM-OVF01'
               AND l.status = 'ACTIVE' AND NOT l.is_putaway_target));
SELECT pg_temp.expect_ok('BV20 a carton that fits no bin goes into OVERFLOW', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA50-O', md5('demo:item:TABLE-OAK-160')::uuid, md5('qa50:unit:table')::uuid, 1,
            'HCM-OVF01') $q$);

ROLLBACK;
\echo 'PASS 50_bin_volume_and_fit'
