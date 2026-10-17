-- =============================================================================
-- PR #109 deploy preflight - READ ONLY. Run it against the database about to be upgraded, before the deploy:
--
--   docker exec -i <postgres container> psql -U <user> -d <database> -X -q < tools/db/preflight/pr109_preflight.sql
--
-- It writes nothing (the whole script is one READ ONLY transaction, rolled back) and decides nothing: every line it
-- prints is for a person to look at. Review of #109, deploy checklist (Tú) and decision of 16/10/2026 on tax codes.
--
--   1. V20261017000100 disables the CUSTOMER-only accounts of BLOCKED / INACTIVE customers and ends their sessions.
--      Lists the accounts that would be disabled, so nobody is surprised by a customer who can no longer sign in.
--   2. V20261017000530 adds uk_pricing_rule_segment_sku and ck_pricing_rule_segment_vnd. A duplicate (segment, SKU)
--      price or a group price that is not positive whole VND makes that migration fail; listed here first.
--   3. V20261017000830 makes a tax code unique in every status and stops on a duplicate. On a database that never
--      ran this branch the column does not exist yet (V20261017000100 adds it, empty), so there is nothing to check.
-- Each section prints "OK: ..." when it finds nothing.
-- =============================================================================

BEGIN TRANSACTION READ ONLY;

DO $$
DECLARE
    row record;
    found boolean := false;
BEGIN
    FOR row IN
        SELECT u.id AS user_id, u.username, c.id AS customer_id, c.status
          FROM identity.app_user u
          JOIN customer.customer c ON c.user_id = u.id
         WHERE c.status IN ('BLOCKED', 'INACTIVE')
           AND u.status = 'ACTIVE'
           AND EXISTS (SELECT 1 FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                        WHERE ur.user_id = u.id AND r.code = 'CUSTOMER')
           AND NOT EXISTS (SELECT 1 FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                            WHERE ur.user_id = u.id AND r.code <> 'CUSTOMER')
         ORDER BY c.status, u.username
    LOOP
        found := true;
        RAISE NOTICE '1. will be DISABLED by V20261017000100: account % (%), customer % is %',
            row.username, row.user_id, row.customer_id, row.status;
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
    FOR row IN EXECUTE
        'SELECT tax_code, string_agg(id::text || '' ('' || status || '')'', '', '' ORDER BY created_at) AS customers
           FROM customer.customer WHERE tax_code IS NOT NULL GROUP BY tax_code HAVING count(*) > 1 ORDER BY tax_code'
    LOOP
        found := true;
        RAISE NOTICE '3. tax code % is held by more than one customer (stops V20261017000830; reconcile by hand): %',
            row.tax_code, row.customers;
    END LOOP;
    IF NOT found THEN
        RAISE NOTICE 'OK: 3. every tax code belongs to one customer';
    END IF;
END $$;

ROLLBACK;
