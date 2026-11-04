-- =============================================================================
-- QC-REVERIFY-1022 (lead's decision 2026-10-22): only the warehouse manager re-verifies the design of a package held at
-- packing. Module: fulfillment (identity grants). Docs: kltn-docs warehouse/08-packing, order states (an On Hold pack is
-- resolved by the Manager; QC is not listed there). V20261022000200 had granted the endpoint's permission to QC_STAFF
-- because the old role list let QC through.
--
-- fulfillment-design-reverification:UPDATE (POST /fulfillment/tasks/{taskId}/design-integrity-reverification) is
-- removed from QC_STAFF only. WAREHOUSE_MANAGER and SYSTEM_ADMIN keep it. QC keeps fulfillment-design-reverification:READ
-- (it can still see a held task and the design files); only the act of releasing the hold is taken away.
-- Only the seeded grant is removed: an administrator can still grant the permission to any role at run time.
-- =============================================================================

DELETE FROM identity.role_permission rp
USING identity.app_role role, identity.permission permission
WHERE rp.role_id = role.id
  AND rp.permission_id = permission.id
  AND role.code = 'QC_STAFF'
  AND permission.code = 'fulfillment-design-reverification:UPDATE';

-- ADR-0008: the role's permission cache must change generation in this same transaction.
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code = 'QC_STAFF';
