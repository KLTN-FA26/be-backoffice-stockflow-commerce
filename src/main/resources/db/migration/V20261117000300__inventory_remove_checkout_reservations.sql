-- =============================================================================
-- Review D-1 (cluster 4): the checkout's expiring reservation goes - POST /api/v1/inventory/reservations,
-- InventoryService.reserve, the 30-minute deadline and the sweeper that released it.
--
-- Why. There is no checkout any more (team plan 5-2): stock is held by Sales choosing a signed contract's shipping
-- warehouse (kltn-docs 17 §4.2, ShippingWarehouseService), a hold at that warehouse with no deadline, and allocated
-- whole cartons at release. The HTTP endpoint was the reserve path's last caller: it took any order id in any state and
-- made a warehouse-less SKU reservation with a 30-minute deadline, lowering ATP outside the hold model. Granted to the
-- warehouse roles, it let a clerk promise stock nobody sold. ReservationSweeper, the deadline and the pin/rearm of it
-- go with the code; the permission inventory-reservations:CREATE goes here.
--
-- Kept on purpose: reading holds, consuming one by hand and releasing one (inventory-reservations READ, UPDATE, DELETE,
-- VIEW_PAGE); the expires_at column and the EXPIRED status, which rows of before keep as history.
--
-- Data (QD-1: no archive, no stop condition):
--   1. every live hold that carries a deadline is closed: EXPIRED (RESERVATION_EXPIRED) when it is past, else RELEASED
--      (MANUAL_OVERRIDE); a hold on a stock row gives its units back to the row's reserved. Nothing would ever close
--      them now. A database built from db/migration and db/demo has none (holds at a warehouse and allocations have no
--      deadline);
--   2. the grants of inventory-reservations:CREATE go, each role that loses one moves its version in the same
--      statement (ADR-0008), then the permission row.
-- =============================================================================

-- 1. holds with a deadline
WITH closed AS (
    UPDATE inventory.stock_reservation
       SET status = CASE WHEN expires_at <= now() THEN 'EXPIRED' ELSE 'RELEASED' END,
           release_reason = CASE WHEN expires_at <= now() THEN 'RESERVATION_EXPIRED' ELSE 'MANUAL_OVERRIDE' END,
           closed_at = now()
     WHERE status = 'HELD' AND expires_at IS NOT NULL
    RETURNING stock_item_id, quantity
)
UPDATE inventory.stock_item s
   SET reserved = GREATEST(s.reserved - x.quantity, 0), version = s.version + 1, last_modified_at = now()
  FROM (SELECT stock_item_id, sum(quantity) AS quantity FROM closed WHERE stock_item_id IS NOT NULL
         GROUP BY stock_item_id) x
 WHERE s.id = x.stock_item_id;

-- 2. the permission: role_permission first, moving the version of every role that loses a grant in the same statement
--    (deleting the permission first would take its grants with it, ON DELETE CASCADE, without moving any version).
WITH lost AS (
    DELETE FROM identity.role_permission rp
    USING identity.permission p
    WHERE rp.permission_id = p.id AND p.resource = 'inventory-reservations' AND p.action = 'CREATE'
    RETURNING rp.role_id
)
UPDATE identity.app_role r SET version = version + 1
WHERE r.id IN (SELECT DISTINCT role_id FROM lost);

DELETE FROM identity.permission WHERE resource = 'inventory-reservations' AND action = 'CREATE';
