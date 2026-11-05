-- =============================================================================
-- assistant-conversations:VIEW_PAGE, READ, CREATE for WAREHOUSE_STAFF and PRODUCTION_STAFF
-- (ADR-0010, docs/assistant-design.md §2 and §13 AQ-02, review finding ROLE-10).
-- Module: identity (grants) for assistant. Written with the module, as V20261105000200 was: the repository owner's
-- decision of 2026-10-24, the exception to the schema owner's flow.
--
-- Like V20261105000200, this opens the assistant panel and grants no data. Every tool still carries the permission of
-- the screen that shows the same data, and both roles are limited to the warehouses they are assigned to (data scope
-- WAREHOUSE), so a floor user learns through the assistant only what their own screens already show them, in their
-- own warehouses. Under the grants seeded by 2026-10-29 that is, for WAREHOUSE_STAFF: stock levels and lots, expiring
-- lots, a lot's trace, the pick tasks assigned to them, goods receipts, purchase orders and production orders; for
-- PRODUCTION_STAFF: stock levels and lots and production orders; for both, the catalog and the business rules.
-- Company-wide figures (findLowStock, proposeReorder) stay refused to them, as to every warehouse-limited user.
--
-- The daily question cap (stockflow.assistant.daily-message-limit, AQ-04) applies to them as to everyone.
-- Never CUSTOMER.
--
-- Grants are cached per role version (ADR-0008, CLAUDE.md §5): both roles are advanced here, in the same transaction,
-- or their holders would keep the old grants until the cache entry expired.
-- =============================================================================

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
JOIN identity.permission permission ON permission.resource = 'assistant-conversations'
                                   AND permission.action IN ('VIEW_PAGE', 'READ', 'CREATE')
WHERE role.code IN ('WAREHOUSE_STAFF', 'PRODUCTION_STAFF')
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('WAREHOUSE_STAFF', 'PRODUCTION_STAFF');
