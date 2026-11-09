-- =============================================================================
-- Adversarial checks + happy paths: B2B quotes (SCRUM-448, V20261017000300) and who may answer a quote
-- (V20261017000300). The print-sample checks went with in-house production (V20261108000100). Needs the demo seed.
-- Rolled back at the end.
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
    IF condition THEN
        RAISE NOTICE 'ok    %', label;
    ELSE
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
END $$;

-- Fixtures: a confirmed design of the first customer, a draft quote with one line and one tier.
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa9:draft')::uuid, c.id, (SELECT p.id FROM product.products p ORDER BY p.code LIMIT 1), 'DRAFT', NOW(),
       '{}'::jsonb FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa9:snap')::uuid, md5('qa9:draft')::uuid, repeat('b', 64), 'https://example.com/b.pdf', NOW(), NOW(), '[]'::jsonb);
INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, payment_term, valid_until)
SELECT md5('qa9:quote')::uuid, 'QT-QA-1', gen_random_uuid(), c.id, 'DRAFT', 'PREPAID', NOW() + interval '7 days'
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO ordering.quote_line (id, quote_id, line_no, blank_sku, design_snapshot_id, quantity)
VALUES (md5('qa9:line')::uuid, md5('qa9:quote')::uuid, 1, 'SOFA-3S-GREY', md5('qa9:snap')::uuid, 500);
INSERT INTO ordering.quote_price_tier (quote_line_id, min_quantity, unit_price)
VALUES (md5('qa9:line')::uuid, 500, 2000);

\echo '--- quotes (V20261017000300)'
SELECT pg_temp.expect_fail('QT1 an unknown status', $q$
    UPDATE ordering.quote SET status = 'APPROVED' WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT2 SENT without who sent it and when', $q$
    UPDATE ordering.quote SET status = 'SENT' WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT3 a deposit share on a prepaid quote', $q$
    UPDATE ordering.quote SET deposit_percent = 30 WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT4 a deposit of 100% (Order.applyTerms needs below 100)', $q$
    UPDATE ordering.quote SET payment_term = 'DEPOSIT', deposit_percent = 100 WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT5 valid until before it was created', $q$
    UPDATE ordering.quote SET valid_until = created_at - interval '1 day' WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT6 CHANGES_REQUESTED without the customer''s feedback', $q$
    UPDATE ordering.quote SET status = 'CHANGES_REQUESTED', sent_at = NOW(), sent_by = md5('demo:user:editor')::uuid,
           responded_at = NOW(), responded_by = md5('demo:user:editor')::uuid WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT7 CONVERTED without its order', $q$
    UPDATE ordering.quote SET status = 'CONVERTED', sent_at = NOW(), sent_by = md5('demo:user:editor')::uuid,
           responded_at = NOW(), responded_by = md5('demo:user:editor')::uuid WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT8 CANCELLED without a reason', $q$
    UPDATE ordering.quote SET status = 'CANCELLED' WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT9 two quotes for one request id', $q$
    INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, payment_term, valid_until)
    SELECT gen_random_uuid(), 'QT-QA-2', request_id, customer_id, 'DRAFT', 'PREPAID', valid_until
      FROM ordering.quote WHERE id = md5('qa9:quote')::uuid $q$);
SELECT pg_temp.expect_fail('QT10 a tier price with a fraction of a dong', $q$
    INSERT INTO ordering.quote_price_tier (quote_line_id, min_quantity, unit_price)
    VALUES (md5('qa9:line')::uuid, 1000, 1999.50) $q$);
SELECT pg_temp.expect_fail('QT11 two tiers starting at the same quantity', $q$
    INSERT INTO ordering.quote_price_tier (quote_line_id, min_quantity, unit_price)
    VALUES (md5('qa9:line')::uuid, 500, 1900) $q$);
SELECT pg_temp.expect_fail('QT12 a line on a blank that is not a product variant', $q$
    INSERT INTO ordering.quote_line (id, quote_id, line_no, blank_sku, design_snapshot_id, quantity)
    VALUES (gen_random_uuid(), md5('qa9:quote')::uuid, 2, 'NO-SUCH-SKU', md5('qa9:snap')::uuid, 500) $q$);
SELECT pg_temp.expect_fail('QT13 an accepted quantity without its price', $q$
    UPDATE ordering.quote_line SET accepted_quantity = 500 WHERE id = md5('qa9:line')::uuid $q$);
SELECT pg_temp.expect_fail('QT14 a SENT history row without the offer it sent', $q$
    INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, changed_at)
    VALUES (gen_random_uuid(), md5('qa9:quote')::uuid, 1, 'DRAFT', 'SENT', NOW()) $q$);
SELECT pg_temp.expect_ok('QT15 sent, then accepted at 500 for 2,000', $q$
    UPDATE ordering.quote SET status = 'ACCEPTED', sent_at = NOW(), sent_by = md5('demo:user:editor')::uuid,
           responded_at = NOW(), responded_by = md5('demo:user:editor')::uuid WHERE id = md5('qa9:quote')::uuid;
    UPDATE ordering.quote_line SET accepted_quantity = 500, accepted_unit_price = 2000 WHERE id = md5('qa9:line')::uuid $q$);

\echo '--- quote and order lines by carton of the customer''s own SKU (V20261108002000, 4B-2)'
INSERT INTO product.products (id, code, name, slug, status, kind, version, created_at)
VALUES (md5('qa9:c:product')::uuid, 'QAQC', 'QA ly', 'qa-qc', 'DRAFT', 'CUSTOMIZABLE', 0, NOW());
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at)
VALUES (md5('qa9:c:blank')::uuid, md5('qa9:c:product')::uuid, 'QA-QC-BLANK', 'QA ly', 'ACTIVE', TRUE, 'DEFAULT', 0, 0,
        NOW());
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa9:c:draft')::uuid, c.id, md5('qa9:c:product')::uuid, 'DRAFT', NOW(), '{}'::jsonb
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa9:c:snap')::uuid, md5('qa9:c:draft')::uuid, repeat('f', 64), 'https://example.com/f.pdf', NOW(), NOW(),
        '[]'::jsonb);
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, owner_customer_id, design_snapshot_id, source_variant_id)
SELECT md5('qa9:c:own')::uuid, md5('qa9:c:product')::uuid, 'QA-QC-OWN', 'QA ly in logo', 'ACTIVE', FALSE,
       'OWNED-SKU:QA-QC-OWN', 1, 0, NOW(), c.id, md5('qa9:c:snap')::uuid, md5('qa9:c:blank')::uuid
  FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase, active,
                                       version, created_at, default_shipping)
SELECT md5('qa9:c:carton')::uuid, i.id, 'CARTON', 50, FALSE, FALSE, TRUE, 0, NOW(), TRUE
  FROM inventory.inventory_items i WHERE i.sku = 'QA-QC-OWN';
INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, payment_term, valid_until)
SELECT md5('qa9:c:quote')::uuid, 'QT-QA-3', gen_random_uuid(), c.id, 'DRAFT', 'PREPAID', NOW() + interval '7 days'
  FROM customer.customer c ORDER BY c.id LIMIT 1;
SELECT pg_temp.expect_ok('QT16 20 cartons of the customer''s own SKU in its carton unit, 50 pieces each', $q$
    INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, units_per_carton)
    VALUES (md5('qa9:c:line')::uuid, md5('qa9:c:quote')::uuid, 1, 'QA-QC-OWN', md5('qa9:c:carton')::uuid, 20, 50) $q$);
SELECT pg_temp.expect_fail('QT17 the same SKU twice on one quote (14 BR-22)', $q$
    INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, units_per_carton)
    VALUES (gen_random_uuid(), md5('qa9:c:quote')::uuid, 2, 'QA-QC-OWN', md5('qa9:c:carton')::uuid, 5, 50) $q$);
SELECT pg_temp.expect_fail('QT18 pieces per carton other than the carton unit''s', $q$
    UPDATE ordering.quote_line SET units_per_carton = 40 WHERE id = md5('qa9:c:line')::uuid $q$);
SELECT pg_temp.expect_fail('QT19 a line that is both a blank with a design and a SKU in cartons', $q$
    UPDATE ordering.quote_line SET blank_sku = 'QA-QC-BLANK', design_snapshot_id = md5('qa9:c:snap')::uuid,
           quantity = 1000 WHERE id = md5('qa9:c:line')::uuid $q$);
SELECT pg_temp.expect_fail('QT20 accepted cartons without their price', $q$
    UPDATE ordering.quote_line SET accepted_cartons = 20 WHERE id = md5('qa9:c:line')::uuid $q$);
SELECT pg_temp.expect_ok('QT21 accepted: 20 cartons at 1,800 a piece', $q$
    UPDATE ordering.quote_line SET accepted_cartons = 20, accepted_unit_price = 1800
     WHERE id = md5('qa9:c:line')::uuid $q$);
SELECT pg_temp.expect_fail('QT22 the carton''s factor changed while a quote line counts in it (01 BR-10)', $q$
    UPDATE inventory.uom_conversions SET factor = 25 WHERE id = md5('qa9:c:carton')::uuid $q$);
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                     created_at)
SELECT md5('qa9:c:order')::uuid, 'SO-QA-QC', c.id, gen_random_uuid(), 'DRAFT', 1800000, 'VND', NOW(), NOW()
  FROM customer.customer c ORDER BY c.id LIMIT 1;
SELECT pg_temp.expect_fail('QT23 an order line whose pieces are not its cartons x pieces per carton', $q$
    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                     units_per_carton)
    VALUES (gen_random_uuid(), md5('qa9:c:order')::uuid, 'QA-QC-OWN', 999, 1800, 'VND', md5('qa9:c:carton')::uuid,
            20, 50) $q$);
SELECT pg_temp.expect_ok('QT24 an order line of 20 cartons, 1,000 pieces, with the requested delivery date', $q$
    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, uom_conversion_id, cartons,
                                     units_per_carton, requested_delivery_date)
    VALUES (gen_random_uuid(), md5('qa9:c:order')::uuid, 'QA-QC-OWN', 1000, 1800, 'VND', md5('qa9:c:carton')::uuid,
            20, 50, CURRENT_DATE + 30) $q$);

\echo '--- who answers (V20261017000200 / 000300)'
CREATE FUNCTION pg_temp.grants(role_code TEXT, permission_code TEXT) RETURNS BOOLEAN LANGUAGE sql AS $$
    SELECT EXISTS (SELECT 1 FROM identity.role_permission rp
                     JOIN identity.app_role r ON r.id = rp.role_id
                     JOIN identity.permission p ON p.id = rp.permission_id
                    WHERE r.code = role_code AND p.code = permission_code)
$$;
SELECT pg_temp.expect_true('GR3 only the customer answers a quote; Sales drafts and converts',
    pg_temp.grants('CUSTOMER', 'sales-quotes:APPROVE')
    AND NOT pg_temp.grants('SALES_STAFF', 'sales-quotes:APPROVE')
    AND NOT pg_temp.grants('ORDER_COORDINATOR', 'sales-quotes:APPROVE')
    AND pg_temp.grants('SALES_STAFF', 'sales-quotes:CREATE')
    AND pg_temp.grants('SALES_STAFF', 'sales-quotes:UPDATE')
    AND NOT pg_temp.grants('CUSTOMER', 'sales-quotes:CREATE'));

ROLLBACK;
