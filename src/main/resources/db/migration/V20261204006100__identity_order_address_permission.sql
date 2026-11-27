-- =============================================================================
-- WF04 G2: Sales change an order's delivery address until its first carton is labelled (kltn-docs 17 §4.7 steps 23-24,
-- BR-16; 08 BR-04, §6 edge case "Địa chỉ giao không hợp lệ": held ADDRESS, corrected, released).
--
-- PUT /orders/{orderId}/shipping-address gets a code of its own, sales-order-addresses:UPDATE, as Sales' other
-- decisions on an order did (sales-order-partial-shipments, sales-order-shipping-warehouses): not sales-orders:UPDATE,
-- which the portal customer holds for their own orders - the customer asks, Sales changes it (17 §3 input "Khách / Sale
-- ... đổi địa chỉ", §2: the order is Sales' to amend).
--
-- Granted to SALES_STAFF (17 §2) and, as every permission row, to SYSTEM_ADMIN. Every role that gained a grant advances
-- app_role.version in the same file (ADR-0008).
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'sales-order-addresses:UPDATE', 'sales-order-addresses', 'UPDATE',
        'Change an order''s delivery address until its first carton is labelled (kltn-docs 17 §4.7, BR-16)',
        0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission permission ON permission.code = 'sales-order-addresses:UPDATE'
     WHERE role.code IN ('SALES_STAFF', 'SYSTEM_ADMIN')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
