-- =============================================================================
-- Adversarial checks: the Sales owner recorded on quotes and contracts, and the "làm hộ" entries of an order
-- (sales-owner plan 2026-11-28; kltn-docs 14 BR-26, 17 BR-32; V20270128000300, V20270128000310).
--   DS  ordering.quote / ordering.sales_contract .sales_owner_id: a real account (FK); once set, never changed
--       (tg_quote_sales_owner_fixed, tg_sales_contract_sales_owner_fixed); the backfill gave the demo documents a
--       salesperson sender's id only
--   OB  ordering.order_on_behalf_action: a known action (ck_order_on_behalf_action_action); nobody acts on their own
--       behalf (ck_order_on_behalf_action_other); real accounts and order (FKs); append-only, rows leave only with
--       their order
-- Needs the demo seed. Fixture rows are made here and everything is rolled back at the end.
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

CREATE FUNCTION pg_temp.id(key TEXT) RETURNS uuid LANGUAGE sql IMMUTABLE AS $$ SELECT md5('qa:document-owner:' || key)::uuid $$;

-- Fixtures: two salespeople, a customer, its quote, an order and the order's contract.
INSERT INTO identity.app_user (id, username, email, status, version, created_at)
VALUES (pg_temp.id('a'), 'qa-do-a', 'qa-do-a@example.test', 'ACTIVE', 0, NOW()),
       (pg_temp.id('b'), 'qa-do-b', 'qa-do-b@example.test', 'ACTIVE', 0, NOW());
INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
VALUES (pg_temp.id('c'), 'QA document owner customer', 'qa-do-c@example.test', 'ACTIVE', 0, NOW());
INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, valid_until, created_at)
VALUES (pg_temp.id('q'), 'QA-DO-Q1', pg_temp.id('q-request'), pg_temp.id('c'), 'DRAFT', NOW() + interval '30 days',
        NOW()),
       (pg_temp.id('q2'), 'QA-DO-Q2', pg_temp.id('q2-request'), pg_temp.id('c'), 'DRAFT', NOW() + interval '30 days',
        NOW());
INSERT INTO ordering.customer_order (id, order_number, request_id, customer_id, status, total_amount, currency,
                                     placed_at, created_at)
VALUES (pg_temp.id('o'), 'QA-DO-O1', pg_temp.id('o-request'), pg_temp.id('c'), 'DRAFT', 0, 'VND', NOW(), NOW()),
       (pg_temp.id('o2'), 'QA-DO-O2', pg_temp.id('o2-request'), pg_temp.id('c'), 'DRAFT', 0, 'VND', NOW(), NOW());
-- Cancelled contracts: the contract terms trigger (batches, installments, deferred) passes over a cancelled one.
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code,
                                     seller_address, seller_representative, seller_representative_title, buyer_name,
                                     buyer_address, buyer_representative, buyer_representative_title,
                                     free_sample_rounds, sales_owner_id, cancelled_at, cancelled_by,
                                     cancellation_reason)
VALUES (pg_temp.id('k'), 'QA-DO-K1', pg_temp.id('o'), 'CANCELLED', 'Seller', '0312345678', 'Addr', 'Rep', 'Director',
        'Buyer', 'Addr', 'Rep', 'Director', 0, pg_temp.id('a'), NOW(), pg_temp.id('a'), 'redrawn'),
       (pg_temp.id('k2'), 'QA-DO-K2', pg_temp.id('o2'), 'CANCELLED', 'Seller', '0312345678', 'Addr', 'Rep',
        'Director', 'Buyer', 'Addr', 'Rep', 'Director', 0, NULL, NOW(), pg_temp.id('a'), 'redrawn');

\echo '--- DS: the Sales owner recorded on quotes and contracts'
SELECT pg_temp.expect_fail_like('DS1 a quote names an account that exists', 'fk_quote_sales_owner',
    format('UPDATE ordering.quote SET sales_owner_id = %L WHERE id = %L', gen_random_uuid(), pg_temp.id('q')));
SELECT pg_temp.expect_ok('DS2 a quote drawn up without an owner may be given one once (NULL to a value)',
    format('UPDATE ordering.quote SET sales_owner_id = %L WHERE id = %L', pg_temp.id('a'), pg_temp.id('q')));
SELECT pg_temp.expect_fail_like('DS3 BR-26: a quote''s owner never moves to another salesperson', 'immutable once set',
    format('UPDATE ordering.quote SET sales_owner_id = %L WHERE id = %L', pg_temp.id('b'), pg_temp.id('q')));
SELECT pg_temp.expect_fail_like('DS4 BR-26: nor is it cleared', 'immutable once set',
    format('UPDATE ordering.quote SET sales_owner_id = NULL WHERE id = %L', pg_temp.id('q')));
SELECT pg_temp.expect_ok('DS5 the rest of a quote still changes (a revision)',
    format('UPDATE ordering.quote SET revision = revision + 1 WHERE id = %L', pg_temp.id('q')));
SELECT pg_temp.expect_fail_like('DS6 BR-26: a contract''s owner never moves', 'immutable once set',
    format('UPDATE ordering.sales_contract SET sales_owner_id = %L WHERE id = %L', pg_temp.id('b'),
           pg_temp.id('k')));
SELECT pg_temp.expect_fail_like('DS7 a contract names an account that exists', 'fk_sales_contract_sales_owner',
    format('UPDATE ordering.sales_contract SET sales_owner_id = %L WHERE id = %L', gen_random_uuid(),
           pg_temp.id('k2')));
SELECT pg_temp.expect_true('DS8 the backfill gave no demo document a sender who is not a salesperson',
    NOT EXISTS (SELECT 1 FROM ordering.quote q
                 WHERE q.sales_owner_id IS NOT NULL AND q.id <> pg_temp.id('q')
                   AND NOT EXISTS (SELECT 1 FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                                    WHERE ur.user_id = q.sales_owner_id AND r.code = 'SALES_STAFF'))
    AND NOT EXISTS (SELECT 1 FROM ordering.sales_contract k
                     WHERE k.sales_owner_id IS NOT NULL AND k.id NOT IN (pg_temp.id('k'), pg_temp.id('k2'))
                       AND NOT EXISTS (SELECT 1 FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                                        WHERE ur.user_id = k.sales_owner_id AND r.code = 'SALES_STAFF')));

\echo '--- OB: work done on another salesperson''s behalf (17 BR-32)'
SELECT pg_temp.expect_ok('OB1 an entry: B chose the warehouse for A',
    format('INSERT INTO ordering.order_on_behalf_action (id, order_id, action, actor_id, acting_for_user_id, acted_at) '
           'VALUES (%L, %L, ''CHOOSE_SHIPPING_WAREHOUSE'', %L, %L, NOW())', pg_temp.id('e1'), pg_temp.id('o'),
           pg_temp.id('b'), pg_temp.id('a')));
SELECT pg_temp.expect_ok('OB2 a customer with no active owner: nobody done for, the actor still recorded',
    format('INSERT INTO ordering.order_on_behalf_action (id, order_id, action, actor_id, acting_for_user_id, acted_at) '
           'VALUES (gen_random_uuid(), %L, ''HOLD'', %L, NULL, NOW())', pg_temp.id('o'), pg_temp.id('b')));
SELECT pg_temp.expect_fail_like('OB3 only the operational actions BR-32 names', 'ck_order_on_behalf_action_action',
    format('INSERT INTO ordering.order_on_behalf_action (id, order_id, action, actor_id, acting_for_user_id, acted_at) '
           'VALUES (gen_random_uuid(), %L, ''SEND_QUOTE'', %L, %L, NOW())', pg_temp.id('o'), pg_temp.id('b'),
           pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('OB4 nobody acts on their own behalf', 'ck_order_on_behalf_action_other',
    format('INSERT INTO ordering.order_on_behalf_action (id, order_id, action, actor_id, acting_for_user_id, acted_at) '
           'VALUES (gen_random_uuid(), %L, ''HOLD'', %L, %L, NOW())', pg_temp.id('o'), pg_temp.id('a'),
           pg_temp.id('a')));
SELECT pg_temp.expect_fail_like('OB5 the actor is a real account', 'fk_order_on_behalf_action_actor',
    format('INSERT INTO ordering.order_on_behalf_action (id, order_id, action, actor_id, acting_for_user_id, acted_at) '
           'VALUES (gen_random_uuid(), %L, ''HOLD'', %L, NULL, NOW())', pg_temp.id('o'), gen_random_uuid()));
SELECT pg_temp.expect_fail_like('OB6 the history cannot be edited', 'append-only',
    format('UPDATE ordering.order_on_behalf_action SET action = ''RESOLVE_HOLD'' WHERE id = %L', pg_temp.id('e1')));
SELECT pg_temp.expect_fail_like('OB7 nor an entry deleted while its order stands', 'append-only',
    format('DELETE FROM ordering.order_on_behalf_action WHERE id = %L', pg_temp.id('e1')));
SELECT pg_temp.expect_ok('OB8 the entries leave with their order',
    format('DELETE FROM ordering.sales_contract WHERE order_id = %L; DELETE FROM ordering.customer_order WHERE id = %L',
           pg_temp.id('o'), pg_temp.id('o')));
SELECT pg_temp.expect_true('OB9 ... and are gone with it',
    NOT EXISTS (SELECT 1 FROM ordering.order_on_behalf_action WHERE order_id = pg_temp.id('o')));

ROLLBACK;
