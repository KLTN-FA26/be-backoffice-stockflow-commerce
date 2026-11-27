-- =============================================================================
-- assistant-conversations:VIEW_PAGE, READ, CREATE (ADR-0011, docs/assistant-design.md §2).
-- Module: identity (permissions and grants) for assistant
--
-- Holding this resource opens the assistant panel and nothing else. It grants no data: every tool the
-- assistant calls carries the permission of the screen that shows the same data, so a user learns
-- through the assistant only what their own grants already let them read.
--
-- Granted to the back-office roles that ask the questions the assistant answers. Never to CUSTOMER:
-- the assistant is back office only, and its controller refuses a portal customer even if a grant
-- slipped through. SYSTEM_ADMIN holds every permission (the baseline), so it gets these too. The roles are those
-- of the rescope (ADR-0010 of the rescope, V20261111000000): warehouse, sales, production coordination, accounting.
--
-- Grants are cached per role version (ADR-0008): every role that gains rows is advanced here.
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'assistant-conversations:' || a.action, 'assistant-conversations', a.action, 0, NOW(),
       'flyway'
FROM unnest(ARRAY['VIEW_PAGE', 'READ', 'CREATE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission ON permission.resource = 'assistant-conversations'
WHERE role.code IN ('WAREHOUSE_MANAGER', 'SALES_STAFF', 'SALES_MANAGER', 'PRODUCTION_COORDINATOR', 'ACCOUNTANT',
                    'SYSTEM_ADMIN')
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('WAREHOUSE_MANAGER', 'SALES_STAFF', 'SALES_MANAGER', 'PRODUCTION_COORDINATOR', 'ACCOUNTANT',
               'SYSTEM_ADMIN');
