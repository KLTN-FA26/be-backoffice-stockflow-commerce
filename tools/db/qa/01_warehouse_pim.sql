-- =============================================================================
-- Adversarial checks: warehouse map + product information management.
-- Needs the demo seed (local profile database). Everything runs in one transaction that is rolled
-- back at the end, so it leaves no data behind. Run: tools/db/qa/run.sh <database>
--
-- Each "expect_fail" is a hole a QA round found, or a rule a migration claims to enforce: if the
-- database accepts the statement, the rule is not there and the script stops with FAIL.
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

-- A second warehouse, to try crossing warehouses.
SELECT pg_temp.qa_warehouse(md5('qa:wh:HN')::uuid, 'HN', 'M', 30, 20);
INSERT INTO warehouse.zone (id, warehouse_id, name) VALUES (md5('qa:zone:HN')::uuid, md5('qa:wh:HN')::uuid, 'Khu HN');

\echo '--- warehouse map'
SELECT pg_temp.expect_ok('W0 a correct new bin (location first, owned by commit)', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
    VALUES (md5('qa:loc:C')::uuid, md5('demo:wh:HCM')::uuid, 'BIN', 'HCM-A01-1-C', 'OVERSIZE', TRUE, TRUE, 'ACTIVE');
    INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
    VALUES (md5('qa:bin:C')::uuid, md5('demo:level:HCM-A01-1')::uuid, md5('qa:loc:C')::uuid, 'C', 0, 0, 1, 1, 0, 'STANDARD') $q$);
SELECT pg_temp.expect_fail('W1 bin location code in area format', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
    VALUES (gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'BIN', 'HCM-A01', 'NORMAL', TRUE, TRUE, 'ACTIVE') $q$);
SELECT pg_temp.expect_fail('W2 old-style code HCM-A-01-02-B', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
    VALUES (gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'BIN', 'HCM-A-01-02-B', 'NORMAL', TRUE, TRUE, 'ACTIVE') $q$);
SELECT pg_temp.expect_fail('W3 bin pointing at an AREA location', $q$
    INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
    VALUES (gen_random_uuid(), md5('demo:level:HCM-A01-1')::uuid, md5('demo:loc:HCM-QC01')::uuid, 'Z', 0, 0, 1, 1, 0, 'STANDARD') $q$);
SELECT pg_temp.expect_fail('W4 bin whose location is in another warehouse', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
    VALUES (md5('qa:loc:hn')::uuid, md5('qa:wh:HN')::uuid, 'BIN', 'HCM-A01-1-D', 'NORMAL', TRUE, TRUE, 'ACTIVE');
    INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
    VALUES (gen_random_uuid(), md5('demo:level:HCM-A01-1')::uuid, md5('qa:loc:hn')::uuid, 'D', 0, 0, 1, 1, 0, 'STANDARD') $q$);
SELECT pg_temp.expect_fail('W5 bin code that does not assemble into its location code', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
    VALUES (md5('qa:loc:e')::uuid, md5('demo:wh:HCM')::uuid, 'BIN', 'HCM-A01-1-E', 'NORMAL', TRUE, TRUE, 'ACTIVE');
    INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
    VALUES (gen_random_uuid(), md5('demo:level:HCM-A01-1')::uuid, md5('qa:loc:e')::uuid, 'F', 0, 0, 1, 1, 0, 'STANDARD') $q$);
SELECT pg_temp.expect_fail('W6 storage location owned by nobody at commit', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
    VALUES (gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-LOST01', 'NORMAL', FALSE, FALSE, 'ACTIVE') $q$);
SELECT pg_temp.expect_fail('W7 warehouse prefix changed (BR-13)',
    $q$ UPDATE warehouse.warehouse SET prefix = 'SGN' WHERE id = md5('demo:wh:HCM')::uuid $q$);
SELECT pg_temp.expect_fail('W8 location code changed (BR-13)',
    $q$ UPDATE warehouse.storage_location SET location_code = 'HCM-A01-2-Z' WHERE location_code = 'HCM-A01-2-B' $q$);
SELECT pg_temp.expect_fail('W9 level index renumbered (BR-13)',
    $q$ UPDATE warehouse.shelf_level SET level_index = 3 WHERE id = md5('demo:level:HCM-A01-2')::uuid $q$);
SELECT pg_temp.expect_fail('W10 NON_STORAGE area with a location', $q$
    UPDATE warehouse.area SET location_id = md5('demo:loc:HCM-QC01')::uuid WHERE code = 'OFFICE' $q$);
SELECT pg_temp.expect_fail('W11 storage area without a location', $q$
    INSERT INTO warehouse.area (id, warehouse_id, code, type, name, x, y, width, length, rotation, is_obstacle, status)
    VALUES (gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'OVF01', 'OVERFLOW', 'x', 1, 1, 1, 1, 0, FALSE, 'ACTIVE') $q$);
SELECT pg_temp.expect_fail('W12 shelf in a zone of another warehouse', $q$
    INSERT INTO warehouse.shelf (id, warehouse_id, zone_id, code, name, x, y, width, length, rotation, is_obstacle,
        pick_north, pick_east, pick_south, pick_west, default_storage_class, status)
    VALUES (gen_random_uuid(), md5('demo:wh:HCM')::uuid, md5('qa:zone:HN')::uuid, 'Z99', 'x', 1, 1, 1, 1, 0, TRUE,
        TRUE, FALSE, FALSE, FALSE, 'NORMAL', 'ACTIVE') $q$);
SELECT pg_temp.expect_fail('W13 door without open/closed state', $q$
    INSERT INTO warehouse.boundary (id, warehouse_id, type, start_x, start_y, end_x, end_y, is_passable)
    VALUES (gen_random_uuid(), md5('demo:wh:HCM')::uuid, 'DOOR', 0, 0, 0, 5, TRUE) $q$);
SELECT pg_temp.expect_fail('W14 rotation of 45 degrees',
    $q$ UPDATE warehouse.shelf SET rotation = 45 WHERE code = 'A01' $q$);
SELECT pg_temp.expect_fail('W15 half-defined map frame', $q$
    SELECT pg_temp.qa_warehouse(gen_random_uuid(), 'DN', 'M', NULL, NULL) $q$);

SELECT pg_temp.expect_fail('W16 shelf in a warehouse that does not exist (D-W1)', $q$
    INSERT INTO warehouse.shelf (id, warehouse_id, code, name, x, y, width, length, rotation, is_obstacle,
        pick_north, pick_east, pick_south, pick_west, default_storage_class, status)
    VALUES (gen_random_uuid(), gen_random_uuid(), 'Z98', 'x', 1, 1, 1, 1, 0, TRUE,
        TRUE, FALSE, FALSE, FALSE, 'NORMAL', 'ACTIVE') $q$);
SELECT pg_temp.expect_fail('W17 storage location in a warehouse that does not exist (D-W1)', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable, is_putaway_target, status)
    VALUES (gen_random_uuid(), gen_random_uuid(), 'AREA', 'XX-GHOST01', 'NORMAL', FALSE, FALSE, 'ACTIVE') $q$);

\echo '--- product information management'
INSERT INTO product.attributes (id, code, name, data_type) VALUES (md5('qa:attr:NOTE')::uuid, 'NOTE', 'Ghi chú', 'TEXT');
INSERT INTO product.attribute_values (id, attribute_id, code, label, hex_color)
VALUES (md5('qa:val:BEIGE')::uuid, md5('demo:attr:COLOR')::uuid, 'BEIGE', 'Be', '#D8C8A8');

SELECT pg_temp.expect_fail('P1 variant axis on a TEXT attribute', $q$
    INSERT INTO product.product_attributes (id, product_id, attribute_id, role)
    VALUES (gen_random_uuid(), md5('demo:product:SOFA-3S')::uuid, md5('qa:attr:NOTE')::uuid, 'VARIANT_AXIS') $q$);
SELECT pg_temp.expect_fail('P2 a MATERIAL value offered on the COLOR axis', $q$
    INSERT INTO product.product_attribute_options (id, product_attribute_id, attribute_value_id)
    VALUES (gen_random_uuid(), md5('demo:pa:SOFA-3S:COLOR')::uuid, md5('demo:val:MATERIAL:OAK')::uuid) $q$);
SELECT pg_temp.expect_fail('P3 option offered on a DESCRIPTIVE attribute', $q$
    INSERT INTO product.product_attribute_options (id, product_attribute_id, attribute_value_id)
    VALUES (gen_random_uuid(), md5('demo:pa:SOFA-3S:MATERIAL')::uuid, md5('demo:val:MATERIAL:OAK')::uuid) $q$);
SELECT pg_temp.expect_fail('P4 variant takes an option of another product (QA case A1)', $q$
    INSERT INTO product.variant_attribute_values (id, variant_id, product_attribute_option_id)
    VALUES (gen_random_uuid(), md5('demo:variant:SOFA-3S-GREY')::uuid, md5('demo:po:TABLE-OAK:160')::uuid) $q$);
SELECT pg_temp.expect_fail('P5 variant takes two options on one axis', $q$
    INSERT INTO product.product_attribute_options (id, product_attribute_id, attribute_value_id)
    VALUES (md5('qa:po:beige')::uuid, md5('demo:pa:SOFA-3S:COLOR')::uuid, md5('qa:val:BEIGE')::uuid);
    INSERT INTO product.variant_attribute_values (id, variant_id, product_attribute_option_id)
    VALUES (gen_random_uuid(), md5('demo:variant:SOFA-3S-GREY')::uuid, md5('qa:po:beige')::uuid) $q$);
SELECT pg_temp.expect_fail('P6 a second value for a single SELECT attribute', $q$
    INSERT INTO product.product_descriptive_values (id, product_attribute_id, attribute_value_id)
    VALUES (gen_random_uuid(), md5('demo:pa:SOFA-3S:MATERIAL')::uuid, md5('demo:val:MATERIAL:OAK')::uuid) $q$);
SELECT pg_temp.expect_fail('P7 free text on a SELECT attribute', $q$
    DELETE FROM product.product_descriptive_values WHERE id = md5('demo:pd:SOFA-3S:FABRIC')::uuid;
    INSERT INTO product.product_descriptive_values (id, product_attribute_id, value_text)
    VALUES (gen_random_uuid(), md5('demo:pa:SOFA-3S:MATERIAL')::uuid, 'Vải') $q$);
SELECT pg_temp.expect_fail('P8 descriptive value with two value columns', $q$
    INSERT INTO product.product_descriptive_values (id, product_attribute_id, value_text, value_number)
    VALUES (gen_random_uuid(), md5('demo:pa:SOFA-3S:MATERIAL')::uuid, 'x', 1) $q$);
SELECT pg_temp.expect_fail('P9 approved by the submitter (BR-PRD-003)', $q$
    UPDATE product.products SET approved_by = submitted_by WHERE code = 'SOFA-3S' $q$);
SELECT pg_temp.expect_fail('P10 PUBLISHED without published_at', $q$
    UPDATE product.products SET published_at = NULL WHERE code = 'SOFA-3S' $q$);
SELECT pg_temp.expect_fail('P11 a second primary category', $q$
    INSERT INTO product.product_categories (id, product_id, category_id, is_primary)
    VALUES (gen_random_uuid(), md5('demo:product:SOFA-3S')::uuid, md5('demo:cat:LIVING')::uuid, TRUE) $q$);
SELECT pg_temp.expect_fail('P12 a second default variant', $q$
    INSERT INTO product.variants (id, product_id, sku, name, is_default, attribute_signature)
    VALUES (gen_random_uuid(), md5('demo:product:SOFA-3S')::uuid, 'SOFA-3S-BEIGE', 'x', TRUE, 'COLOR=BEIGE') $q$);
SELECT pg_temp.expect_fail('P13 two variants with the same combination', $q$
    INSERT INTO product.variants (id, product_id, sku, name, attribute_signature)
    VALUES (gen_random_uuid(), md5('demo:product:SOFA-3S')::uuid, 'SOFA-3S-GREY2', 'x', 'COLOR=GREY') $q$);
SELECT pg_temp.expect_fail('P14 sku renamed on an ACTIVE variant (docs 01 BR-01)', $q$
    UPDATE product.variants SET sku = 'SOFA-3S-GRAY' WHERE sku = 'SOFA-3S-GREY' $q$);
SELECT pg_temp.expect_ok('P15 sku renamed while still DRAFT', $q$
    INSERT INTO product.variants (id, product_id, sku, name, attribute_signature)
    VALUES (md5('qa:variant:draft')::uuid, md5('demo:product:SOFA-3S')::uuid, 'SOFA-3S-BEIGE', 'x', 'COLOR=BEIGE');
    UPDATE product.variants SET sku = 'SOFA-3S-BE' WHERE id = md5('qa:variant:draft')::uuid $q$);
SELECT pg_temp.expect_fail('P16 print template on a STANDARD product', $q$
    INSERT INTO product.customization_templates (id, variant_id, code, name, template_2d_url, allowed_file_formats, max_file_size_mb)
    VALUES (gen_random_uuid(), md5('demo:variant:SOFA-3S-GREY')::uuid, 'T1', 'x', 'https://x', ARRAY['PNG'], 10) $q$);
SELECT pg_temp.expect_fail('P17 category path not under its parent', $q$
    INSERT INTO product.categories (id, parent_id, code, name, slug, path, depth)
    VALUES (gen_random_uuid(), md5('demo:cat:LIVING')::uuid, 'CHAIR', 'Ghế', 'ghe', '/DINING/CHAIR', 1) $q$);
SELECT pg_temp.expect_fail('P18 category moved under its own child', $q$
    UPDATE product.categories SET parent_id = md5('demo:cat:SOFA')::uuid, depth = 2, path = '/LIVING/SOFA/LIVING'
     WHERE code = 'LIVING' $q$);
SELECT pg_temp.expect_fail('P19 stored media without its file metadata', $q$
    INSERT INTO product.media (id, variant_id, storage_key) VALUES (gen_random_uuid(), md5('demo:variant:SOFA-3S-GREY')::uuid, 'k/1.png') $q$);
SELECT pg_temp.expect_fail('P20 inventory item for a sku that is no variant', $q$
    INSERT INTO inventory.inventory_items (id, sku) VALUES (gen_random_uuid(), 'NO-SUCH-SKU') $q$);
SELECT pg_temp.expect_fail('P21 expiry tracked without lots', $q$
    UPDATE inventory.inventory_items SET lot_tracked = FALSE WHERE sku = 'TABLE-OAK-160' $q$);
SELECT pg_temp.expect_fail('P22 inventory item sku changed',
    $q$ UPDATE inventory.inventory_items SET sku = 'SOFA-3S-BE' WHERE sku = 'SOFA-3S-GREY' $q$);
SELECT pg_temp.expect_fail('P23 variant deleted while it has an inventory item',
    $q$ DELETE FROM product.variants WHERE sku = 'SOFA-3S-GREY' $q$);

ROLLBACK;
\echo 'PASS 01_warehouse_pim'
