-- =============================================================================
-- WF-05 audit round 2, returns M4: the warehouse records goods it keeps for the customer.
--
-- Decision Q-11 and docs 03 §4.2 step 10, 17 §4.10 (TH-2 step 7): goods that came back and that no return receipt takes
-- - a SKU the return did not approve, more than it approved, a parcel after the refund was settled - are kept for the
-- customer, and "kho ghi biên nhận giữ hộ trên yêu cầu trả". The custody note endpoints asked for sales-rmas:CREATE,
-- which no warehouse role holds: the clerk at the dock was told (RETURN_CARTON_NOT_CLAIMED) to record custody and then
-- refused (403), and the goods sat with no document until a Sales person came.
--
-- sales-rma-custody:CREATE is that one action and nothing else: no reading of the return list beyond the note's own
-- answer, no opening or withdrawing a return. Granted to WAREHOUSE_STAFF and WAREHOUSE_MANAGER (the dock), and kept for
-- SALES_STAFF, who recorded custody before and may take goods in at the office. SYSTEM_ADMIN holds every permission.
--
-- Every role that gained a grant advances app_role.version in the same transaction (ADR-0008, CLAUDE.md §5).
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'sales-rma-custody:CREATE', 'sales-rma-custody', 'CREATE',
        'Record goods kept in custody for the customer on a return request: photo, SKU or LPN, quantity (WF-05 Q-11)',
        0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH grantee (role_code) AS (
    VALUES ('SYSTEM_ADMIN'), ('WAREHOUSE_STAFF'), ('WAREHOUSE_MANAGER'), ('SALES_STAFF')
),
granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'flyway'
      FROM grantee g
      JOIN identity.app_role r ON r.code = g.role_code
      JOIN identity.permission p ON p.code = 'sales-rma-custody:CREATE'
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
