-- =============================================================================
-- Adversarial checks: the carton belongs to the catalogue variant (V20261231000100, owner decision Q1 of 2026-11-26)
-- and whether it may be turned is the variant's too (V20261231000200, Q9). A customer's SKU is packed in its source
-- variant's carton: it has none of its own, every check that compared a carton's item with a line's or a package's item
-- now compares variants, and a retired carton still takes the goods of the order line signed in it (Q10). Needs the
-- demo seed (the demo cup LY-GIAY-DEMO-12OZ and its customers' SKUs -XANH, 1,000 a carton, and -DO, 500 a carton before
-- the migration). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
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

CREATE FUNCTION pg_temp.expect_fail_like(label TEXT, fragment TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        IF position(fragment IN SQLERRM) = 0 THEN
            RAISE EXCEPTION 'FAIL  %: refused, but for another reason: %', label, SQLERRM;
        END IF;
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

\echo '--- the shape: cartons keyed by variant, items name their variant, rotatable is the variant''s'
SELECT pg_temp.expect_true('CV1 uom_conversions has variant_id and no inventory_item_id',
    EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema = 'inventory' AND table_name = 'uom_conversions' AND column_name = 'variant_id'
               AND is_nullable = 'NO')
    AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                     WHERE table_schema = 'inventory' AND table_name = 'uom_conversions'
                       AND column_name = 'inventory_item_id'));
SELECT pg_temp.expect_true('CV2 every item names its variant; rotatable left the item for the variant',
    EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema = 'inventory' AND table_name = 'inventory_items' AND column_name = 'variant_id'
               AND is_nullable = 'NO')
    AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                     WHERE table_schema = 'inventory' AND table_name = 'inventory_items' AND column_name = 'rotatable')
    AND EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'product' AND table_name = 'variants' AND column_name = 'rotatable'
                   AND is_nullable = 'NO'));
SELECT pg_temp.expect_true('CV3 no function body still reads a carton row''s item (a body naming a dropped column fails at its next call)',
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                   AND p.prosrc ~ 'uom_conversions'
                   AND p.prosrc ~ '\munit\.inventory_item_id\M'));
SELECT pg_temp.expect_true('CV4 no function body reads rotatable off an item',
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                   AND p.prosrc ~ '\mi\.rotatable\M'));
SELECT pg_temp.expect_true('CV5 uk_uom_conversions_one_carton is unique on the variant, where active',
    (SELECT i.indisunique AND pg_get_expr(i.indpred, i.indrelid) = 'active'
            AND pg_get_indexdef(i.indexrelid) ~ '\(variant_id\)'
       FROM pg_index i WHERE i.indexrelid = 'inventory.uk_uom_conversions_one_carton'::regclass));

\echo '--- the data the migration left: every SKU reads its variant''s carton'
SELECT pg_temp.expect_true('CV6 an item''s variant is its SKU''s source variant, or the variant itself',
    NOT EXISTS (SELECT 1 FROM inventory.inventory_items i JOIN product.variants v ON v.sku = i.sku
                 WHERE i.variant_id IS DISTINCT FROM COALESCE(v.source_variant_id, v.id)));
SELECT pg_temp.expect_true('CV7 no carton hangs off a customer''s SKU',
    NOT EXISTS (SELECT 1 FROM inventory.uom_conversions u JOIN product.variants v ON v.id = u.variant_id
                 WHERE v.owner_customer_id IS NOT NULL));
SELECT pg_temp.expect_true('CV8 the demo cup has one active carton, of 1,000, and its blue and red SKUs both read it',
    (SELECT count(*) = 1 AND min(u.factor) = 1000
       FROM inventory.uom_conversions u JOIN product.variants v ON v.id = u.variant_id
      WHERE v.sku = 'LY-GIAY-DEMO-12OZ' AND u.active)
    AND (SELECT count(DISTINCT u.id) = 1
           FROM inventory.inventory_items i JOIN inventory.uom_conversions u ON u.variant_id = i.variant_id AND u.active
          WHERE i.sku IN ('LY-GIAY-DEMO-12OZ-XANH', 'LY-GIAY-DEMO-12OZ-DO')));
SELECT pg_temp.expect_true('CV9 the red cup''s carton of 500 is kept, retired, under a code of its own: its cartons in stock count in it',
    (SELECT NOT u.active AND u.factor = 500 AND u.unit_of_measure <> 'THUNG' AND inventory.unit_in_use(u.id)
       FROM inventory.uom_conversions u WHERE u.id = md5('demo:cups:unit:LY-GIAY-DEMO-12OZ-DO')::uuid));

\echo '--- a carton is declared on a catalogue variant only, one active, codes unique (01 BR-03)'
SELECT pg_temp.expect_fail_like('CV10 a carton declared on a customer''s SKU (tg_uom_conversions_catalogue_variant)',
    'has none of its own', $q$
    INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, weight_kg, length_cm, width_cm,
                                           height_cm, active)
    SELECT gen_random_uuid(), v.id, 'QA47OWN', 1000, 8.6, 60, 40, 50, FALSE
      FROM product.variants v WHERE v.sku = 'LY-GIAY-DEMO-12OZ-XANH' $q$);
SELECT pg_temp.expect_fail_like('CV11 a second active carton on the demo cup', 'uk_uom_conversions_one_carton', $q$
    INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, weight_kg, length_cm, width_cm,
                                           height_cm, active)
    SELECT gen_random_uuid(), v.id, 'QA47TWO', 500, 5, 50, 40, 30, TRUE
      FROM product.variants v WHERE v.sku = 'LY-GIAY-DEMO-12OZ' $q$);
SELECT pg_temp.expect_fail_like('CV12 a retired carton under the active one''s code', 'uk_uom_conversions_unit', $q$
    INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, weight_kg, length_cm, width_cm,
                                           height_cm, active)
    SELECT gen_random_uuid(), u.variant_id, u.unit_of_measure, 500, 5, 50, 40, 30, FALSE
      FROM inventory.uom_conversions u JOIN product.variants v ON v.id = u.variant_id
     WHERE v.sku = 'LY-GIAY-DEMO-12OZ' AND u.active $q$);
SELECT pg_temp.expect_fail_like('CV13 a carton called what the pieces of the variant''s SKUs are counted in',
    'the unit the pieces', $q$
    INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, weight_kg, length_cm, width_cm,
                                           height_cm, active)
    SELECT gen_random_uuid(), v.id, 'EACH', 500, 5, 50, 40, 30, FALSE
      FROM product.variants v WHERE v.sku = 'LY-GIAY-DEMO-12OZ' $q$);
SELECT pg_temp.expect_fail_like('CV14 a SKU of the demo cup counted in what its carton is called',
    'is already called', $q$
    UPDATE inventory.inventory_items
       SET unit_of_measure = (SELECT u.unit_of_measure FROM inventory.uom_conversions u
                                JOIN product.variants v ON v.id = u.variant_id
                               WHERE v.sku = 'LY-GIAY-DEMO-12OZ' AND u.active)
     WHERE sku = 'LY-GIAY-DEMO-12OZ-DO' $q$);

\echo '--- a carton a document counts in keeps its variant, count and code (BR-10); its size stays editable (Q8)'
SELECT pg_temp.expect_fail('CV15 the demo cup''s carton moved to another variant', $q$
    UPDATE inventory.uom_conversions
       SET variant_id = (SELECT id FROM product.variants WHERE sku = 'HOP-GIAY-DEMO-GREY-PAPER')
     WHERE active AND variant_id = (SELECT id FROM product.variants WHERE sku = 'LY-GIAY-DEMO-12OZ') $q$);
SELECT pg_temp.expect_fail('CV16 its count changed', $q$
    UPDATE inventory.uom_conversions SET factor = 999
     WHERE active AND variant_id = (SELECT id FROM product.variants WHERE sku = 'LY-GIAY-DEMO-12OZ') $q$);
SELECT pg_temp.expect_fail('CV17 its code changed', $q$
    UPDATE inventory.uom_conversions SET unit_of_measure = 'QA47RENAMED'
     WHERE active AND variant_id = (SELECT id FROM product.variants WHERE sku = 'LY-GIAY-DEMO-12OZ') $q$);
SELECT pg_temp.expect_ok('CV18 measured again: one row, and every SKU of the cup reads the new size', $q$
    UPDATE inventory.uom_conversions SET length_cm = 50, width_cm = 40, height_cm = 45, weight_kg = 9.2
     WHERE active AND variant_id = (SELECT id FROM product.variants WHERE sku = 'LY-GIAY-DEMO-12OZ') $q$);
SELECT pg_temp.expect_true('CV19 ... the blue and red SKUs both see 50 x 40 x 45, 9.2 kg',
    (SELECT bool_and(u.length_cm = 50 AND u.width_cm = 40 AND u.height_cm = 45 AND u.weight_kg = 9.2) AND count(*) = 2
       FROM inventory.inventory_items i JOIN inventory.uom_conversions u ON u.variant_id = i.variant_id AND u.active
      WHERE i.sku IN ('LY-GIAY-DEMO-12OZ-XANH', 'LY-GIAY-DEMO-12OZ-DO')));

\echo '--- an item''s variant is fixed and set with it'
SELECT pg_temp.expect_fail_like('CV20 the blue cup''s item moved to another variant', 'never changes', $q$
    UPDATE inventory.inventory_items SET variant_id = (SELECT id FROM product.variants WHERE sku = 'HOP-GIAY-DEMO-GREY-PAPER')
     WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH' $q$);
INSERT INTO product.products (id, code, name, slug, status, kind, version, created_at)
VALUES (md5('qa47:product')::uuid, 'QA47', 'QA 47', 'qa-47', 'DRAFT', 'CUSTOMIZABLE', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa47:a')::uuid, md5('qa47:product')::uuid, 'QA47-A', 'QA 47 A', 'ACTIVE', TRUE, 'DEFAULT', 0, 0, NOW()),
       (md5('qa47:b')::uuid, md5('qa47:product')::uuid, 'QA47-B', 'QA 47 B', 'ACTIVE', FALSE, 'QA47-B', 1, 0, NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa47:draft')::uuid, c.id, md5('qa47:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa47:snap')::uuid, md5('qa47:draft')::uuid, repeat('e', 64), 'https://example.com/e.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa47:own')::uuid, md5('qa47:product')::uuid, 'QA47-OWN', 'QA 47 in logo', 'ACTIVE', FALSE,
       'OWNED-SKU:QA47-OWN', 2, 0, NOW(), c.id, md5('qa47:snap')::uuid, md5('qa47:a')::uuid
  FROM customer.customer c ORDER BY c.id LIMIT 1;
SELECT pg_temp.expect_true('CV21 a catalogue variant''s item names the variant; a customer''s SKU''s item names its source',
    (SELECT variant_id = md5('qa47:a')::uuid FROM inventory.inventory_items WHERE sku = 'QA47-A')
    AND (SELECT variant_id = md5('qa47:a')::uuid FROM inventory.inventory_items WHERE sku = 'QA47-OWN'));

\echo '--- a quote line of a customer''s SKU counts in its variant''s carton, and no other (Q1, 14 BR-04)'
INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, weight_kg, length_cm, width_cm,
                                       height_cm, active)
VALUES (md5('qa47:carton:a')::uuid, md5('qa47:a')::uuid, 'THUNG', 50, 4, 40, 30, 30, TRUE),
       (md5('qa47:carton:b')::uuid, md5('qa47:b')::uuid, 'THUNG', 50, 4, 40, 30, 30, TRUE);
INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, valid_until)
SELECT md5('qa47:quote')::uuid, 'QT-QA-47', gen_random_uuid(), c.id, 'DRAFT', NOW() + interval '7 days'
  FROM customer.customer c ORDER BY c.id LIMIT 1;
SELECT pg_temp.expect_fail_like('CV22 the customer''s SKU quoted in another variant''s carton', 'is not the carton of', $q$
    INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, units_per_carton)
    VALUES (gen_random_uuid(), md5('qa47:quote')::uuid, 1, 'QA47-OWN', md5('qa47:carton:b')::uuid, 20, 50) $q$);
SELECT pg_temp.expect_ok('CV23 ... and in its variant''s carton', $q$
    INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, units_per_carton)
    VALUES (md5('qa47:qline')::uuid, md5('qa47:quote')::uuid, 1, 'QA47-OWN', md5('qa47:carton:a')::uuid, 20, 50) $q$);
SELECT pg_temp.expect_fail('CV24 a catalogue variant quoted in its own carton: still only a customer''s own SKU (14 BR-04)', $q$
    INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, units_per_carton)
    VALUES (gen_random_uuid(), md5('qa47:quote')::uuid, 2, 'QA47-A', md5('qa47:carton:a')::uuid, 20, 50) $q$);
SELECT pg_temp.expect_fail_like('CV25 a new quote line in the variant''s carton once retired for another', 'is retired', $q$
    UPDATE inventory.uom_conversions SET active = FALSE WHERE id = md5('qa47:carton:a')::uuid;
    INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, weight_kg, length_cm, width_cm,
                                           height_cm, active)
    VALUES (md5('qa47:carton:a2')::uuid, md5('qa47:a')::uuid, 'THUNG2', 25, 2, 40, 30, 15, TRUE);
    INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, valid_until)
    SELECT md5('qa47:quote2')::uuid, 'QT-QA-47B', gen_random_uuid(), c.id, 'DRAFT', NOW() + interval '7 days'
      FROM customer.customer c ORDER BY c.id LIMIT 1;
    INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, units_per_carton)
    VALUES (gen_random_uuid(), md5('qa47:quote2')::uuid, 1, 'QA47-OWN', md5('qa47:carton:a')::uuid, 20, 50) $q$);

\echo '--- a stock package is packed in its SKU''s variant''s carton'
SELECT pg_temp.expect_fail('CV26 a blue cup carton in the grey paper box''s carton', $q$
    INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, weight_kg, length_cm, width_cm,
                                           height_cm, active)
    SELECT md5('qa47:grey')::uuid, id, 'QA47GREY', 1000, 5, 50, 40, 30, TRUE
      FROM product.variants WHERE sku = 'HOP-GIAY-DEMO-GREY-PAPER';
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    VALUES (gen_random_uuid(), 'QA47-LPN-1', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
            md5('qa47:grey')::uuid, 1000, 'HCM-RCV01') $q$);
SELECT pg_temp.expect_ok('CV27 a red cup carton in the demo cup''s carton: the red SKU has no carton of its own any more', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code)
    SELECT gen_random_uuid(), 'QA47-LPN-2', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-DO'),
           u.id, u.factor, 'HCM-RCV01'
      FROM inventory.uom_conversions u JOIN product.variants v ON v.id = u.variant_id
     WHERE v.sku = 'LY-GIAY-DEMO-12OZ' AND u.active $q$);

\echo '--- Q10: a retired carton still takes the goods of the order line signed in it, and nothing else'
-- The blue cup changes carton: OLD (10 a carton) is what two order lines were signed in before it was retired; NEW (also
-- 10 a carton, another size) is the one in use now, and a third order line was signed in it. A production order makes
-- each line. Its rows are written with the row triggers off, as 15_stock_packages.sql and 61_purchasing_removal.sql do:
-- the production order's own rules are 21_production_orders.sql's.
UPDATE inventory.uom_conversions SET active = FALSE
 WHERE active AND variant_id = (SELECT variant_id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH');
INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, length_cm, width_cm, height_cm,
                                       weight_kg, active)
VALUES (md5('qa47:old')::uuid, (SELECT variant_id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
        'QA47OLD', 10, 60, 40, 50, 12, FALSE),
       (md5('qa47:new')::uuid, (SELECT variant_id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
        'QA47NEW', 10, 50, 40, 45, 12, TRUE);
SET LOCAL session_replication_role = replica;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                 units_per_carton)
VALUES (md5('qa47:ol:old')::uuid, md5('qa47:so')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 50, 2000, 'VND',
        md5('qa47:old')::uuid, 5, 10),
       (md5('qa47:ol:new')::uuid, md5('qa47:so')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 50, 2000, 'VND',
        md5('qa47:new')::uuid, 5, 10);
INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id,
                                       receiving_warehouse_id, need_by_date, status, sample_approval_required,
                                       sample_required_by_default, drawn_up_by, sent_by, sent_at)
VALUES (md5('qa47:prod')::uuid, 'PRD-20261231-4701', md5('qa47:so')::uuid, md5('qa47:batch')::uuid,
        md5('qa47:cust')::uuid, md5('qa47:producer')::uuid, md5('demo:wh:HCM')::uuid, CURRENT_DATE + 30, 'SENT', FALSE,
        FALSE, md5('demo:user:editor')::uuid, md5('demo:user:editor')::uuid, NOW());
INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                            design_snapshot_id, print_file_checksum)
VALUES (md5('qa47:pl:old')::uuid, md5('qa47:prod')::uuid, md5('qa47:ol:old')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 5, 10,
        md5('qa47:snap')::uuid, repeat('d', 64)),
       (md5('qa47:pl:new')::uuid, md5('qa47:prod')::uuid, md5('qa47:ol:new')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 5, 10,
        md5('qa47:snap')::uuid, repeat('d', 64));
SET LOCAL session_replication_role = origin;
INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, production_order_id, warehouse_id, status,
                                        received_at, received_by)
VALUES (md5('qa47:gr')::uuid, 'GR-QA47', 'PRODUCTION', md5('qa47:prod')::uuid, md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(),
        md5('demo:user:editor')::uuid);
INSERT INTO ordering.production_order_receipt (production_order_id, goods_receipt_id, receipt_number, status, opened_at)
VALUES (md5('qa47:prod')::uuid, md5('qa47:gr')::uuid, 'GR-QA47', 'DRAFT', NOW());
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;
SELECT pg_temp.expect_fail_like('CV28 the line signed in the new carton received in the retired one', 'is retired', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id, location_id,
                                                 received_qty, lot_number, uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa47:gr')::uuid, md5('qa47:pl:new')::uuid,
            (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
            md5('demo:loc:HCM-RCV01')::uuid, 10, 'PRD-20261231-4701', md5('qa47:old')::uuid) $q$);
SELECT pg_temp.expect_ok('CV29 the line signed in the retired carton received in it (Q10)', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id, location_id,
                                                 received_qty, lot_number, uom_conversion_id)
    VALUES (md5('qa47:grl:old')::uuid, md5('qa47:gr')::uuid, md5('qa47:pl:old')::uuid,
            (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
            md5('demo:loc:HCM-RCV01')::uuid, 10, 'PRD-20261231-4701', md5('qa47:old')::uuid) $q$);
SELECT pg_temp.expect_ok('CV30 ... and its carton labelled in the retired carton, as the receipt line counts it', $q$
    INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, location_code,
                                         goods_receipt_line_id)
    VALUES (gen_random_uuid(), 'QA47-LPN-OLD', (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
            md5('qa47:old')::uuid, 10, 'HCM-RCV01', md5('qa47:grl:old')::uuid) $q$);
SELECT pg_temp.expect_ok('CV31 the line signed in the new carton received in it', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id, location_id,
                                                 received_qty, lot_number, uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa47:gr')::uuid, md5('qa47:pl:new')::uuid,
            (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
            md5('demo:loc:HCM-RCV01')::uuid, 10, 'PRD-20261231-4701', md5('qa47:new')::uuid) $q$);
SELECT pg_temp.expect_fail_like('CV32 a received line moved onto the retired carton it was not signed in', 'is retired', $q$
    UPDATE procurement.goods_receipt_lines SET uom_conversion_id = md5('qa47:old')::uuid
     WHERE production_order_line_id = md5('qa47:pl:new')::uuid $q$);

\echo '--- whether a carton may be turned is read off its variant (Q9)'
SELECT pg_temp.expect_true('CV33 check_stock_package_fits_bin reads product.variants.rotatable',
    (SELECT p.prosrc ~ 'product\.variants' AND p.prosrc ~ '\mv\.rotatable\M'
       FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
      WHERE n.nspname = 'inventory' AND p.proname = 'check_stock_package_fits_bin'));
-- The fit itself, turned and not, is 50_bin_volume_and_fit.sql's (BV9b to BV21).
ROLLBACK;
\echo 'PASS 47_carton_per_variant'
