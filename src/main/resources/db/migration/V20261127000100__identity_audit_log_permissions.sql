-- =============================================================================
-- BE3-B: the System Admin reads the audit trail (kltn-docs 00 §2.5).
--
-- Every audited action of the platform is written to platform.audit_log by @Auditable and kept 180 days, or 730 for the
-- security-relevant ones (AuditRetention). The documents say changes are audited - the internal prices (13 §4.2), the
-- warehouse map, "who, when, what" (06 §4.1 step 8) - and until now nobody could read them. GET /api/v1/audit-logs and
-- GET /api/v1/audit-logs/{auditLogId} (identity's AuditLogController) answer platform-audit-logs:READ; VIEW_PAGE shows
-- the screen.
--
-- Granted to SYSTEM_ADMIN only: an entry's details and client address may name a person, and reading every user's
-- actions is the administrator's, not a business role's. A role created at run time gets it only by a grant.
--
-- Every role that gained a grant advances app_role.version in the same file (ADR-0008).
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'platform-audit-logs:VIEW_PAGE', 'platform-audit-logs', 'VIEW_PAGE',
        'Open the audit log screen (kltn-docs 00 §2.5)', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'platform-audit-logs:READ', 'platform-audit-logs', 'READ',
        'Search the audit trail and open an entry: who did what, to which record, when, from where (kltn-docs 00 §2.5)',
        0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

-- System Admin holds every permission row (00 §2.5.3; SystemAdminRoleIntegrationTest), the two new ones included.
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
