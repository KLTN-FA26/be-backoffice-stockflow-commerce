-- =============================================================================
-- assistant-conversations:VIEW_PAGE, READ, CREATE for WAREHOUSE_STAFF
-- (ADR-0011, docs/assistant-design.md §2 and §13 AQ-02, review finding ROLE-10).
-- Module: identity (grants) for assistant. Written with the module, as V20270106000200 was: the repository owner's
-- decision of 2026-10-24, the exception to the schema owner's flow.
--
-- Like V20270106000200, this opens the assistant panel and grants no data. Every tool still carries the permission of
-- the screen that shows the same data, and the role is limited to the warehouses it is assigned to (data scope
-- WAREHOUSE), so a floor user learns through the assistant only what their own screens already show them, in their
-- own warehouses: stock levels and lots, a lot's trace, cartons and locations scanned, the pick tasks assigned to them
-- and goods receipts; the catalog and the business rules. The print-floor role went with in-house production
-- (V20261108000100).
--
-- The daily question cap (stockflow.assistant.daily-message-limit, AQ-04) applies to them as to everyone.
-- Never CUSTOMER.
--
-- Grants are cached per role version (ADR-0008, CLAUDE.md §5): the role is advanced here, in the same transaction, or
-- its holders would keep the old grants until the cache entry expired.
-- =============================================================================

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission ON permission.resource = 'assistant-conversations'
                                   AND permission.action IN ('VIEW_PAGE', 'READ', 'CREATE')
WHERE role.code IN ('WAREHOUSE_STAFF')
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('WAREHOUSE_STAFF');
