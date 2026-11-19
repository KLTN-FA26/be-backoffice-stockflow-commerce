-- =============================================================================
-- RTM-RTDCANCEL (owner decision 2026-11-19; kltn-docs 09 §5.1 "Cancelled", 08 §5.1): the warehouse manager cancels a
-- shipment that waits for dispatch, with a reason and the cartons that are damaged. The order is held for stock
-- (INVENTORY), the cartons go to quarantine or back onto a bin, and the replacement cartons are reallocated, picked and
-- checked into a new shipment.
--
-- POST /fulfillment/shipments/{shipmentId}/cancellation takes a code of its own,
-- fulfillment-shipment-cancellations:UPDATE: no existing code is the warehouse manager's alone for this (the dispatch
-- code is the warehouse staff's too). Granted to the Warehouse Manager (09 §5.1: "Quản lý kho") and to the System
-- Admin, who holds every permission.
--
-- Every role that gained a grant advances app_role.version in the same file (ADR-0008).
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'fulfillment-shipment-cancellations:UPDATE', 'fulfillment-shipment-cancellations', 'UPDATE',
        'Cancel a shipment waiting for dispatch, with a reason and its damaged cartons: the order is held for stock '
            || 'and the cartons go to quarantine or back onto a bin (kltn-docs 09 §5.1)',
        0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, cancellations.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission cancellations ON cancellations.code = 'fulfillment-shipment-cancellations:UPDATE'
     WHERE role.code IN ('WAREHOUSE_MANAGER', 'SYSTEM_ADMIN')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
