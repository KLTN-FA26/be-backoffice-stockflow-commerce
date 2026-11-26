-- =============================================================================
-- Adversarial checks: the four tables no code used are gone (V20261228000100, issue #419) - warehouse.move_task,
-- fulfillment.wave, notification.template, notification.preference - with fulfillment.pick.wave_id, and
-- inventory.item_in_use, rewritten without move_task, still answers. Needs the demo seed (the blue demo cup is in
-- stock). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
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

\echo '--- the four tables, the pick''s wave and the move task''s trigger function are gone'
SELECT pg_temp.expect_true('UT1 no move_task, wave, notification template or preference table',
    to_regclass('warehouse.move_task') IS NULL AND to_regclass('fulfillment.wave') IS NULL
    AND to_regclass('notification.template') IS NULL AND to_regclass('notification.preference') IS NULL);
SELECT pg_temp.expect_true('UT2 fulfillment.pick has no wave_id',
    NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'fulfillment' AND table_name = 'pick' AND column_name = 'wave_id'));
SELECT pg_temp.expect_true('UT3 warehouse.check_move_task_locations is gone',
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'warehouse' AND p.proname = 'check_move_task_locations'));
SELECT pg_temp.expect_true('UT4 no SQL function body still names a dropped table',
    NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                   AND p.prosrc ~ '(warehouse\.move_task|fulfillment\.wave\M|notification\.template\M|notification\.preference)'));

\echo '--- inventory.item_in_use still answers'
SELECT pg_temp.expect_true('UT5 an item in stock is in use',
    (SELECT inventory.item_in_use(id, sku) FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'));
INSERT INTO product.products (id, code, name, slug) VALUES (md5('qa46:product')::uuid, 'QA46', 'QA 46', 'qa46');
-- Since V20270105000100 an item is a customer's SKU's, made when an order names it.
INSERT INTO product.variants (id, product_id, sku, name, attribute_signature)
VALUES (md5('qa46:variant')::uuid, md5('qa46:product')::uuid, 'QA46-BLANK', 'QA 46', '');
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa46:draft')::uuid, c.id, md5('qa46:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa46:snap')::uuid, md5('qa46:draft')::uuid, repeat('a', 64), 'https://example.com/a.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.customer_skus (id, product_id, variant_id, customer_id, design_snapshot_id, sku, name)
SELECT md5('qa46:sku')::uuid, md5('qa46:product')::uuid, md5('qa46:variant')::uuid, d.customer_id,
       md5('qa46:snap')::uuid, 'QA46-ITEM', 'QA 46'
  FROM design.design_draft d WHERE d.id = md5('qa46:draft')::uuid;
SELECT pg_temp.expect_ok('UT6 a new item of a customer''s SKU, as its first order makes it', $q$
    INSERT INTO inventory.inventory_items (id, sku, variant_id)
    VALUES (gen_random_uuid(), 'QA46-ITEM', md5('qa46:variant')::uuid) $q$);
SELECT pg_temp.expect_true('UT7 ... and nothing uses it',
    (SELECT NOT inventory.item_in_use(id, sku) FROM inventory.inventory_items WHERE sku = 'QA46-ITEM'));
ROLLBACK;
\echo 'PASS 46_unused_tables_removed'
