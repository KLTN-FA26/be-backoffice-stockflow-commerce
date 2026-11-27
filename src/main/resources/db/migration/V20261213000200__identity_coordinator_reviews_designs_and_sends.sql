-- =============================================================================
-- The Production Coordinator reviews the customers' designs and sends production orders without approval; the Sales
-- Manager is told of each one sent (spec 2026-11-20 "the Production Coordinator owns production" §1, §2; kltn-docs
-- 12 §4.4, §4.10, 17 §2, §4.3).
--
-- Design review (12 §4.4, §4.10) moves from SALES_MANAGER to PRODUCTION_COORDINATOR: design-designs:APPROVE. The
-- review queue, the review rounds and the reviewers' notice all follow the permission. The coordinator also gets what
-- the review screens call and it did not hold:
--   - design-designs:READ       the design, its history, its artifacts, their preflight and download links, the
--                               review round's project name (DesignWorkflowController, DesignController,
--                               DesignAssetController, DesignProjectController);
--   - design-designs:VIEW_PAGE  the design screens' route guard.
-- Nothing else: reading a customer's design is still decided by the reviewer flag (DesignReadRights), so READ alone
-- opens no design that does not wait for review. The Sales Manager keeps design-designs:READ and so reads a design
-- through an open quote request only (spec §1: accepted, no new read right).
--
-- Production orders are no longer approved (17 §4.3; the /approval endpoint went with V20261213000100's code):
-- production-orders:APPROVE is retired - every grant deleted first (custom roles too), then the permission row,
-- because the row's ON DELETE CASCADE would drop the grants without moving any role's version.
--
-- Told of a production order sent: production-order-sends:READ, granted to SALES_MANAGER and SYSTEM_ADMIN. The spec
-- named it production-orders:MONITOR; it is READ on a resource of its own instead, because MONITOR is no Action: the
-- action set is closed (Action), read back with Action.valueOf when a role's grants are resolved, and the back office
-- parses /api/v1/identity/me/permissions against the same closed list, so a new action would break every user's
-- session until the front end shipped. delivery-delay-alerts:READ (V20261209000400) is the precedent for a permission
-- that is only an audience. production-orders:READ is held too widely to be that audience.
--
-- The two roles' descriptions follow. Every role that gained or lost a grant advances app_role.version once, in the
-- same statement (ADR-0008): PRODUCTION_COORDINATOR, SALES_MANAGER, SYSTEM_ADMIN, and any custom role that held
-- production-orders:APPROVE.
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'production-order-sends:READ', 'production-order-sends', 'READ',
        'Told when a production order is sent (kltn-docs 17 §2)', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'flyway'
      FROM identity.app_role r
      JOIN identity.permission p
        ON p.code IN ('design-designs:APPROVE', 'design-designs:READ', 'design-designs:VIEW_PAGE')
     WHERE r.code = 'PRODUCTION_COORDINATOR'
    UNION ALL
    SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'flyway'
      FROM identity.app_role r
      JOIN identity.permission p ON p.code = 'production-order-sends:READ'
     WHERE r.code IN ('SALES_MANAGER', 'SYSTEM_ADMIN')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
),
review_revoked AS (
    DELETE FROM identity.role_permission rp
     USING identity.app_role r, identity.permission p
     WHERE rp.role_id = r.id AND rp.permission_id = p.id
       AND r.code = 'SALES_MANAGER' AND p.code = 'design-designs:APPROVE'
    RETURNING rp.role_id
),
approval_retired AS (
    DELETE FROM identity.role_permission rp
     USING identity.permission p
     WHERE rp.permission_id = p.id AND p.code = 'production-orders:APPROVE'
    RETURNING rp.role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted
              UNION SELECT role_id FROM review_revoked
              UNION SELECT role_id FROM approval_retired);

DELETE FROM identity.permission WHERE code = 'production-orders:APPROVE';

UPDATE identity.app_role
   SET description = 'Product data and approval, internal prices per customer, print setup, told of production orders sent',
       last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE code = 'SALES_MANAGER'
   AND description = 'Product data and approval, internal prices per customer, print setup, review of the customers'' designs, production order approval';

UPDATE identity.app_role
   SET description = 'Review of the customers'' designs; production orders for what a confirmed order lacks: producers, lead times, sending, print samples, progress',
       last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE code = 'PRODUCTION_COORDINATOR'
   AND description = 'Production orders for what a confirmed order lacks: producers, lead times, print samples, progress';
