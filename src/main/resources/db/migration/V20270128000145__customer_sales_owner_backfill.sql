-- =============================================================================
-- Fills the Sales owner of every existing customer (sales-owner plan §4 case 28; API contract "Backfill rule").
-- Module: customer (reads ordering's documents once, here, as the migration that introduces the column).
--
-- The owner is the salesperson of the customer's newest sent document - the rule of GET /orders/sales-contacts
-- (SalesContactsAdapter, PR #497): a quote's sent_by at sent_at, a contract's COALESCE(sent_by, signed_by) at the time
-- that goes with whichever named the person; newest first, ties to the later id. Only a document sent by an account
-- holding SALES_STAFF counts (owner decision D42: a Sales Manager or an admin coordinates, never owns), so a manager's
-- newer document leaves the customer with the salesperson of the newest one a salesperson sent.
--
--   account ACTIVE      -> ASSIGNED to it;
--   account not ACTIVE  -> NEEDS_ASSIGNMENT, the account kept as the former owner (BR-S08: a manager assigns);
--   no such document    -> stays UNASSIGNED.
--
-- Every customer given an owner or marked gets one history row, reason BACKFILL, by the system (changed_by NULL).
-- Customers that already have an owner are left alone, so the migration changes nothing on a database where owners
-- were already set. Pinned by SalesOwnerBackfillMigrationUpgradeTest.
-- =============================================================================

CREATE TEMPORARY TABLE sales_owner_backfill ON COMMIT DROP AS
SELECT DISTINCT ON (d.customer_id) d.customer_id, d.sales_user_id, (u.status = 'ACTIVE') AS active
  FROM (SELECT q.customer_id, q.sent_by AS sales_user_id, q.sent_at AS at, q.id
          FROM ordering.quote q
         WHERE q.sent_by IS NOT NULL AND q.sent_at IS NOT NULL
        UNION ALL
        SELECT o.customer_id, COALESCE(k.sent_by, k.signed_by),
               CASE WHEN k.sent_by IS NOT NULL THEN k.sent_at ELSE k.signed_at END, k.id
          FROM ordering.sales_contract k
          JOIN ordering.customer_order o ON o.id = k.order_id
         WHERE COALESCE(k.sent_by, k.signed_by) IS NOT NULL) d
  JOIN identity.app_user u ON u.id = d.sales_user_id
  JOIN customer.customer c ON c.id = d.customer_id AND c.sales_owner_state = 'UNASSIGNED'
 WHERE EXISTS (SELECT 1 FROM identity.user_role ur JOIN identity.app_role r ON r.id = ur.role_id
                WHERE ur.user_id = u.id AND r.code = 'SALES_STAFF')
 ORDER BY d.customer_id, d.at DESC NULLS LAST, d.id DESC;

UPDATE customer.customer c
   SET sales_owner_id = b.sales_user_id,
       sales_owner_since = NOW(),
       sales_owner_state = CASE WHEN b.active THEN 'ASSIGNED' ELSE 'NEEDS_ASSIGNMENT' END,
       version = c.version + 1,
       last_modified_at = NOW(),
       last_modified_by = 'flyway'
  FROM sales_owner_backfill b
 WHERE c.id = b.customer_id;

INSERT INTO customer.customer_sales_owner_history (id, customer_id, from_user_id, to_user_id, to_state, reason, note,
                                                   changed_by, changed_at, version, created_at, created_by)
SELECT gen_random_uuid(), b.customer_id,
       CASE WHEN b.active THEN NULL ELSE b.sales_user_id END,
       CASE WHEN b.active THEN b.sales_user_id END,
       CASE WHEN b.active THEN 'ASSIGNED' ELSE 'NEEDS_ASSIGNMENT' END,
       'BACKFILL', 'From the newest sent quote or contract', NULL, NOW(), 0, NOW(), 'flyway'
  FROM sales_owner_backfill b;
