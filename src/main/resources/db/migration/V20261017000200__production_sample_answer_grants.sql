-- =============================================================================
-- Identity: who may answer a sample, and who may only look at it (SCRUM-394/403).
-- Docs: 03-state-machines §17 (SENT_TO_CUSTOMER -> APPROVED | CHANGES_REQUESTED, actor "Customer (portal) /
-- Sales staff on their behalf"), plan 2026-10-06 §3.2.
--
-- V20261010000500 made the customer's answer an UPDATE on production-sample-requests and gave UPDATE to
-- SALES_STAFF and QC_STAFF, but nothing to CUSTOMER. Two consequences:
--   * the portal could not show a customer their own samples, let alone take their answer;
--   * a QC inspector, holding UPDATE, could approve a sample on the customer's behalf - an approval is
--     what lets a new design go to mass print (BR-PRD-08), so it must be the customer's or Sales'.
--
-- From here the answer (approve / request changes) is the APPROVE action of production-sample-requests:
--   CUSTOMER     VIEW_PAGE, READ, APPROVE   their own rounds only; the service holds a portal user to
--                                           their own customer and refuses every back-office verb
--   SALES_STAFF  + APPROVE                  records the answer given to them off-system
--   QC_STAFF     - UPDATE                   no sample-request endpoint is QC work: passing or failing the
--                                           printed sample is recorded on its SAMPLE production order
--                                           (production-orders:UPDATE, kept), and the round becomes READY
--                                           from there (SCRUM-399/400). READ stays, to QC against it.
-- UPDATE keeps meaning "Sales handles the round": edit, submit, record sending, cancel.
--
-- Grants are cached per role version (ADR-0008): every role whose grants change is advanced below in the
-- same transaction. SYSTEM_ADMIN already holds every permission (V20260929000200) and is unchanged.
-- Re-running is harmless: inserts skip existing rows, the delete finds nothing the second time.
-- =============================================================================

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('CUSTOMER',    'production-sample-requests', 'VIEW_PAGE,READ,APPROVE'),
          ('SALES_STAFF', 'production-sample-requests', 'APPROVE')
     ) AS g(role_code, resource, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = g.resource
 AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

DELETE FROM identity.role_permission rp
USING identity.app_role role, identity.permission permission
WHERE rp.role_id = role.id
  AND rp.permission_id = permission.id
  AND role.code = 'QC_STAFF'
  AND permission.resource = 'production-sample-requests'
  AND permission.action = 'UPDATE';

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('CUSTOMER', 'SALES_STAFF', 'QC_STAFF');
