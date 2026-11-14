-- =============================================================================
-- WF4 BE-13: whoever releases an order reads its delivery schedule (kltn-docs 17 §4.2, §4.5, BR-29).
--
-- The warehouse manager releases an order batch by batch (sales-order-releases:UPDATE, V20261115000200, 4B-8), and has
-- to see which batch is next, its window, what is held for it and what was already released. GET
-- /orders/{orderId}/delivery-batches answered only sales-contracts:READ, which the warehouse manager does not hold - and
-- must not: it opens the contract itself (totals, payment plan, parties), which is Sales' and accounting's.
--
-- So the schedule gets a code of its own, as the delay alerts and the shipping-warehouse reads did (V20261110000500,
-- V20261108002500): sales-order-delivery-batches:READ. Granted to
--   * every role that read the schedule until now - every holder of sales-contracts:READ (Sales, Sales Manager,
--     Accountant, Production Coordinator, System Admin ...), so nobody loses a read;
--   * every role that releases - every holder of sales-order-releases:UPDATE (the warehouse manager, System Admin).
-- Generic over roles, not a list, so a role created at run time with either permission follows (as V20261022000200 did
-- for the cancellation queue). The portal customer holds neither and gets none: it reads its own schedule through
-- GET /orders/me/{orderId}/delivery-batches (sales-orders:READ, scope OWN).
--
-- The endpoint narrows a caller limited to warehouses (scope WAREHOUSE) to the orders shipping from or released to one of
-- theirs (OrderWarehouseVisibility), as the order list does. Nothing is written to any table but the two permission
-- ones; the window-change endpoints stay sales-contracts:UPDATE (Sales', 17 BR-28).
--
-- Every role that gained a grant advances app_role.version in the same file (ADR-0008).
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'sales-order-delivery-batches:READ', 'sales-order-delivery-batches', 'READ',
        'Read an order''s delivery schedule: batches, windows in force, what is held and released (kltn-docs 17 §4.5, BR-29)',
        0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, schedule.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission schedule ON schedule.code = 'sales-order-delivery-batches:READ'
     WHERE role.code <> 'CUSTOMER'
       AND EXISTS (SELECT 1
                     FROM identity.role_permission held
                     JOIN identity.permission old ON old.id = held.permission_id
                    WHERE held.role_id = role.id
                      AND old.code IN ('sales-contracts:READ', 'sales-order-releases:UPDATE'))
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);

-- System Admin holds every permission row (00 §2.5.3; SystemAdminRoleIntegrationTest): a no-op when the grant above
-- already gave it the new one, as it holds both permissions it keys on.
WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
     CROSS JOIN identity.permission permission
     WHERE role.code = 'SYSTEM_ADMIN'
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
