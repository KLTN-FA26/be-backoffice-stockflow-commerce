-- =============================================================================
-- Adversarial checks: a customer's own SKU is its own table, product.customer_skus, and has an inventory item only once
-- an order names it (V20270105000100; owner decisions Q4, Q5 of 2026-11-26). The rows moved out of product.variants
-- with their ids and codes; every reference to a customer's SKU is a foreign key to the new table; a code is never both
-- a variant's and a customer's SKU's; what a SKU is never changes; and a catalogue variant never gets an item. Needs
-- the demo seed (the demo cup LY-GIAY-DEMO-12OZ and its customers' SKUs -XANH and -DO of one customer, -TSM of
-- another). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
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

\echo '--- the copy: the demo''s customer SKUs moved with their ids and codes, product.variants is the catalogue'
SELECT pg_temp.expect_true('CS1 the demo''s customer SKUs are rows of product.customer_skus, none of product.variants',
    (SELECT count(*) = 8 FROM product.customer_skus WHERE sku LIKE 'LY-GIAY-DEMO-12OZ-%')
    AND NOT EXISTS (SELECT 1 FROM product.variants WHERE sku LIKE 'LY-GIAY-DEMO-12OZ-%'));
SELECT pg_temp.expect_true('CS2 a moved SKU kept the id the demo seed gave its variant row, and its source variant',
    (SELECT c.id = md5('demo:wf02:variant:LY-GIAY-DEMO-12OZ-XANH')::uuid
            AND c.variant_id = (SELECT id FROM product.variants WHERE sku = 'LY-GIAY-DEMO-12OZ')
       FROM product.customer_skus c WHERE c.sku = 'LY-GIAY-DEMO-12OZ-XANH'));
SELECT pg_temp.expect_true('CS3 product.variants has no customer column left',
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'product' AND table_name = 'variants'
                   AND column_name IN ('owner_customer_id', 'design_snapshot_id', 'source_variant_id')));
SELECT pg_temp.expect_true('CS4 no function body still reads a dropped column or makes an item for a variant',
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                   AND p.prosrc ~ '(owner_customer_id|source_variant_id|create_variant_item)'));
SELECT pg_temp.expect_true('CS5 the triggers and functions that told a customer''s SKU from a variant are gone',
    NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname IN ('tg_variant_inventory_item', 'tg_variants_customer_sku',
                                                          'tg_variants_inventory_rename',
                                                          'tg_uom_conversions_catalogue_variant',
                                                          'tg_standard_price_variant'))
    AND NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                     WHERE (n.nspname, p.proname) IN (('inventory', 'create_variant_item'),
                                                      ('product', 'check_customer_sku'),
                                                      ('product', 'lock_inventory_sku_rename'),
                                                      ('inventory', 'check_carton_on_catalogue_variant'),
                                                      ('catalog', 'check_standard_price_variant'))));
SELECT pg_temp.expect_true('CS6 every reference to a customer''s SKU is a foreign key to product.customer_skus',
    (SELECT count(*) = 10 FROM pg_constraint
      WHERE contype = 'f' AND confrelid = 'product.customer_skus'::regclass
        AND conname IN ('fk_quote_line_customer_sku', 'fk_order_line_customer_sku', 'fk_quote_fee_line_sku',
                        'fk_order_fee_line_sku', 'fk_customer_price_customer_sku',
                        'fk_return_request_line_customer_sku', 'fk_goods_receipt_return_loose_lines_customer_sku',
                        'fk_product_sales_summary_customer_sku', 'fk_inventory_items_customer_sku',
                        'fk_conversation_context_customer_sku')));
SELECT pg_temp.expect_true('CS7 the chat context names a customer''s SKU by customer_sku_id; variant_id is gone',
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'chat'
             AND table_name = 'conversation_context' AND column_name = 'customer_sku_id')
    AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'chat'
                     AND table_name = 'conversation_context' AND column_name = 'variant_id'));

\echo '--- a code names one thing (tg_customer_skus_code_free, tg_variants_code_free)'
-- A product and a design of the demo customer for the rows below.
INSERT INTO product.products (id, code, name, slug, status, kind, version, created_at)
VALUES (md5('qa98:product')::uuid, 'QA98', 'QA 98', 'qa-98', 'DRAFT', 'CUSTOMIZABLE', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa98:blank')::uuid, md5('qa98:product')::uuid, 'QA98-BLANK', 'QA 98', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
VALUES (md5('qa98:draft')::uuid, 'c0000000-0000-4000-8000-000000000001', md5('qa98:product')::uuid, 'DRAFT', NOW(),
        '{}'::jsonb),
       (md5('qa98:draft2')::uuid, 'c0000000-0000-4000-8000-000000000001', md5('qa98:product')::uuid, 'DRAFT', NOW(),
        '{}'::jsonb);
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa98:snap')::uuid, md5('qa98:draft')::uuid, repeat('a', 64), 'https://example.com/a.pdf', NOW(), NOW(),
        '[]'::jsonb),
       (md5('qa98:snap2')::uuid, md5('qa98:draft2')::uuid, repeat('b', 64), 'https://example.com/b.pdf', NOW(), NOW(),
        '[]'::jsonb);
SELECT pg_temp.expect_fail_like('CS8 a customer''s SKU under a catalogue variant''s code', 'is a catalogue variant', $q$
    INSERT INTO product.customer_skus (id, product_id, variant_id, customer_id, design_snapshot_id, sku, name)
    VALUES (gen_random_uuid(), md5('qa98:product')::uuid, md5('qa98:blank')::uuid,
            'c0000000-0000-4000-8000-000000000001', md5('qa98:snap')::uuid, 'QA98-BLANK', 'x') $q$);
SELECT pg_temp.expect_ok('CS9 the customer''s SKU under a code of its own', $q$
    INSERT INTO product.customer_skus (id, product_id, variant_id, customer_id, design_snapshot_id, sku, name)
    VALUES (md5('qa98:own')::uuid, md5('qa98:product')::uuid, md5('qa98:blank')::uuid,
            'c0000000-0000-4000-8000-000000000001', md5('qa98:snap')::uuid, 'QA98-KH-01', 'QA 98 in logo') $q$);
SELECT pg_temp.expect_fail_like('CS10 a new variant under a customer''s SKU''s code', 'is a customer''s SKU', $q$
    INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position,
                                  version, created_at)
    VALUES (gen_random_uuid(), md5('qa98:product')::uuid, 'QA98-KH-01', 'x', 'DRAFT', FALSE, 'X', 1, 0, NOW()) $q$);
SELECT pg_temp.expect_fail_like('CS11 a DRAFT variant renamed to a customer''s SKU''s code', 'is a customer''s SKU', $q$
    UPDATE product.variants SET status = 'DRAFT' WHERE id = md5('qa98:blank')::uuid;
    UPDATE product.variants SET sku = 'LY-GIAY-DEMO-12OZ-XANH' WHERE id = md5('qa98:blank')::uuid $q$);
SELECT pg_temp.expect_fail_like('CS12 two customer''s SKUs under one code', 'uk_customer_skus_sku', $q$
    INSERT INTO product.customer_skus (id, product_id, variant_id, customer_id, design_snapshot_id, sku, name)
    VALUES (gen_random_uuid(), md5('qa98:product')::uuid, md5('qa98:blank')::uuid,
            'c0000000-0000-4000-8000-000000000001', md5('qa98:snap2')::uuid, 'QA98-KH-01', 'x') $q$);
SELECT pg_temp.expect_fail_like('CS13 two customer''s SKUs for one design snapshot', 'uk_customer_skus_design_snapshot', $q$
    INSERT INTO product.customer_skus (id, product_id, variant_id, customer_id, design_snapshot_id, sku, name)
    VALUES (gen_random_uuid(), md5('qa98:product')::uuid, md5('qa98:blank')::uuid,
            'c0000000-0000-4000-8000-000000000001', md5('qa98:snap')::uuid, 'QA98-KH-02', 'x') $q$);
SELECT pg_temp.expect_fail_like('CS14 a customer''s SKU drawn on a variant of another product', 'drawn on a variant of its own product', $q$
    INSERT INTO product.customer_skus (id, product_id, variant_id, customer_id, design_snapshot_id, sku, name)
    SELECT gen_random_uuid(), md5('qa98:product')::uuid, v.id, 'c0000000-0000-4000-8000-000000000001',
           md5('qa98:snap2')::uuid, 'QA98-KH-03', 'x'
      FROM product.variants v WHERE v.sku = 'LY-GIAY-DEMO-12OZ' $q$);
SELECT pg_temp.expect_fail_like('CS15 a code no SKU may hold', 'ck_customer_skus_sku', $q$
    INSERT INTO product.customer_skus (id, product_id, variant_id, customer_id, design_snapshot_id, sku, name)
    VALUES (gen_random_uuid(), md5('qa98:product')::uuid, md5('qa98:blank')::uuid,
            'c0000000-0000-4000-8000-000000000001', md5('qa98:snap2')::uuid, 'qa98 lower', 'x') $q$);

\echo '--- what a customer''s SKU is never changes; its status moves (ck_customer_skus_status, ck_customer_skus_obsoleted)'
SELECT pg_temp.expect_fail_like('CS16 its code changed', 'immutable', $q$
    UPDATE product.customer_skus SET sku = 'QA98-KH-99' WHERE id = md5('qa98:own')::uuid $q$);
SELECT pg_temp.expect_fail_like('CS17 its variant changed', 'immutable', $q$
    UPDATE product.customer_skus SET variant_id = (SELECT id FROM product.variants WHERE sku = 'LY-GIAY-DEMO-12OZ')
     WHERE id = md5('qa98:own')::uuid $q$);
SELECT pg_temp.expect_fail_like('CS18 its customer changed', 'immutable', $q$
    UPDATE product.customer_skus SET customer_id = (SELECT customer_id FROM product.customer_skus
                                                     WHERE sku = 'LY-GIAY-DEMO-12OZ-TSM')
     WHERE id = md5('qa98:own')::uuid $q$);
SELECT pg_temp.expect_fail_like('CS19 its design snapshot changed', 'immutable', $q$
    UPDATE product.customer_skus SET design_snapshot_id = md5('qa98:snap2')::uuid WHERE id = md5('qa98:own')::uuid $q$);
SELECT pg_temp.expect_fail_like('CS20 its product changed', 'immutable', $q$
    UPDATE product.customer_skus SET product_id = (SELECT product_id FROM product.variants WHERE sku = 'LY-GIAY-DEMO-12OZ')
     WHERE id = md5('qa98:own')::uuid $q$);
SELECT pg_temp.expect_fail_like('CS21 a DRAFT customer''s SKU (it is issued ACTIVE)', 'ck_customer_skus_status', $q$
    UPDATE product.customer_skus SET status = 'DRAFT' WHERE id = md5('qa98:own')::uuid $q$);
SELECT pg_temp.expect_fail_like('CS22 OBSOLETE without the time it was made so', 'ck_customer_skus_obsoleted', $q$
    UPDATE product.customer_skus SET status = 'OBSOLETE' WHERE id = md5('qa98:own')::uuid $q$);
SELECT pg_temp.expect_ok('CS23 blocked, renamed, active again: the name and the status move', $q$
    UPDATE product.customer_skus SET status = 'BLOCKED', name = 'QA 98 doi ten' WHERE id = md5('qa98:own')::uuid;
    UPDATE product.customer_skus SET status = 'ACTIVE' WHERE id = md5('qa98:own')::uuid $q$);

\echo '--- the customer''s own SKU, in quotes, orders and prices (14 BR-04, 13 BR-02)'
SELECT pg_temp.expect_fail_like('CS24 a quote line of another customer''s SKU', 'is not a SKU of customer', $q$
    INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, valid_until)
    VALUES (md5('qa98:quote')::uuid, 'QT-QA-98', gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'DRAFT',
            NOW() + interval '7 days');
    INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, units_per_carton)
    SELECT gen_random_uuid(), md5('qa98:quote')::uuid, 1, 'LY-GIAY-DEMO-12OZ-TSM', u.id, 2, u.factor
      FROM inventory.uom_conversions u JOIN product.variants v ON v.id = u.variant_id
     WHERE v.sku = 'LY-GIAY-DEMO-12OZ' AND u.active $q$);
SELECT pg_temp.expect_fail_like('CS25 a quote line of a catalogue variant: it is no customer''s SKU', 'fk_quote_line_customer_sku', $q$
    INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, valid_until)
    VALUES (md5('qa98:quote')::uuid, 'QT-QA-98', gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'DRAFT',
            NOW() + interval '7 days');
    ALTER TABLE ordering.quote_line DISABLE TRIGGER tg_quote_line_carton_unit;
    INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, units_per_carton)
    SELECT gen_random_uuid(), md5('qa98:quote')::uuid, 1, 'LY-GIAY-DEMO-12OZ', u.id, 2, u.factor
      FROM inventory.uom_conversions u JOIN product.variants v ON v.id = u.variant_id
     WHERE v.sku = 'LY-GIAY-DEMO-12OZ' AND u.active $q$);
SELECT pg_temp.expect_ok('CS26 a quote line of the customer''s own SKU in its variant''s carton', $q$
    INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, valid_until)
    VALUES (md5('qa98:quote')::uuid, 'QT-QA-98', gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'DRAFT',
            NOW() + interval '7 days');
    INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, units_per_carton)
    SELECT gen_random_uuid(), md5('qa98:quote')::uuid, 1, 'LY-GIAY-DEMO-12OZ-XANH', u.id, 2, u.factor
      FROM inventory.uom_conversions u JOIN product.variants v ON v.id = u.variant_id
     WHERE v.sku = 'LY-GIAY-DEMO-12OZ' AND u.active $q$);
SELECT pg_temp.expect_fail_like('CS27 an order line of another customer''s SKU', 'is not a SKU of customer', $q$
    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                         placed_at, created_at)
    VALUES (md5('qa98:order')::uuid, 'SO-QA-98', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'DRAFT',
            0, 'VND', NOW(), NOW());
    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                     units_per_carton)
    SELECT gen_random_uuid(), md5('qa98:order')::uuid, 'LY-GIAY-DEMO-12OZ-TSM', 2 * u.factor, 1000, 'VND', u.id, 2,
           u.factor
      FROM inventory.uom_conversions u JOIN product.variants v ON v.id = u.variant_id
     WHERE v.sku = 'LY-GIAY-DEMO-12OZ' AND u.active $q$);
SELECT pg_temp.expect_fail_like('CS28 a customer price on another customer''s SKU', 'is not a SKU of customer', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, currency, version, created_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'LY-GIAY-DEMO-12OZ-TSM', 'VND', 0, NOW()) $q$);
SELECT pg_temp.expect_ok('CS29 a customer price on the customer''s own new SKU', $q$
    INSERT INTO catalog.customer_price (id, customer_id, sku, currency, version, created_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'QA98-KH-01', 'VND', 0, NOW()) $q$);
SELECT pg_temp.expect_fail_like('CS30 a standard price on a customer''s SKU: it is a variant''s', 'fk_standard_price_variant', $q$
    INSERT INTO catalog.standard_price (id, sku, created_at) VALUES (gen_random_uuid(), 'QA98-KH-01', NOW()) $q$);
SELECT pg_temp.expect_ok('CS31 a standard price on the catalogue variant', $q$
    INSERT INTO catalog.standard_price (id, sku, created_at) VALUES (gen_random_uuid(), 'QA98-BLANK', NOW()) $q$);

\echo '--- a chat about a customer''s SKU is its owner''s (16 BR-02, tg_conversation_context_owner)'
SELECT pg_temp.expect_fail_like('CS32 a conversation of another customer about the SKU', '16 BR-02', $q$
    INSERT INTO chat.conversation_context (id, conversation_id, kind, customer_sku_id, attached_at, version, created_at)
    SELECT gen_random_uuid(), c.id, 'CUSTOMER_SKU', md5('qa98:own')::uuid, NOW(), 0, NOW()
      FROM chat.conversation c WHERE c.customer_id <> 'c0000000-0000-4000-8000-000000000001' ORDER BY c.id LIMIT 1 $q$);
SELECT pg_temp.expect_ok('CS33 the owner''s conversation about it', $q$
    INSERT INTO chat.conversation_context (id, conversation_id, kind, customer_sku_id, attached_at, version, created_at)
    SELECT gen_random_uuid(), c.id, 'CUSTOMER_SKU', md5('qa98:own')::uuid, NOW(), 0, NOW()
      FROM chat.conversation c WHERE c.customer_id = 'c0000000-0000-4000-8000-000000000001' ORDER BY c.id LIMIT 1 $q$);

\echo '--- an inventory item is a customer''s SKU''s, made when an order names it (Q5)'
SELECT pg_temp.expect_true('CS34 no catalogue variant has an item, and every item is a customer''s SKU''s',
    NOT EXISTS (SELECT 1 FROM inventory.inventory_items i JOIN product.variants v ON v.sku = i.sku)
    AND NOT EXISTS (SELECT 1 FROM inventory.inventory_items i
                     WHERE NOT EXISTS (SELECT 1 FROM product.customer_skus c WHERE c.sku = i.sku)));
SELECT pg_temp.expect_true('CS35 a new variant and a new customer''s SKU make no item',
    NOT EXISTS (SELECT 1 FROM inventory.inventory_items WHERE sku IN ('QA98-BLANK', 'QA98-KH-01')));
SELECT pg_temp.expect_fail_like('CS36 an item for a catalogue variant', 'packed as its SKU''s catalogue variant', $q$
    INSERT INTO inventory.inventory_items (id, sku, variant_id) VALUES (gen_random_uuid(), 'QA98-BLANK', md5('qa98:blank')::uuid) $q$);
SELECT pg_temp.expect_fail_like('CS37 an item packed as another variant than its SKU''s', 'packed as its SKU''s catalogue variant', $q$
    INSERT INTO inventory.inventory_items (id, sku, variant_id)
    SELECT gen_random_uuid(), 'QA98-KH-01', v.id FROM product.variants v WHERE v.sku = 'LY-GIAY-DEMO-12OZ' $q$);
SELECT pg_temp.expect_fail_like('CS38 an item counted in what a carton of its variant is called (now on insert too)',
    'is already called', $q$
    INSERT INTO inventory.uom_conversions (id, variant_id, unit_of_measure, factor, active)
    VALUES (gen_random_uuid(), md5('qa98:blank')::uuid, 'THUNG98', 50, FALSE);
    INSERT INTO inventory.inventory_items (id, sku, variant_id, unit_of_measure)
    VALUES (gen_random_uuid(), 'QA98-KH-01', md5('qa98:blank')::uuid, 'THUNG98') $q$);
SELECT pg_temp.expect_ok('CS39 the item the first order of the SKU makes, counted in EACH', $q$
    INSERT INTO inventory.inventory_items (id, sku, variant_id)
    VALUES (md5('qa98:item')::uuid, 'QA98-KH-01', md5('qa98:blank')::uuid) $q$);
SELECT pg_temp.expect_fail_like('CS40 its SKU changed', 'never changes', $q$
    UPDATE inventory.inventory_items SET sku = 'LY-GIAY-DEMO-12OZ-TSM' WHERE id = md5('qa98:item')::uuid $q$);
SELECT pg_temp.expect_fail_like('CS41 its variant changed', 'never changes', $q$
    UPDATE inventory.inventory_items SET variant_id = (SELECT id FROM product.variants WHERE sku = 'LY-GIAY-DEMO-12OZ')
     WHERE id = md5('qa98:item')::uuid $q$);
SELECT pg_temp.expect_fail_like('CS42 the customer''s SKU deleted while it has its item', 'fk_inventory_items_customer_sku', $q$
    DELETE FROM catalog.customer_price WHERE sku = 'QA98-KH-01';
    DELETE FROM chat.conversation_context WHERE customer_sku_id = md5('qa98:own')::uuid;
    DELETE FROM product.customer_skus WHERE id = md5('qa98:own')::uuid $q$);
SELECT pg_temp.expect_fail_like('CS43 the catalogue variant deleted while a customer''s SKU is drawn on it', 'fk_customer_skus_variant', $q$
    DELETE FROM inventory.inventory_items WHERE id = md5('qa98:item')::uuid;
    DELETE FROM product.variants WHERE id = md5('qa98:blank')::uuid $q$);
ROLLBACK;
\echo 'PASS 98_customer_skus'
