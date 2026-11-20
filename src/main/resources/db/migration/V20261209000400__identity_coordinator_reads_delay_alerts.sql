-- =============================================================================
-- The Production Coordinator reads the delay alerts, without the late deliveries (permission review 2026-11-20, A4;
-- kltn-docs 17 §2, §3, §4.12, BR-30).
--
-- 17 §3: the delay alerts (AT_RISK, PRODUCTION_LATE, PO_NEEDED, DELIVERY_LATE) are for the Production Coordinator
-- "except DELIVERY_LATE", Sales, the Sales Manager, and the warehouse manager for late batches; 17 §2 has the
-- coordinator "receive and handle" them with the producer. GET /api/v1/delay-alerts answered delay-alerts:READ, which
-- the coordinator did not hold - because the same permission was also the audience told of DELIVERY_LATE, and granting
-- it would have told the coordinator of late deliveries too.
--
-- The two jobs are split:
--   - delivery-delay-alerts:READ (new): those who follow delivery. They see DELIVERY_LATE in the list and are told of
--     it. Granted to every role that held delay-alerts:READ, custom roles included, so nobody's list or inbox changes.
--   - delay-alerts:READ: reading the list. Now also granted to PRODUCTION_COORDINATOR, whose list leaves DELIVERY_LATE
--     out (DelayAlertController).
--
-- Every role that gained a grant advances app_role.version in the same file (ADR-0008).
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'delivery-delay-alerts:READ', 'delivery-delay-alerts', 'READ',
        'See late deliveries (DELIVERY_LATE) among the delay alerts and be told of them (kltn-docs 17 §3, BR-30)',
        0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH delivery_grantee AS (
    SELECT DISTINCT rp.role_id
      FROM identity.role_permission rp
      JOIN identity.permission p ON p.id = rp.permission_id
     WHERE p.code = 'delay-alerts:READ'
    UNION
    SELECT r.id FROM identity.app_role r WHERE r.code = 'SYSTEM_ADMIN'
),
granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), g.role_id, p.id, 0, NOW(), 'flyway'
      FROM delivery_grantee g
      JOIN identity.permission p ON p.code = 'delivery-delay-alerts:READ'
    UNION ALL
    SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'flyway'
      FROM identity.app_role r
      JOIN identity.permission p ON p.code = 'delay-alerts:READ'
     WHERE r.code = 'PRODUCTION_COORDINATOR'
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
