-- =============================================================================
-- The customer does not place orders (kltn-docs 14 BR-01, BR-02; 13 BR-08): an order is drawn up by Sales from an
-- accepted quote, and every "order", "request a quote" and "order again" in the storefront opens a conversation with
-- Sales. The self-ordering surface is gone from the code - checkout (POST /api/v1/orders) with its duplicate-order
-- check, the reorder of a quoted order (POST /api/v1/orders/{id}/reorder) and its notice to Sales, and the system's
-- cancellation of an unpaid order whose stock hold ran out, with its referral to staff once printing had started. This
-- removes what the database kept for them. Every row dropped is archived whole in platform.legacy_archive first.
--
-- Kept on purpose:
--   * ordering.customer_order and its lines, whatever path placed them: orders placed at checkout stay as history and
--     are worked like any other;
--   * the permission sales-orders:CREATE itself, still held by staff roles (the back office); only the customer's grant
--     goes - it let a portal account place and reorder;
--   * the reason codes the removed paths wrote (PAYMENT_NOT_RECEIVED, RESERVATION_EXPIRED): orders and holds of the
--     past carry them.
--
-- Data:
--   * ordering.cart and ordering.cart_line were never written by the application (no code maps them); whatever rows a
--     test or a hand put there are archived, then both tables are dropped;
--   * an order placed at checkout and still PENDING_PAYMENT is no longer cancelled when its hold runs out: Sales cancels
--     it from the back office (POST /orders/{id}/admin-cancellation) if the customer does not pay;
--   * event publications not yet completed that only the removed code could handle are archived and deleted: the order
--     side of a hold's expiry (InventoryEventListener), the referral (OrderCancellationReferred) and a customer's reorder
--     notice (CustomerActedOnQuote with REORDER_REQUESTED). CustomerActedOnQuote also lost sourceOrderNumber, which
--     only a reorder filled: the field is cut out of the text of the publications still waiting (archived first),
--     keeping Jackson's compact form - the registry finds a publication by that exact text;
--   * sales-carts goes with every grant of it, and CUSTOMER loses sales-orders:CREATE; every role that lost a grant
--     moves its version in the same statement, so cached grants are dropped at once (ADR-0008).
--
-- Order, so no step meets a reference a later step removes:
--   1. archive; 2. event publications; 3. the cart tables, child first; 4. permissions.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. Archive
-- ---------------------------------------------------------------------------------------------
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'ordering.cart_line', l.id, to_jsonb(l) FROM ordering.cart_line l
UNION ALL
SELECT 'ordering.cart', c.id, to_jsonb(c) FROM ordering.cart c
UNION ALL
SELECT 'identity.permission', p.id, to_jsonb(p) FROM identity.permission p
WHERE p.resource = 'sales-carts'
UNION ALL
SELECT 'identity.role_permission', rp.id, to_jsonb(rp)
FROM identity.role_permission rp
JOIN identity.permission p ON p.id = rp.permission_id
WHERE p.resource = 'sales-carts'
   OR (p.code = 'sales-orders:CREATE'
       AND rp.role_id IN (SELECT id FROM identity.app_role WHERE code = 'CUSTOMER'))
UNION ALL
SELECT 'public.event_publication', e.id, to_jsonb(e)
FROM event_publication e
WHERE e.completion_date IS NULL
  AND (e.event_type = 'com.stockflow.contracts.OrderCancellationReferred'
       OR e.listener_id LIKE 'com.stockflow.order.internal.service.InventoryEventListener.%'
       OR e.listener_id LIKE 'com.stockflow.notification.internal.service.OrderReferralNotificationListener.%'
       OR (e.event_type = 'com.stockflow.contracts.CustomerActedOnQuote'
           AND e.serialized_event LIKE '%"sourceOrderNumber":%'));


-- ---------------------------------------------------------------------------------------------
-- 2. Event publications that can no longer be delivered, or no longer read back as they are
-- ---------------------------------------------------------------------------------------------
-- Unfinished ones only: a completed publication is never read again.
DELETE FROM event_publication e
WHERE e.completion_date IS NULL
  AND (e.event_type = 'com.stockflow.contracts.OrderCancellationReferred'
       OR e.listener_id LIKE 'com.stockflow.order.internal.service.InventoryEventListener.%'
       OR e.listener_id LIKE 'com.stockflow.notification.internal.service.OrderReferralNotificationListener.%'
       OR (e.event_type = 'com.stockflow.contracts.CustomerActedOnQuote'
           AND e.serialized_event LIKE '%"action":"REORDER_REQUESTED"%'));

-- The field is cut out of the text rather than through jsonb, which would reorder the keys and add spaces.
UPDATE event_publication
SET serialized_event = regexp_replace(serialized_event, ',"sourceOrderNumber":(null|"[^"]*")', '', 'g')
WHERE event_type = 'com.stockflow.contracts.CustomerActedOnQuote'
  AND completion_date IS NULL
  AND serialized_event LIKE '%"sourceOrderNumber":%';


-- ---------------------------------------------------------------------------------------------
-- 3. The cart tables: lines first (fk_cart_line_cart), then the carts; nothing else references either
-- ---------------------------------------------------------------------------------------------
DROP TABLE ordering.cart_line;
DROP TABLE ordering.cart;


-- ---------------------------------------------------------------------------------------------
-- 4. Permissions
-- ---------------------------------------------------------------------------------------------
-- role_permission first, moving the version of every role that loses a grant in the same statement: deleting the
-- permission first would take its grants with it (ON DELETE CASCADE) without moving any version.
WITH lost AS (
    DELETE FROM identity.role_permission rp
    USING identity.permission p
    WHERE rp.permission_id = p.id
      AND (p.resource = 'sales-carts'
           OR (p.code = 'sales-orders:CREATE'
               AND rp.role_id IN (SELECT id FROM identity.app_role WHERE code = 'CUSTOMER')))
    RETURNING rp.role_id
)
UPDATE identity.app_role r SET version = version + 1
WHERE r.id IN (SELECT DISTINCT role_id FROM lost);

DELETE FROM identity.permission WHERE resource = 'sales-carts';
