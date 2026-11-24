-- =============================================================================
-- WF-05 audit round 3, RAD-3 (decision Q-11 hỏi thêm, TH-6): goods kept on a custody note end as the customer chose,
-- and the warehouse manager carries it out from the list of goods held for customers ("danh sách thùng hàng hoàn chờ
-- quyết định → ... trả hoặc huỷ hộ"), as the held cartons already are (GET /inventory/stock-packages/customer-custody).
--
--   sales-rma-custody:READ    the list of custody notes and their outcomes (GET /returns/custody-notes): the warehouse
--                             that keeps the goods, and Sales who record the customer's choice. Warehouse-limited in
--                             the service, as every return path is (ReturnWarehouseScope).
--   sales-rma-custody:UPDATE  record that the goods left as chosen (POST /returns/{id}/custody/{evidenceId}/end): the
--                             warehouse manager, as for the held cartons (inventory-stock-packages:UPDATE).
--
-- Sales record the choice itself with sales-rmas:UPDATE, as on the return (custody-choice). SYSTEM_ADMIN holds every
-- permission. Every role that gained a grant advances app_role.version in the same transaction (ADR-0008, CLAUDE.md §5).
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'sales-rma-custody:READ', 'sales-rma-custody', 'READ',
        'List goods kept in custody for customers on return requests, and how each ended (WF-05 Q-11, TH-6)',
        0, NOW(), 'flyway'),
       (gen_random_uuid(), 'sales-rma-custody:UPDATE', 'sales-rma-custody', 'UPDATE',
        'Record that goods kept in custody left as the customer chose: handed back, destroyed, taken on a return',
        0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH grantee (role_code, permission_code) AS (
    VALUES ('SYSTEM_ADMIN', 'sales-rma-custody:READ'),
           ('WAREHOUSE_STAFF', 'sales-rma-custody:READ'),
           ('WAREHOUSE_MANAGER', 'sales-rma-custody:READ'),
           ('SALES_STAFF', 'sales-rma-custody:READ'),
           ('SYSTEM_ADMIN', 'sales-rma-custody:UPDATE'),
           ('WAREHOUSE_MANAGER', 'sales-rma-custody:UPDATE')
),
granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'flyway'
      FROM grantee g
      JOIN identity.app_role r ON r.code = g.role_code
      JOIN identity.permission p ON p.code = g.permission_code
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
