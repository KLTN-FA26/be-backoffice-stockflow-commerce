-- =============================================================================
-- warehouse-warehouses:READ for ORDER_COORDINATOR - the release dialog needs a warehouse to pick.
-- Module: identity (grants) for warehouse
--
-- Decision WH-READ-COORD (23/10): releasing an order asks the order coordinator to choose the warehouse the
-- order is released to, but GET /api/v1/warehouses answered 403 to that role, so the dialog had nothing to
-- list. READ only: no VIEW_PAGE (the coordinator gets no warehouse-admin screen), no CREATE or UPDATE.
-- The list applies no data scope, so the coordinator sees every warehouse it may release to.
-- =============================================================================

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
  FROM identity.app_role role
  JOIN identity.permission permission
    ON permission.code = 'warehouse-warehouses:READ'
 WHERE role.code = 'ORDER_COORDINATOR'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Grants are cached per role version (ADR-0008): advance the role whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE code = 'ORDER_COORDINATOR';
