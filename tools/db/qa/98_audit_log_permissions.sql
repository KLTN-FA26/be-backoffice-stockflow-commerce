-- =============================================================================
-- Checks of the audit log read permission (V20261127000100, BE3-B; kltn-docs 00 §2.5): GET /api/v1/audit-logs answers
-- platform-audit-logs:READ, and an entry's details and client address may name a person, so only the System Admin
-- holds it - and still holds every permission row, as 00 §2.5.3 says. The trail it reads keeps its retention class
-- written with each row. Needs the demo seed. Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

\echo '--- the permission'
SELECT pg_temp.expect_true('AL1 platform-audit-logs has exactly VIEW_PAGE and READ: nothing writes an audit entry by hand',
    (SELECT array_agg(action::text ORDER BY action) FROM identity.permission WHERE resource = 'platform-audit-logs')
        = ARRAY['READ', 'VIEW_PAGE']);
SELECT pg_temp.expect_true('AL2 only the System Admin holds it (an entry may name a person)',
    (SELECT array_agg(DISTINCT r.code::text) FROM identity.role_permission rp
       JOIN identity.app_role r ON r.id = rp.role_id
       JOIN identity.permission p ON p.id = rp.permission_id
      WHERE p.resource = 'platform-audit-logs') = ARRAY['SYSTEM_ADMIN']);
SELECT pg_temp.expect_true('AL3 the System Admin still holds every permission row (00 §2.5.3)',
    NOT EXISTS (SELECT 1 FROM identity.permission p
                 WHERE NOT EXISTS (SELECT 1 FROM identity.role_permission rp
                                     JOIN identity.app_role r ON r.id = rp.role_id
                                    WHERE r.code = 'SYSTEM_ADMIN' AND rp.permission_id = p.id)));

\echo '--- the trail it reads'
SELECT pg_temp.expect_true('AL4 the indexes the search filters on are there: actor and time, resource, time, request',
    (SELECT count(*) FROM pg_indexes WHERE schemaname = 'platform' AND tablename = 'audit_log'
        AND indexname IN ('ix_audit_actor_time', 'ix_audit_resource', 'ix_audit_occurred', 'ix_audit_correlation')) = 4);

ROLLBACK;
