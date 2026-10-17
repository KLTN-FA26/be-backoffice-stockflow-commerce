-- =============================================================================
-- PR #109 deploy preflight - READ ONLY. Run it against the database about to be upgraded, before the deploy:
--
--   docker exec -i <postgres container> psql -U <user> -d <database> -X -q < tools/db/preflight/pr109_preflight.sql
--
-- It writes nothing (the whole script is one READ ONLY transaction, rolled back) and decides nothing: every line it
-- prints is for a person to look at. It prints ids and counts only - no names, e-mails or tax codes (they would end up
-- in a terminal scrollback or a CI log). Review of #109, deploy checklist (Tú), audit of 2026-10-17.
--
--   0. Flyway history: a failed migration, or an applied version above this branch's first one (V20261017000100), would
--      stop the upgrade - Flyway does not run older files out of order. Lists them.
--   1. V20261017000100 disables the CUSTOMER-only accounts of BLOCKED / INACTIVE customers and ends their sessions.
--      Lists the accounts that would be disabled (ids), so nobody is surprised by a customer who can no longer sign in.
--   2. V20261017000530 adds uk_pricing_rule_segment_sku and ck_pricing_rule_segment_vnd. A duplicate (segment, SKU)
--      price or a group price that is not positive whole VND makes that migration fail; listed here first.
--   3. V20261017000830 makes a tax code unique in every status and stops on a duplicate. On a database that never
--      ran this branch the column does not exist yet (V20261017000100 adds it, empty), so there is nothing to check.
--   4. PR #121 (V20261016001000) gives production_order / sample_request / approved_sample.blank_sku a foreign key to
--      inventory.inventory_items and stops on a row whose blank has no item. Counted here, per table, before either
--      deploy; nothing is repaired automatically - a blank without an item has no meaning to repair it by.
-- Each section prints "OK: ..." when it finds nothing.
-- =============================================================================

BEGIN TRANSACTION READ ONLY;

DO $$
DECLARE
    row record;
    found boolean := false;
BEGIN
    FOR row IN
        SELECT version, description, success FROM flyway_schema_history
         WHERE NOT success OR (version IS NOT NULL AND version::numeric > 20261017000100)
         ORDER BY installed_rank
    LOOP
        found := true;
        IF NOT row.success THEN
            RAISE NOTICE '0. FAILED migration in the history (repair it first): %', row.version;
        ELSE
            RAISE NOTICE '0. applied version % is above V20261017000100: this branch''s older files would not run', row.version;
        END IF;
    END LOOP;
    IF NOT found THEN
        RAISE NOTICE 'OK: 0. no failed migration, nothing applied above V20261017000100 (newest applied: %)',
            (SELECT max(version::numeric) FROM flyway_schema_history WHERE version IS NOT NULL);
    END IF;
END $$;

DO $$
DECLARE
    row record;
    found boolean := false;
BEGIN
    FOR row IN
        SELECT u.id AS user_id, c.id AS customer_id, c.status
          FROM identity.app_user u
          JOIN customer.customer c ON c.user_id = u.id
         WHERE c.status IN ('BLOCKED', 'INACTIVE')
           AND u.status = 'ACTIVE'
           AND EXISTS (SELECT 1 FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                        WHERE ur.user_id = u.id AND r.code = 'CUSTOMER')
           AND NOT EXISTS (SELECT 1 FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                            WHERE ur.user_id = u.id AND r.code <> 'CUSTOMER')
         ORDER BY c.status, u.id
    LOOP
        found := true;
        RAISE NOTICE '1. will be DISABLED by V20261017000100: account %, customer % is %',
            row.user_id, row.customer_id, row.status;
    END LOOP;
    IF NOT found THEN
        RAISE NOTICE 'OK: 1. no CUSTOMER account of a BLOCKED or INACTIVE customer is still active';
    END IF;
END $$;

DO $$
DECLARE
    row record;
    found boolean := false;
BEGIN
    FOR row IN
        SELECT segment_id, sku, count(*) AS prices, string_agg(id::text, ', ') AS ids
          FROM catalog.pricing_rule
         WHERE segment_id IS NOT NULL AND sku IS NOT NULL
         GROUP BY segment_id, sku
        HAVING count(*) > 1
    LOOP
        found := true;
        RAISE NOTICE '2. duplicate group price (blocks uk_pricing_rule_segment_sku): segment % SKU %: % rows (%)',
            row.segment_id, row.sku, row.prices, row.ids;
    END LOOP;
    FOR row IN
        SELECT id, segment_id, sku, currency, price
          FROM catalog.pricing_rule
         WHERE segment_id IS NOT NULL AND NOT (currency = 'VND' AND price > 0 AND price = trunc(price))
    LOOP
        found := true;
        RAISE NOTICE '2. group price that is not positive whole VND (blocks ck_pricing_rule_segment_vnd): % segment % SKU % = % %',
            row.id, row.segment_id, row.sku, row.price, row.currency;
    END LOOP;
    IF NOT found THEN
        RAISE NOTICE 'OK: 2. every (segment, SKU) has one price, each positive whole VND';
    END IF;
END $$;

DO $$
DECLARE
    row record;
    found boolean := false;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'customer' AND table_name = 'customer' AND column_name = 'tax_code') THEN
        RAISE NOTICE 'OK: 3. customer.tax_code does not exist yet: V20261017000100 adds it empty, nothing can clash';
        RETURN;
    END IF;
    -- Ids only: the tax code itself is personal data for a household business (a personal id).
    FOR row IN EXECUTE
        'SELECT string_agg(id::text || '' ('' || status || '')'', '', '' ORDER BY created_at) AS customers
           FROM customer.customer WHERE tax_code IS NOT NULL GROUP BY tax_code HAVING count(*) > 1'
    LOOP
        found := true;
        RAISE NOTICE '3. these customers share one tax code (stops V20261017000830; reconcile by hand): %', row.customers;
    END LOOP;
    IF NOT found THEN
        RAISE NOTICE 'OK: 3. every tax code belongs to one customer';
    END IF;
END $$;

DO $$
DECLARE
    orphans bigint;
    total bigint := 0;
BEGIN
    SELECT count(*) INTO orphans FROM production.production_order c
     WHERE NOT EXISTS (SELECT 1 FROM inventory.inventory_items i WHERE i.sku = c.blank_sku);
    IF orphans > 0 THEN
        RAISE NOTICE '4. % production order(s) name a blank with no inventory item (stops #121''s foreign key)', orphans;
    END IF;
    total := total + orphans;
    SELECT count(*) INTO orphans FROM production.sample_request c
     WHERE NOT EXISTS (SELECT 1 FROM inventory.inventory_items i WHERE i.sku = c.blank_sku);
    IF orphans > 0 THEN
        RAISE NOTICE '4. % sample request(s) name a blank with no inventory item (stops #121''s foreign key)', orphans;
    END IF;
    total := total + orphans;
    SELECT count(*) INTO orphans FROM ordering.approved_sample c
     WHERE NOT EXISTS (SELECT 1 FROM inventory.inventory_items i WHERE i.sku = c.blank_sku);
    IF orphans > 0 THEN
        RAISE NOTICE '4. % approved sample(s) name a blank with no inventory item (stops #121''s foreign key)', orphans;
    END IF;
    total := total + orphans;
    IF total = 0 THEN
        RAISE NOTICE 'OK: 4. every production order, sample request and approved sample names a blank that is an inventory item';
    END IF;
END $$;

ROLLBACK;
