-- =============================================================================
-- WF-05 full fix, shared base (PR-0): the two approval-level permissions the decisions of 2026-11-23 ask for.
--
-- sales-lost-shipment-closures:APPROVE (decision Q-12, BE-17)
--   Sales close the units of a shipment the carrier reported lost, on the carrier's written confirmation; the money is
--   settled as a carrier's return is (Q-2), at the moment of closing. An approval-level grant of its own - as recording
--   a shipment brought back takes fulfillment-delivery-outcomes:APPROVE (Q-7) - so it can be withheld from a role that
--   may otherwise close an order's rest (sales-order-cancellations:UPDATE). Granted to SALES_STAFF.
--
-- procurement-return-receipt-approvals:APPROVE (decision Q-18, BE-20, BE-43)
--   A return receipt holding an opened carton or loose units - goods that do not go into stock and are destroyed on the
--   receipt - is confirmed by the warehouse manager, someone other than who counted it. A plain return receipt is still
--   confirmed with procurement-goods-receipts:UPDATE. Granted to WAREHOUSE_MANAGER.
--
-- Recording a shipment brought back by the carrier (Q-7) needs no new permission: it now takes
-- fulfillment-delivery-outcomes:APPROVE, which the warehouse manager already holds.
--
-- SYSTEM_ADMIN holds every permission (SystemAdminRoleIntegrationTest). Every role that gained a grant advances
-- app_role.version in the same transaction (ADR-0008), so no holder keeps a cached matrix without it.
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'sales-lost-shipment-closures:APPROVE', 'sales-lost-shipment-closures', 'APPROVE',
        'Close a shipment the carrier lost, on its written confirmation; settled as a carrier return (WF-05 Q-12)',
        0, NOW(), 'flyway'),
       (gen_random_uuid(), 'procurement-return-receipt-approvals:APPROVE', 'procurement-return-receipt-approvals',
        'APPROVE', 'Confirm a return receipt holding opened cartons or loose units, not counted by oneself (WF-05 Q-18)',
        0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH grantee (role_code, permission_code) AS (
    VALUES ('SYSTEM_ADMIN', 'sales-lost-shipment-closures:APPROVE'),
           ('SALES_STAFF', 'sales-lost-shipment-closures:APPROVE'),
           ('SYSTEM_ADMIN', 'procurement-return-receipt-approvals:APPROVE'),
           ('WAREHOUSE_MANAGER', 'procurement-return-receipt-approvals:APPROVE')
),
granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'flyway'
      FROM grantee g
      JOIN identity.app_role r ON r.code = g.role_code
      JOIN identity.permission p ON p.code = g.permission_code
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
