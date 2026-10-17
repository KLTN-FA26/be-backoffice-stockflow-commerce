-- =============================================================================
-- One customer record per tax code, whatever its status (decided by the user on 16/10/2026, review of #109).
--
-- uk_customer_tax_code_live (V20261017000100) covered PENDING_ACTIVATION and ACTIVE only, so a BLOCKED customer
-- could be onboarded again under the same tax code - a way round the block - and a closed (INACTIVE) one could be
-- created anew instead of reopened. Now a normalised tax code (or a household representative's 12-digit personal id)
-- names one customer record in every status:
--   * BLOCKED: no new record; the block stays where it is.
--   * INACTIVE: the ECOMMERCE_ADMIN reopens the same record with a reason, and customer_status_history keeps the
--     story (V20261017000500); no new record wipes the trail.
-- The tax code stays optional (household businesses, small shops): NULL is not constrained.
--
-- Data: existing duplicates are not merged, deleted or renumbered here - which record is the real customer is a
-- business decision. The file stops with the duplicated codes' customer ids instead, for manual reconciliation
-- (tools/db/preflight/pr109_preflight.sql lists them read-only before a deploy).
-- =============================================================================

DO $$
DECLARE
    duplicates TEXT;
BEGIN
    SELECT string_agg(tax_code || ': ' || ids, '; ') INTO duplicates
      FROM (SELECT tax_code, string_agg(id::text, ', ' ORDER BY created_at, id) AS ids
              FROM customer.customer
             WHERE tax_code IS NOT NULL
             GROUP BY tax_code
            HAVING count(*) > 1
             ORDER BY tax_code
             LIMIT 20) twins;
    IF duplicates IS NOT NULL THEN
        RAISE EXCEPTION 'V20261017000830: tax codes held by more than one customer, reconcile them by hand first '
            '(first 20): %', duplicates;
    END IF;
END $$;

DROP INDEX customer.uk_customer_tax_code_live;
CREATE UNIQUE INDEX uk_customer_tax_code ON customer.customer (tax_code) WHERE tax_code IS NOT NULL;

COMMENT ON INDEX customer.uk_customer_tax_code IS
    'One customer per normalised tax code or personal id, in every status (BR-01; reopen a closed one, do not re-create).';
