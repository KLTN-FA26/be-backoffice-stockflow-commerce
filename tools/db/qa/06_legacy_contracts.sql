-- =============================================================================
-- Contracts C1-C4 (V20261011000300-0600): the legacy tables are gone, and the keys that replaced
-- them refuse what the old model allowed. Needs the demo seed. Rolled back at the end.
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

CREATE FUNCTION pg_temp.expect_true(label TEXT, query TEXT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE result BOOLEAN;
BEGIN
    EXECUTE query INTO result;
    IF result IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

\echo '--- the legacy tables are gone'
SELECT pg_temp.expect_true('no legacy product, procurement or location table remains', $q$
    SELECT bool_and(to_regclass(t) IS NULL) FROM unnest(ARRAY[
        'product.product', 'product.category', 'product.variant', 'product.sku', 'product.print_config',
        'product.product_image', 'product.product_gallery', 'product.variant_gallery',
        'procurement.supplier', 'procurement.purchase_order', 'procurement.po_line', 'procurement.goods_receipt',
        'procurement.qc_result', 'procurement.supplier_invoice', 'warehouse.location']) t $q$);
SELECT pg_temp.expect_true('warehouse lost code, address_line and city', $q$
    SELECT NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'warehouse'
                        AND table_name = 'warehouse' AND column_name IN ('code', 'address_line', 'city')) $q$);
SELECT pg_temp.expect_true('the #68 bridge functions are gone', $q$
    SELECT to_regproc('product.bridge_product_to_pim') IS NULL AND to_regproc('product.bridge_category_to_pim') IS NULL $q$);
SELECT pg_temp.expect_true('the archive exists', $q$ SELECT to_regclass('platform.legacy_archive') IS NOT NULL $q$);

\echo '--- C1: SKUs and designs point at the PIM'
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at,
                                     paid_amount, paid_in_full_at)
SELECT md5('qa:contract:order')::uuid, 'ORD-QA-CONTRACT', id, gen_random_uuid(), 'CONFIRMED', 1, 'VND', NOW(), NOW(), 1, NOW()
  FROM customer.customer LIMIT 1;
SELECT pg_temp.expect_fail('order line for a SKU that is no variant', $q$
    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
    VALUES (gen_random_uuid(), md5('qa:contract:order')::uuid, 'NO-SUCH-SKU', 1, 1, 'VND') $q$);
SELECT pg_temp.expect_fail('order line naming a variant in lower case', $q$
    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
    VALUES (gen_random_uuid(), md5('qa:contract:order')::uuid, 'sofa-3s-grey', 1, 1, 'VND') $q$);
SELECT pg_temp.expect_fail('design draft of no product', $q$
    INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
    SELECT gen_random_uuid(), id, gen_random_uuid(), 'DRAFT', NOW(), '{}'::jsonb FROM customer.customer LIMIT 1 $q$);

\echo '--- C2: the map is the only location model'
SELECT pg_temp.expect_fail('stock at a location the map does not have', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version, created_at)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 'NOWHERE-1', 1, 0, 'AVAILABLE', 0, NOW()) $q$);
SELECT pg_temp.expect_fail('warehouse without a map frame', $q$
    UPDATE warehouse.warehouse SET map_width = NULL WHERE prefix = 'HCM' $q$);
SELECT pg_temp.expect_true('putaway targets a storage location', $q$
    SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_putaway_task_target_location'
                    AND confrelid = 'warehouse.storage_location'::regclass) $q$);

\echo '--- C3: stock and putaway are of known inventory items'
SELECT pg_temp.expect_fail('stock of a SKU with no inventory item', $q$
    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version, created_at)
    SELECT gen_random_uuid(), 'NO-ITEM-SKU', location_code, 1, 0, 'AVAILABLE', 0, NOW()
      FROM warehouse.storage_location LIMIT 1 $q$);

\echo '--- C4: putaway has exactly one source'
SELECT pg_temp.expect_fail('putaway task with no source line', $q$
    INSERT INTO warehouse.putaway_task (id, sku, quantity, status, version, created_at)
    VALUES (gen_random_uuid(), 'SOFA-3S-GREY', 1, 'PENDING', 0, NOW()) $q$);
SELECT pg_temp.expect_true('putaway names a receipt and its line together, both real', $q$
    SELECT count(*) = 3 FROM pg_constraint
     WHERE conrelid = 'warehouse.putaway_task'::regclass
       AND conname IN ('ck_putaway_task_receipt_pair', 'fk_putaway_task_receipt', 'fk_putaway_task_receipt_line') $q$);
SELECT pg_temp.expect_fail('delivery decision for no purchase order', $q$
    INSERT INTO procurement.po_delivery_decision (id, purchase_order_id, generation, expected_at, reconciled,
                                                  acknowledge_past_due, channel, recipient, actor, requested_at)
    VALUES (gen_random_uuid(), gen_random_uuid(), 0, CURRENT_DATE, FALSE, FALSE, 'EMAIL', 'a@b.test', 'qa', NOW()) $q$);

ROLLBACK;
