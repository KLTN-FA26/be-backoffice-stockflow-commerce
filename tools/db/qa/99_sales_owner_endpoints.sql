-- =============================================================================
-- Adversarial checks: what the sales-owner endpoints added (V20270128000150).
--   TI  customer.sales_owner_transfer_item: REASSIGNED_SINCE is a skip reason; an unknown one is not
--   TN  customer.sales_owner_transfer_batch.not_found_customer_ids: never null
--   TE  customer.customer_tax_code_exemption: a reason that is not blank, who granted it, one per customer
--   SC  customer.customer_sales_contact: QUOTE or CONTRACT; one row per customer and salesperson; real people only
-- Fixture rows are made here and everything is rolled back at the end.
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

CREATE FUNCTION pg_temp.id(key TEXT) RETURNS uuid LANGUAGE sql IMMUTABLE AS $$ SELECT md5('qa:so-endpoints:' || key)::uuid $$;

INSERT INTO identity.app_user (id, username, email, status, version, created_at)
VALUES (pg_temp.id('manager'), 'qa-soe-manager', 'qa-soe-manager@example.test', 'ACTIVE', 0, NOW()),
       (pg_temp.id('a'), 'qa-soe-a', 'qa-soe-a@example.test', 'ACTIVE', 0, NOW());
INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
VALUES (pg_temp.id('c1'), 'QA endpoints customer', 'qa-soe-c1@example.test', 'ACTIVE', 0, NOW());
INSERT INTO customer.sales_owner_transfer_batch (id, request_id, from_user_id, reason, requested_at)
VALUES (pg_temp.id('batch'), pg_temp.id('request'), pg_temp.id('a'), 'WORKLOAD', NOW());

\echo '--- TI / TN: transfer items and batches'
SELECT pg_temp.expect_ok('TI1 a return skips a customer moved on since: REASSIGNED_SINCE',
    format('INSERT INTO customer.sales_owner_transfer_item (id, batch_id, customer_id, outcome, skip_reason) '
           'VALUES (%L, %L, %L, ''SKIPPED'', ''REASSIGNED_SINCE'')', pg_temp.id('i1'), pg_temp.id('batch'),
           pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('TI2 an unknown skip reason', 'ck_sales_owner_transfer_item_skip_reason',
    format('UPDATE customer.sales_owner_transfer_item SET skip_reason = ''WHO_KNOWS'' WHERE id = %L',
           pg_temp.id('i1')));
SELECT pg_temp.expect_fail_like('TI3 CUSTOMER_NOT_FOUND is never an item (it names no customer)',
    'ck_sales_owner_transfer_item_skip_reason',
    format('UPDATE customer.sales_owner_transfer_item SET skip_reason = ''CUSTOMER_NOT_FOUND'' WHERE id = %L',
           pg_temp.id('i1')));
SELECT pg_temp.expect_fail_like('TN1 the not-found ids are never null', 'not_found_customer_ids',
    format('UPDATE customer.sales_owner_transfer_batch SET not_found_customer_ids = NULL WHERE id = %L',
           pg_temp.id('batch')));

\echo '--- TE: tax code exemptions'
SELECT pg_temp.expect_fail_like('TE1 BR-S14: an exemption says why', 'ck_customer_tax_code_exemption_reason',
    format('INSERT INTO customer.customer_tax_code_exemption (customer_id, reason, granted_by, granted_at) '
           'VALUES (%L, ''  '', %L, NOW())', pg_temp.id('c1'), pg_temp.id('manager')));
SELECT pg_temp.expect_fail_like('TE2 an exemption names who granted it', 'granted_by',
    format('INSERT INTO customer.customer_tax_code_exemption (customer_id, reason, granted_by, granted_at) '
           'VALUES (%L, ''household'', NULL, NOW())', pg_temp.id('c1')));
SELECT pg_temp.expect_ok('TE3 a manager''s exemption',
    format('INSERT INTO customer.customer_tax_code_exemption (customer_id, reason, granted_by, granted_at) '
           'VALUES (%L, ''household'', %L, NOW())', pg_temp.id('c1'), pg_temp.id('manager')));
SELECT pg_temp.expect_fail_like('TE4 one exemption per customer', 'pk_customer_tax_code_exemption',
    format('INSERT INTO customer.customer_tax_code_exemption (customer_id, reason, granted_by, granted_at) '
           'VALUES (%L, ''again'', %L, NOW())', pg_temp.id('c1'), pg_temp.id('manager')));

\echo '--- SC: who was in touch'
SELECT pg_temp.expect_ok('SC1 a quote sent',
    format('INSERT INTO customer.customer_sales_contact (customer_id, user_id, last_contact_at, last_source) '
           'VALUES (%L, %L, NOW(), ''QUOTE'')', pg_temp.id('c1'), pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('SC2 only a quote or a contract', 'ck_customer_sales_contact_source',
    format('UPDATE customer.customer_sales_contact SET last_source = ''CALL'' WHERE customer_id = %L',
           pg_temp.id('c1')));
SELECT pg_temp.expect_fail_like('SC3 one row per customer and salesperson', 'pk_customer_sales_contact',
    format('INSERT INTO customer.customer_sales_contact (customer_id, user_id, last_contact_at, last_source) '
           'VALUES (%L, %L, NOW(), ''CONTRACT'')', pg_temp.id('c1'), pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('SC4 a real salesperson', 'fk_customer_sales_contact_user',
    format('INSERT INTO customer.customer_sales_contact (customer_id, user_id, last_contact_at, last_source) '
           'VALUES (%L, %L, NOW(), ''QUOTE'')', pg_temp.id('c1'), pg_temp.id('nobody')));

ROLLBACK;
